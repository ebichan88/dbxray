package com.dbxray.infrastructure.path;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.service.path.OutputRoot;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DefaultOutputPathResolver の各種ファイルパス組み立てに関するテスト */
public class DefaultOutputPathResolverTest {

  private final DefaultOutputPathResolver resolver = new DefaultOutputPathResolver();
  private final Path baseDir = Path.of("output");
  private final BaseInfoEntity baseInfo =
      new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH);
  private final OutputRoot root = new OutputRoot(baseDir, baseInfo);

  private TableEntity table(String schema, String physical, String tableType) {
    return new TableEntity("testdb", schema, "", physical, TableType.fromId(tableType), "", "");
  }

  @Test
  @DisplayName("resolveOutputBaseDir: 指定したパスをそのまま返す")
  void testResolveBaseOutputDirUsesSpecifiedPath() {
    Path result = resolver.resolveOutputBaseDir("custom_out");
    assertEquals(Path.of("custom_out"), result);
  }

  @Test
  @DisplayName("resolveOutputBaseDir: nullの場合は./outputへフォールバックする")
  void testResolveBaseOutputDirFallbackWhenNull() {
    Path result = resolver.resolveOutputBaseDir(null);
    assertEquals(Path.of("./output"), result);
  }

  @Test
  @DisplayName("resolveOutputBaseDir: 空白のみの場合も./outputへフォールバックする")
  void testResolveBaseOutputDirFallbackWhenWhitespace() {
    Path result = resolver.resolveOutputBaseDir("   ");
    assertEquals(Path.of("./output"), result);
  }

  @Test
  @DisplayName("isRemovableOutputDir: カレントディレクトリ配下のサブディレクトリは削除を認める")
  void testIsRemovableOutputDirAllowsSubdirectory() {
    assertTrue(resolver.isRemovableOutputDir(Path.of("output")));
  }

  @Test
  @DisplayName("isRemovableOutputDir: ルート・ホーム・カレントディレクトリ自体は削除を認めない")
  void testIsRemovableOutputDirRejectsUnsafeDirectories() {
    assertFalse(resolver.isRemovableOutputDir(Path.of(".")));
    assertFalse(resolver.isRemovableOutputDir(Path.of("").toAbsolutePath().getRoot()));
    assertFalse(resolver.isRemovableOutputDir(Path.of(System.getProperty("user.home"))));
  }

  @Test
  @DisplayName("isRemovableOutputDir: カレント・ホームを含む上位のディレクトリは削除を認めない")
  void testIsRemovableOutputDirRejectsAncestors() {
    assertFalse(resolver.isRemovableOutputDir(Path.of("..")));
    assertFalse(resolver.isRemovableOutputDir(Path.of("output", "..", "..")));
    final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
    if (home.getParent() != null) {
      assertFalse(resolver.isRemovableOutputDir(home.getParent()));
    }
  }

  @Test
  @DisplayName("isRemovableOutputDir: カレント・ホームと名前が前方一致するだけの兄弟ディレクトリは削除を認める")
  void testIsRemovableOutputDirAllowsSiblingWithSamePrefix() {
    final Path cwd = Path.of("").toAbsolutePath();
    assertTrue(resolver.isRemovableOutputDir(cwd.resolveSibling(cwd.getFileName() + "_out")));
  }

  @Test
  @DisplayName("resolveTableDefinitionFile: DB名・スキーマ名・テーブル名のパスの区切りは置き換える")
  void testResolveTableDefinitionFileEncodesNames() {
    final OutputRoot hostileRoot =
        new OutputRoot(baseDir, new BaseInfoEntity("..", Dbms.POSTGRESQL, 16, LocalDate.EPOCH));
    Path result =
        resolver.resolveTableDefinitionFile(hostileRoot, table("/tmp/evil", "../x", "table"));
    assertEquals(Path.of("output", "~2E~2E", "~2Ftmp~2Fevil", "table", "..~2Fx.md"), result);
  }

  @Test
  @DisplayName("resolveTableDefinitionFile: .だけからなるテーブル名・スキーマ名も、置き換えて出力先の配下に置く")
  void testResolveTableDefinitionFileWithDotOnlyNames() {
    assertEquals(
        Path.of("output", "testdb", "~2E~2E", "table", "~2E~2E~2E.md"),
        resolver.resolveTableDefinitionFile(root, table("..", "...", "table")));
    assertEquals(
        Path.of("output", "testdb", "~2E", "table", "~2E~2E.md"),
        resolver.resolveTableDefinitionFile(root, table(".", "..", "table")));
  }

  @Test
  @DisplayName("resolveDatabaseDirectory: 出力先がカレントディレクトリ（.）でも解決できる")
  void testResolveDatabaseDirectoryUnderCurrentDirectory() {
    final OutputRoot currentRoot = new OutputRoot(Path.of("."), baseInfo);
    assertEquals(Path.of(".", "testdb"), resolver.resolveDatabaseDirectory(currentRoot));
  }

  @Test
  @DisplayName("resolveDatabaseDirectory: {base}/{DB名}")
  void testResolveDatabaseDirectory() {
    Path result = resolver.resolveDatabaseDirectory(root);
    assertEquals(Path.of("output", "testdb"), result);
  }

  @Test
  @DisplayName("resolveTableDefinitionDirectory: {base}/{DB名}/{スキーマ名}/{テーブル種別}")
  void testResolveTableDefinitionDirectory() {
    Path result =
        resolver.resolveTableDefinitionDirectory(root, table("public", "orders", "table"));
    assertEquals(Path.of("output", "testdb", "public", "table"), result);
  }

  @Test
  @DisplayName("resolveTableDefinitionFile: ディレクトリ配下に{物理テーブル名}.md")
  void testResolveTableDefinitionFile() {
    Path result = resolver.resolveTableDefinitionFile(root, table("public", "orders", "view"));
    assertEquals(Path.of("output", "testdb", "public", "view", "orders.md"), result);
  }

  @Test
  @DisplayName("resolveListFile: テーブル一覧は{base}/{DB名}/tableList_{DB名}.md")
  void testResolveListFileForTable() {
    Path result = resolver.resolveListFile(root, ListDocumentType.TABLE);
    assertEquals(Path.of("output", "testdb", "tableList_testdb.md"), result);
  }

  @Test
  @DisplayName("resolveListFile: オブジェクト一覧・ER図一覧は{base}/{DB名}/{接頭辞}List_{DB名}.md")
  void testResolveListFileForObjects() {
    assertEquals(
        Path.of("output", "testdb", "triggerList_testdb.md"),
        resolver.resolveListFile(root, ListDocumentType.TRIGGER));
    assertEquals(
        Path.of("output", "testdb", "erDiagramList_testdb.md"),
        resolver.resolveListFile(root, ListDocumentType.ER_DIAGRAM));
  }

  @Test
  @DisplayName("resolveErDiagramFile: {base}/{DB名}/erDiagram_{DB名}_{スキーマ名}.md")
  void testResolveErDiagramFile() {
    Path result = resolver.resolveErDiagramFile(root, "public");
    assertEquals(Path.of("output", "testdb", "erDiagram_testdb_public.md"), result);
  }

  @Test
  @DisplayName("resolveViewpointFile: {base}/{DB名}/viewpoint_{DB名}_{観点の識別子}.md")
  void testResolveViewpointFile() {
    Path result =
        resolver.resolveViewpointFile(root, Viewpoint.of("order", "受注管理", "", List.of("orders")));
    assertEquals(Path.of("output", "testdb", "viewpoint_testdb_order.md"), result);
  }

  @Test
  @DisplayName("resolveErDiagramGroupFile: {base}/{DB名}/erDiagram_{DB名}_{スキーマ名}_group{グループ番号}.md")
  void testResolveErDiagramGroupFile() {
    Path result = resolver.resolveErDiagramGroupFile(root, "public", 1);
    assertEquals(Path.of("output", "testdb", "erDiagram_testdb_public_group1.md"), result);
  }

  @Test
  @DisplayName("resolveReadmeFile: {base}/{DB名}/README.md")
  void testResolveReadmeFile() {
    Path result = resolver.resolveReadmeFile(root);
    assertEquals(Path.of("output", "testdb", "README.md"), result);
  }

  @Test
  @DisplayName("resolvePageFile: 本体ページと同じディレクトリに、拡張子の前へ_{ページ番号}を付けたファイル")
  void testResolvePageFile() {
    assertEquals(
        Path.of("output", "testdb", "tableList_testdb_2.md"),
        resolver.resolvePageFile(Path.of("output", "testdb", "tableList_testdb.md"), 2));
    assertEquals(
        Path.of("output", "testdb", "functionList_testdb_3.md"),
        resolver.resolvePageFile(resolver.resolveListFile(root, ListDocumentType.FUNCTION), 3));
    assertEquals(
        Path.of("output", "testdb", "erDiagram_testdb_public_4.md"),
        resolver.resolvePageFile(resolver.resolveErDiagramFile(root, "public"), 4));
    assertEquals(
        Path.of("output", "testdb", "erDiagram_testdb_public_group1_2.md"),
        resolver.resolvePageFile(resolver.resolveErDiagramGroupFile(root, "public", 1), 2));
  }

  @Test
  @DisplayName("resolveSchemaObjectDirectory: {base}/{DB名}/{スキーマ名}/{種別}")
  void testResolveSchemaObjectDirectory() {
    Path result = resolver.resolveSchemaObjectDirectory(root, "public", ListDocumentType.FUNCTION);
    assertEquals(Path.of("output", "testdb", "public", "function"), result);
  }

  @Test
  @DisplayName("resolveSchemaObjectFile: ディレクトリ配下に{名前}.md")
  void testResolveSchemaObjectFile() {
    Path result =
        resolver.resolveSchemaObjectFile(root, "public", ListDocumentType.SEQUENCE, "seq1");
    assertEquals(Path.of("output", "testdb", "public", "sequence", "seq1.md"), result);
  }

  @Test
  @DisplayName("resolveSnapshotDirectory: {base}/snapshot")
  void testResolveSnapshotDirectory() {
    assertEquals(Path.of("output", "snapshot"), resolver.resolveSnapshotDirectory(baseDir));
  }

  @Test
  @DisplayName("resolveSnapshotDatabaseFile: {base}/snapshot/{DB名}/database.json")
  void testResolveSnapshotDatabaseFile() {
    assertEquals(
        Path.of("output", "snapshot", "testdb", "database.json"),
        resolver.resolveSnapshotDatabaseFile(root));
  }

  @Test
  @DisplayName("resolveSnapshotFile: {base}/snapshot/{DB名}/{スキーマ名}/{種別のファイル名}.jsonl")
  void testResolveSnapshotFile() {
    assertEquals(
        Path.of("output", "snapshot", "testdb", "public", "tables.jsonl"),
        resolver.resolveSnapshotFile(root, "public", SnapshotKind.TABLE));
    assertEquals(
        Path.of("output", "snapshot", "testdb", "public", "functions.jsonl"),
        resolver.resolveSnapshotFile(root, "public", SnapshotKind.FUNCTION));
  }

  @Test
  @DisplayName("resolveSnapshotKind: resolveSnapshotFileで解決したファイルから種別を判定し、それ以外は空を返す")
  void testResolveSnapshotKind() {
    for (SnapshotKind kind : SnapshotKind.values()) {
      assertEquals(
          Optional.of(kind),
          resolver.resolveSnapshotKind(resolver.resolveSnapshotFile(root, "public", kind)));
    }
    assertEquals(
        Optional.empty(), resolver.resolveSnapshotKind(resolver.resolveSnapshotDatabaseFile(root)));
    assertEquals(Optional.empty(), resolver.resolveSnapshotKind(Path.of("README.md")));
  }

  @Test
  @DisplayName("スキーマ名・テーブル種別が異なれば別ディレクトリになる（衝突しない）")
  void testDifferentSchemasProduceDifferentDirectories() {
    Path publicDir =
        resolver.resolveTableDefinitionDirectory(root, table("public", "orders", "table"));
    Path salesDir =
        resolver.resolveTableDefinitionDirectory(root, table("sales", "orders", "table"));
    assertNotEquals(publicDir, salesDir);
  }
}
