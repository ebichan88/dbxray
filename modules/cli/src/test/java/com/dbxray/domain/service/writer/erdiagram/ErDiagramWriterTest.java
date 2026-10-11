package com.dbxray.domain.service.writer.erdiagram;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ErDiagramWriter のER図出力に関するテスト<br>
 * ノード数の上限超過時にグループ分割されるかどうかを、生成されるファイル構成で検証する
 */
public class ErDiagramWriterTest {

  private static final Path OUT = Path.of("output");

  /** 書き込み内容をメモリ上に収集するFileRepositoryのスタブ */
  private static class InMemoryFileRepository implements FileRepository {
    private final Map<Path, String> files = new LinkedHashMap<>();

    @Override
    public void writeFile(Path filePath, List<String> contents) {
      files.put(filePath, String.join("", contents));
    }

    @Override
    public void appendFile(Path filePath, List<String> contents) {
      files.merge(filePath, String.join("", contents), String::concat);
    }

    @Override
    public void createDirectory(Path filePath) {
      // 何もしない
    }

    @Override
    public boolean exists(Path path) {
      return false;
    }

    @Override
    public boolean isDirectory(Path path) {
      return false;
    }

    @Override
    public List<Path> listFiles(Path directory) {
      return List.of();
    }

    @Override
    public List<String> readFile(Path filePath) {
      return List.of();
    }

    @Override
    public Path createTempDirectory(String prefix) {
      return Path.of(prefix);
    }

    @Override
    public void deleteDirectory(Path directory) {
      // 何もしない
    }
  }

  private InMemoryFileRepository fileRepository;
  private ErDiagramWriter writer;

  @BeforeEach
  void setUp() {
    fileRepository = new InMemoryFileRepository();
    writer =
        new ErDiagramWriter(
            fileRepository,
            new DefaultOutputPathResolver(),
            new PagedSectionWriter(fileRepository, new DefaultOutputPathResolver()));
  }

  private BaseInfoEntity baseInfo() {
    return new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 23));
  }

  private OutputRoot outputRoot() {
    return new OutputRoot(OUT, baseInfo());
  }

  private TableEntity table(String physical) {
    return new TableEntity("testdb", "public", "", physical, TableType.TABLE, "");
  }

  private ForeignKeyEntity fk(String table, String name, String refTable) {
    return ForeignKeyFixtures.physical("public", table, name, "public", refTable);
  }

  private List<String> fileNames() {
    return fileRepository.files.keySet().stream()
        .map(path -> path.getFileName().toString())
        .toList();
  }

  private String contentOf(String fileName) {
    return fileRepository.files.entrySet().stream()
        .filter(entry -> entry.getKey().getFileName().toString().equals(fileName))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> new AssertionError("生成されていないファイル: " + fileName));
  }

  @Test
  @DisplayName("上限内の場合はグループ分割せず、スキーマページに図を1枚出力する")
  void testWithinLimitKeepsSingleDiagram() {
    final List<TableEntity> tables = List.of(table("hub"), table("a"), table("b"));
    final ForeignKeys foreignKeys =
        ForeignKeys.of(List.of(fk("a", "fk_a", "hub"), fk("b", "fk_b", "hub")));

    writer.writeErDiagram(
        Tables.of(tables),
        foreignKeys,
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(80));

    assertTrue(fileNames().contains("erDiagram_testdb_public.md"));
    assertTrue(fileNames().stream().noneMatch(name -> name.contains("_group")), "グループファイルは生成されない");
    assertTrue(contentOf("erDiagram_testdb_public.md").contains("```mermaid"));
  }

  @Test
  @DisplayName("上限超過かつ独立したまとまりが複数ある場合はグループに分割する")
  void testOverflowWithMultipleComponentsSplitsIntoGroups() {
    // 2テーブルずつの独立したまとまりを5組（ノード数10件）作り、上限4件で超過させる
    final List<TableEntity> tables = new ArrayList<>();
    final List<ForeignKeyEntity> foreignKeys = new ArrayList<>();
    IntStream.rangeClosed(1, 5)
        .forEach(
            i -> {
              tables.add(table("child" + i));
              tables.add(table("parent" + i));
              foreignKeys.add(fk("child" + i, "fk" + i, "parent" + i));
            });

    writer.writeErDiagram(
        Tables.of(tables),
        ForeignKeys.of(foreignKeys),
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(4));

    // 1グループあたり2まとまり(4ノード)まで詰め込まれるため、5まとまりは3グループになる
    assertTrue(fileNames().contains("erDiagram_testdb_public_group1.md"));
    assertTrue(fileNames().contains("erDiagram_testdb_public_group2.md"));
    assertTrue(fileNames().contains("erDiagram_testdb_public_group3.md"));
    assertFalse(fileNames().contains("erDiagram_testdb_public_group4.md"));
    // 各グループページには図が描画される
    assertTrue(contentOf("erDiagram_testdb_public_group1.md").contains("```mermaid"));
    // スキーマページはグループ索引になる
    final String schemaPage = contentOf("erDiagram_testdb_public.md");
    assertFalse(schemaPage.contains("```mermaid"));
    assertTrue(schemaPage.contains("3個のグループへ分割しました。"));
    assertTrue(schemaPage.contains("## グループ一覧"));
    assertTrue(schemaPage.contains("[■](./erDiagram_testdb_public_group1.md)"));
  }

  @Test
  @DisplayName("上限超過でも単一の巨大なまとまりの場合はグループ分割せず外部キー一覧にフォールバックする")
  void testOverflowWithSingleComponentFallsBack() {
    // 全テーブルが1つのハブに繋がる構成のため、分割しても1つのまとまりにしかならない
    final List<TableEntity> tables = new ArrayList<>(List.of(table("hub")));
    final List<ForeignKeyEntity> foreignKeys = new ArrayList<>();
    IntStream.rangeClosed(1, 10)
        .forEach(
            i -> {
              tables.add(table("t" + i));
              foreignKeys.add(fk("t" + i, "fk" + i, "hub"));
            });

    writer.writeErDiagram(
        Tables.of(tables),
        ForeignKeys.of(foreignKeys),
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(4));

    assertTrue(fileNames().stream().noneMatch(name -> name.contains("_group")), "グループファイルは生成されない");
    final String schemaPage = contentOf("erDiagram_testdb_public.md");
    assertFalse(schemaPage.contains("```mermaid"));
    assertTrue(schemaPage.contains("上限（erDiagramMaxNodes = 4件）を超えるため描画を省略しました。"));
    assertTrue(schemaPage.contains("## 外部キー一覧"));
  }

  @Test
  @DisplayName("巨大なまとまりと小さなまとまりが混在する場合、小さい側は図として出力される")
  void testOverflowMixedComponents() {
    final List<TableEntity> tables = new ArrayList<>(List.of(table("hub")));
    final List<ForeignKeyEntity> foreignKeys = new ArrayList<>();
    // 上限を超える巨大なまとまり
    IntStream.rangeClosed(1, 10)
        .forEach(
            i -> {
              tables.add(table("t" + i));
              foreignKeys.add(fk("t" + i, "fk" + i, "hub"));
            });
    // 巨大なまとまりに繋がっていない小さなまとまり
    tables.add(table("x"));
    tables.add(table("y"));
    foreignKeys.add(fk("x", "fk_xy", "y"));

    writer.writeErDiagram(
        Tables.of(tables),
        ForeignKeys.of(foreignKeys),
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(4));

    // グループ1は巨大なまとまり（上限超のためフォールバック）
    final String group1 = contentOf("erDiagram_testdb_public_group1.md");
    assertFalse(group1.contains("```mermaid"));
    assertTrue(group1.contains("## 外部キー一覧"));
    // グループ2は小さなまとまり（図として描画される）
    final String group2 = contentOf("erDiagram_testdb_public_group2.md");
    assertTrue(group2.contains("```mermaid"));
    assertTrue(group2.contains("public_y ||--o{ public_x : \"fk_xy\""));
    // グループページからスキーマのER図へ戻れる
    assertTrue(group2.contains("[スキーマのER図へ](./erDiagram_testdb_public.md)"));
  }

  @Test
  @DisplayName("上限なし（0以下）の場合はテーブル数に関わらずグループ分割しない")
  void testNoLimitNeverGroups() {
    final List<TableEntity> tables = new ArrayList<>(List.of(table("hub")));
    final List<ForeignKeyEntity> foreignKeys = new ArrayList<>();
    IntStream.rangeClosed(1, 50)
        .forEach(
            i -> {
              tables.add(table("t" + i));
              foreignKeys.add(fk("t" + i, "fk" + i, "hub"));
            });

    writer.writeErDiagram(
        Tables.of(tables),
        ForeignKeys.of(foreignKeys),
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(0));

    assertTrue(fileNames().stream().noneMatch(name -> name.contains("_group")));
    assertTrue(contentOf("erDiagram_testdb_public.md").contains("```mermaid"));
  }
}
