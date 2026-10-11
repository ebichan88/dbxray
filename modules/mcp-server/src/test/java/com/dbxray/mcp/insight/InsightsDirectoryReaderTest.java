package com.dbxray.mcp.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.UserCorrectableException;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.ViewpointEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link InsightsDirectoryReader}のテスト */
class InsightsDirectoryReaderTest {

  @TempDir Path outputBaseDir;

  private final InsightsDirectoryReader reader = new InsightsDirectoryReader();

  /** {@code --snapshot}に指定されたディレクトリ（参考情報はこの親の兄弟から読む） */
  private Path snapshotDirectory() {
    return outputBaseDir.resolve("snapshot");
  }

  @Test
  @DisplayName("snapshotディレクトリの親の兄弟（insights）から、DBごとのviewpoints.jsonを読み込む")
  void readsViewpointsFromSiblingOfSnapshotDirectory() throws IOException {
    write(
        "insights/testdb/viewpoints.json",
        "{\"formatVersion\":1,\"viewpoints\":["
            + "{\"id\":\"order\",\"name\":\"受注管理\",\"description\":\"受注から出荷までを扱う\","
            + "\"tables\":[{\"schema\":\"sales\",\"name\":\"orders\"}]}]}");
    write(
        "insights/otherdb/viewpoints.json",
        "{\"formatVersion\":1,\"viewpoints\":[{\"id\":\"hr\",\"name\":\"人事\",\"tables\":[]}]}");

    final List<ViewpointEntry> viewpoints = reader.readViewpoints(snapshotDirectory());

    assertEquals(
        List.of(
            new ViewpointEntry("otherdb", "hr", "人事", "", List.of()),
            new ViewpointEntry(
                "testdb",
                "order",
                "受注管理",
                "受注から出荷までを扱う",
                List.of(new ObjectKey("testdb", "sales", "orders")))),
        viewpoints);
  }

  @Test
  @DisplayName("insightsディレクトリが無い場合は0件とする（観点を1つも宣言していないスナップショット）")
  void returnsEmptyWhenInsightsDirectoryIsMissing() {
    assertTrue(reader.readViewpoints(snapshotDirectory()).isEmpty());
  }

  @Test
  @DisplayName("DBのディレクトリにviewpoints.jsonが無い場合は、そのDB分は0件とする")
  void returnsEmptyWhenViewpointsFileIsMissingForDatabase() throws IOException {
    Files.createDirectories(outputBaseDir.resolve("insights").resolve("testdb"));

    assertTrue(reader.readViewpoints(snapshotDirectory()).isEmpty());
  }

  @Test
  @DisplayName("未知の項目は無視する（cliが項目を追加しても読み込める）")
  void ignoresUnknownFields() throws IOException {
    write(
        "insights/testdb/viewpoints.json",
        "{\"formatVersion\":1,\"generatedBy\":\"x\",\"viewpoints\":["
            + "{\"id\":\"order\",\"name\":\"受注管理\",\"newField\":1,"
            + "\"tables\":[{\"schema\":\"sales\",\"name\":\"orders\",\"newColumnField\":true}]}]}");

    final List<ViewpointEntry> viewpoints = reader.readViewpoints(snapshotDirectory());

    assertEquals("order", viewpoints.get(0).id());
  }

  @Test
  @DisplayName("対応していない新しい形式のバージョンの場合は、MCPサーバーの更新を促す")
  void failsOnNewerFormatVersion() throws IOException {
    write("insights/testdb/viewpoints.json", "{\"formatVersion\":2,\"viewpoints\":[]}");

    final UserCorrectableException e =
        assertThrows(
            UserCorrectableException.class, () -> reader.readViewpoints(snapshotDirectory()));

    assertTrue(e.getMessage().contains("formatVersion=2"), e.getMessage());
  }

  @Test
  @DisplayName("formatVersionが無い場合は失敗にする")
  void failsWhenFormatVersionIsMissing() throws IOException {
    write("insights/testdb/viewpoints.json", "{\"viewpoints\":[]}");

    assertThrows(UserCorrectableException.class, () -> reader.readViewpoints(snapshotDirectory()));
  }

  @Test
  @DisplayName("JSONとして読めない場合は、ファイルを示して失敗にする")
  void failsOnBrokenJson() throws IOException {
    final Path file = write("insights/testdb/viewpoints.json", "{\"formatVersion\":1,<<<<<<< HEAD");

    final UserCorrectableException e =
        assertThrows(
            UserCorrectableException.class, () -> reader.readViewpoints(snapshotDirectory()));

    assertTrue(e.getMessage().contains("file=" + file), e.getMessage());
  }

  private Path write(String relativePath, String content) throws IOException {
    final Path file = outputBaseDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  @Test
  @DisplayName("readFunctionTableUsages: DB・スキーマごとのfunctionTableUsages.jsonを読み、スキーマ名はファイルの中の値を使う")
  void readsFunctionTableUsages() throws IOException {
    write(
        "insights/testdb/My~2FSchema/functionTableUsages.json",
        "{\"formatVersion\":1,\"schema\":\"My/Schema\",\"functions\":["
            + "{\"name\":\"f\",\"arguments\":\"p integer\",\"definitionHash\":\"abc\",\"status\":\"analyzed\","
            + "\"tables\":[{\"schema\":\"sample\",\"name\":\"employee\",\"operations\":[\"R\",\"U\"]},"
            + "{\"name\":\"project\",\"schemaResolution\":\"search_path\",\"schemaCandidates\":[\"sample\",\"other\"],\"operations\":[\"D\"]}],"
            + "\"dynamicSql\":[\"EXECUTE\"],\"incomplete\":true,\"newField\":1},"
            + "{\"name\":\"py\",\"status\":\"unsupported_language\",\"language\":\"plpython3u\"}]}");
    write("insights/testdb/viewpoints.json", "{\"formatVersion\":1,\"viewpoints\":[]}");

    final List<FunctionTableUsageEntry> entries =
        reader.readFunctionTableUsages(snapshotDirectory());

    assertEquals(
        List.of(
            new FunctionTableUsageEntry(
                new ObjectKey("testdb", "My/Schema", "f"),
                "p integer",
                "abc",
                "analyzed",
                "",
                List.of(
                    new UsedTable("sample", "employee", "", List.of(), List.of("R", "U")),
                    new UsedTable(
                        "", "project", "search_path", List.of("sample", "other"), List.of("D"))),
                List.of("EXECUTE"),
                true,
                false),
            new FunctionTableUsageEntry(
                new ObjectKey("testdb", "My/Schema", "py"),
                "",
                "",
                "unsupported_language",
                "plpython3u",
                List.of(),
                List.of(),
                false,
                false)),
        entries);
  }

  @Test
  @DisplayName("readFunctionTableUsages: 参考情報のディレクトリ・ファイルが無い場合は0件とする（--previewを付けずに出力した場合）")
  void returnsEmptyFunctionTableUsagesWhenMissing() throws IOException {
    assertTrue(reader.readFunctionTableUsages(snapshotDirectory()).isEmpty());

    Files.createDirectories(outputBaseDir.resolve("insights").resolve("testdb").resolve("sample"));
    write("insights/testdb/viewpoints.json", "{\"formatVersion\":1,\"viewpoints\":[]}");

    assertTrue(reader.readFunctionTableUsages(snapshotDirectory()).isEmpty());
  }

  @Test
  @DisplayName("readFunctionTableUsages: 対応していない新しい形式のバージョンは、MCPサーバーの更新を促す誤りとする")
  void rejectsNewerFunctionTableUsagesFormat() throws IOException {
    write(
        "insights/testdb/sample/functionTableUsages.json",
        "{\"formatVersion\":2,\"schema\":\"sample\",\"functions\":[]}");

    final UserCorrectableException e =
        assertThrows(
            UserCorrectableException.class,
            () -> reader.readFunctionTableUsages(snapshotDirectory()));
    assertTrue(e.getMessage().contains("formatVersion=2"), e.getMessage());
  }
}
