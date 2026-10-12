package com.dbxray.domain.service.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.snapshot.ContentDiff;
import com.dbxray.domain.model.snapshot.DiffResult;
import com.dbxray.domain.service.UnifiedDiffGenerator;
import com.dbxray.infrastructure.file.repository.LocalFileRepository;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.infrastructure.snapshot.JacksonSnapshotSerializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SnapshotDiff のスナップショット同士の比較に関するテスト */
public class SnapshotDiffTest {

  private static final String TABLES = "testdb/public/tables.jsonl";

  private final SnapshotDiff service =
      new SnapshotDiff(
          new LocalFileRepository(),
          new DefaultOutputPathResolver(),
          new JacksonSnapshotSerializer(),
          new UnifiedDiffGenerator());

  /** 差分の対象の表示名（{@link ContentDiff#target()}）だけを取り出すヘルパー */
  private List<String> targetsOf(DiffResult result) {
    return result.contentDiffer().stream().map(ContentDiff::target).toList();
  }

  private void write(Path dir, String relativePath, String... lines) throws IOException {
    Path file = dir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(
        file, lines.length == 0 ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  private String table(String name, String columnType) {
    return "{\"schema\":\"public\",\"name\":\""
        + name
        + "\",\"type\":\"table\",\"columns\":[{\"name\":\"id\",\"type\":\""
        + columnType
        + "\"}]}";
  }

  private void assertUnifiedDiffEquals(
      List<String> expected, List<String> actual, String testName) {
    if (!expected.equals(actual)) {
      var msg = new StringBuilder();
      msg.append("\n").append(testName).append("\n");
      msg.append("EXPECTED (size=").append(expected.size()).append("):\n");
      for (int i = 0; i < expected.size(); i++) {
        msg.append(String.format("[%d] %s\n", i, expected.get(i)));
      }
      msg.append("ACTUAL (size=").append(actual.size()).append("):\n");
      for (int i = 0; i < actual.size(); i++) {
        msg.append(String.format("[%d] %s\n", i, actual.get(i)));
      }
      throw new AssertionError(msg.toString());
    }
  }

  @Test
  @DisplayName("compare: 同じ内容の場合は差分なし")
  void testNoDifference(@TempDir Path generated, @TempDir Path committed) throws IOException {
    write(generated, TABLES, table("t1", "integer"), table("t2", "text"));
    write(committed, TABLES, table("t1", "integer"), table("t2", "text"));

    assertFalse(service.compare(generated, committed).hasDifference());
  }

  @Test
  @DisplayName("compare: オブジェクトの追加・削除・変更を、ファイル名ではなくオブジェクト単位で報告する")
  void testReportsObjectLevelDifferences(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(
        generated,
        TABLES,
        table("added", "integer"),
        table("changed", "bigint"),
        table("same", "text"));
    write(
        committed,
        TABLES,
        table("changed", "integer"),
        table("dropped", "text"),
        table("same", "text"));

    DiffResult result = service.compare(generated, committed);

    assertEquals(List.of("table public.added"), result.onlyInGenerated());
    assertEquals(List.of("table public.dropped"), result.onlyInCommitted());
    assertEquals(List.of("table public.changed"), targetsOf(result));
  }

  @Test
  @DisplayName("compare: 内容が一致しないオブジェクトには、変更箇所を示すunified diffが付く")
  void testContentDifferIncludesUnifiedDiff(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(committed, TABLES, table("changed", "integer"));
    write(generated, TABLES, table("changed", "bigint"));

    DiffResult result = service.compare(generated, committed);

    assertEquals(1, result.contentDiffer().size());
    ContentDiff diff = result.contentDiffer().get(0);
    assertEquals("table public.changed", diff.target());
    var expected =
        List.of(
            "--- committed/testdb/public/tables.jsonl (table public.changed)",
            "+++ generated/testdb/public/tables.jsonl (table public.changed)",
            "@@ -3,6 +3,6 @@",
            "   \"name\": \"changed\"",
            "   \"type\": \"table\"",
            "   \"columns\": [",
            "-    {\"name\":\"id\",\"type\":\"integer\"}",
            "+    {\"name\":\"id\",\"type\":\"bigint\"}",
            "   ]",
            " }");
    var actual = diff.unifiedDiff();
    assertUnifiedDiffEquals(expected, actual, "testContentDifferIncludesUnifiedDiff");
  }

  @Test
  @DisplayName("compare: スキーマ配下のオブジェクトのファイル以外(database.json)のunified diffには、表示名を重複させない")
  void testUnifiedDiffLabelForWholeFileOmitsDuplicateName(
      @TempDir Path generated, @TempDir Path committed) throws IOException {
    write(generated, "testdb/database.json", "{\"formatVersion\":2,\"name\":\"testdb\"}");
    write(committed, "testdb/database.json", "{\"formatVersion\":1,\"name\":\"testdb\"}");

    DiffResult result = service.compare(generated, committed);

    ContentDiff diff = result.contentDiffer().get(0);
    assertEquals(Path.of("testdb", "database.json").toString(), diff.target());
    var lines = diff.unifiedDiff();
    if (lines.size() < 2) {
      throw new AssertionError(
          "Expected at least 2 lines in unifiedDiff, but got "
              + lines.size()
              + ":\n"
              + String.join("\n", lines));
    }
    var expectedFirst = "--- committed/testdb/database.json";
    var actualFirst = lines.get(0);
    if (!expectedFirst.equals(actualFirst)) {
      throw new AssertionError(
          "First line mismatch:\nExpected: " + expectedFirst + "\nActual: " + actualFirst);
    }
    var expectedSecond = "+++ generated/testdb/database.json";
    var actualSecond = lines.get(1);
    if (!expectedSecond.equals(actualSecond)) {
      throw new AssertionError(
          "Second line mismatch:\nExpected: " + expectedSecond + "\nActual: " + actualSecond);
    }
  }

  @Test
  @DisplayName("compare: 行の並び順のみが異なる場合は差分なし")
  void testIgnoresLineOrder(@TempDir Path generated, @TempDir Path committed) throws IOException {
    write(generated, TABLES, table("t1", "integer"), table("t2", "text"));
    write(committed, TABLES, table("t2", "text"), table("t1", "integer"));

    assertFalse(service.compare(generated, committed).hasDifference());
  }

  @Test
  @DisplayName("compare: コミット済みのファイルに空行・空白だけの行が混ざっていても、読み飛ばして差分なしとする")
  void testIgnoresBlankLines(@TempDir Path generated, @TempDir Path committed) throws IOException {
    write(generated, TABLES, table("t1", "integer"), table("t2", "text"));
    // 手で編集・マージしたファイルを想定する
    write(committed, TABLES, "", table("t1", "integer"), "  ", table("t2", "text"), "");

    assertFalse(service.compare(generated, committed).hasDifference());
  }

  @Test
  @DisplayName("compare: コミット済みのファイルの改行がCRLFでも差分なしとする（Gitが改行を変換して取り出した場合）")
  void testIgnoresCrlfLineEndings(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(generated, TABLES, table("t1", "integer"), table("t2", "text"));
    Path file = committed.resolve(TABLES);
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        table("t1", "integer") + "\r\n" + table("t2", "text") + "\r\n",
        StandardCharsets.UTF_8);

    assertFalse(service.compare(generated, committed).hasDifference());
  }

  @Test
  @DisplayName("compare: 同名の関数（オーバーロード）は引数で区別する")
  void testDistinguishesOverloadedFunctions(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(
        generated,
        "testdb/public/functions.jsonl",
        "{\"schema\":\"public\",\"name\":\"calc\",\"arguments\":\"x integer\",\"definition\":\"A\"}",
        "{\"schema\":\"public\",\"name\":\"calc\",\"arguments\":\"x integer, y integer\",\"definition\":\"B2\"}");
    write(
        committed,
        "testdb/public/functions.jsonl",
        "{\"schema\":\"public\",\"name\":\"calc\",\"arguments\":\"x integer\",\"definition\":\"A\"}",
        "{\"schema\":\"public\",\"name\":\"calc\",\"arguments\":\"x integer, y integer\",\"definition\":\"B\"}",
        "{\"schema\":\"public\",\"name\":\"calc\",\"definition\":\"C\"}");

    DiffResult result = service.compare(generated, committed);

    assertEquals(List.of(), result.onlyInGenerated());
    assertEquals(List.of("function public.calc()"), result.onlyInCommitted());
    assertEquals(List.of("function public.calc(x integer, y integer)"), targetsOf(result));
  }

  @Test
  @DisplayName("compare: スキーマ配下のオブジェクトのファイル以外（database.json）はファイル単位で比較する")
  void testComparesOtherFilesAsWhole(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(generated, "testdb/database.json", "{\"formatVersion\":2,\"name\":\"testdb\"}");
    write(committed, "testdb/database.json", "{\"formatVersion\":1,\"name\":\"testdb\"}");

    DiffResult result = service.compare(generated, committed);

    assertEquals(List.of(Path.of("testdb", "database.json").toString()), targetsOf(result));
  }

  @Test
  @DisplayName("compare: 別スキーマのファイルに存在する同名オブジェクトは取り違えない")
  void testSeparatesSameNameAcrossFiles(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(generated, TABLES, table("t1", "integer"));
    write(committed, TABLES, table("t1", "integer"));
    write(committed, "otherdb/public/tables.jsonl", table("t1", "integer"));

    DiffResult result = service.compare(generated, committed);

    assertEquals(List.of("table public.t1"), result.onlyInCommitted());
    assertTrue(result.contentDiffer().isEmpty());
  }

  @Test
  @DisplayName("compare: コミット側のディレクトリが存在しない場合は、生成側の全オブジェクトをonlyInGeneratedとする")
  void testCommittedDirectoryDoesNotExist(@TempDir Path generated, @TempDir Path committed)
      throws IOException {
    write(generated, TABLES, table("t1", "integer"), table("t2", "text"));

    DiffResult result = service.compare(generated, committed.resolve("not-exist"));

    assertEquals(List.of("table public.t1", "table public.t2"), result.onlyInGenerated());
  }
}
