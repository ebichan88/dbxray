package com.dbxray.domain.service.path;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DocumentLocations のファイル名・相対パス・相対リンクの組み立てに関するテスト */
public class DocumentLocationsTest {

  @Test
  @DisplayName("listFile: {接頭辞}List_{DB名}.md")
  void testListFile() {
    assertEquals(
        "tableList_testdb.md", DocumentLocations.listFile(ListDocumentType.TABLE, "testdb"));
    assertEquals(
        "erDiagramList_testdb.md",
        DocumentLocations.listFile(ListDocumentType.ER_DIAGRAM, "testdb"));
  }

  @Test
  @DisplayName("erDiagramFile/erDiagramGroupFile: スキーマ別・グループ別ER図のファイル名")
  void testErDiagramFiles() {
    assertEquals("erDiagram_testdb_public.md", DocumentLocations.erDiagramFile("testdb", "public"));
    assertEquals(
        "erDiagram_testdb_public_group2.md",
        DocumentLocations.erDiagramGroupFile("testdb", "public", 2));
  }

  @Test
  @DisplayName("ファイル名・相対パス・リンクに使う名前は、パスの区切り等を置き換える")
  void testNamesAreEncoded() {
    assertEquals("tableList_~2E~2E.md", DocumentLocations.listFile(ListDocumentType.TABLE, ".."));
    assertEquals("erDiagram_db_a~2Fb.md", DocumentLocations.erDiagramFile("db", "a/b"));
    var table = new TableEntity("db", "..", "", "../../x", TableType.TABLE, "");
    assertEquals("~2E~2E/table/..~2F..~2Fx.md", DocumentLocations.tableDefinitionFile(table));
    assertEquals(
        "~2E~2E/function/a~5Cb.md",
        DocumentLocations.schemaObjectFile("..", ListDocumentType.FUNCTION, "a\\b"));
    assertEquals("~2E~2E", DocumentLocations.databaseDirectory(".."));
  }

  @Test
  @DisplayName("pageFile: 拡張子の前に_{ページ番号}を付ける")
  void testPageFile() {
    assertEquals("tableList_testdb_3.md", DocumentLocations.pageFile("tableList_testdb.md", 3));
  }

  @Test
  @DisplayName("pageFile: Markdownの拡張子で終わらないファイル名はIllegalArgumentExceptionをスローする")
  void testPageFileRejectsNonMarkdown() {
    assertThrows(
        IllegalArgumentException.class, () -> DocumentLocations.pageFile("tables.jsonl", 1));
  }

  @Test
  @DisplayName("tableDefinitionFile/schemaObjectFile: {スキーマ名}/{区分}/{名前}.md")
  void testDefinitionFiles() {
    var table = new TableEntity("testdb", "public", "", "orders", TableType.VIEW, "");
    assertEquals("public/view/orders.md", DocumentLocations.tableDefinitionFile(table));
    assertEquals(
        "public/function",
        DocumentLocations.schemaObjectDirectory("public", ListDocumentType.FUNCTION));
    assertEquals(
        "public/type/status.md",
        DocumentLocations.schemaObjectFile("public", ListDocumentType.TYPE, "status"));
  }

  @Test
  @DisplayName("functionDefinitionName: オーバーロードが無い関数は関数名をそのまま用いる")
  void testFunctionDefinitionNameWithoutOverload() {
    var function =
        new FunctionEntity("testdb", "public", "calc", 1, 1, "FUNCTION", "", "int", "sql", "");
    assertEquals("calc", DocumentLocations.functionDefinitionName(function));
  }

  @Test
  @DisplayName("functionDefinitionName: オーバーロードされた関数は作成順の番号を付ける")
  void testFunctionDefinitionNameWithOverload() {
    var first =
        new FunctionEntity("testdb", "public", "calc", 1, 2, "FUNCTION", "a int", "int", "sql", "");
    var second =
        new FunctionEntity(
            "testdb", "public", "calc", 2, 2, "FUNCTION", "a text", "int", "sql", "");
    assertEquals("calc_1", DocumentLocations.functionDefinitionName(first));
    assertEquals("calc_2", DocumentLocations.functionDefinitionName(second));
  }

  @Test
  @DisplayName("functionsSharingDefinitionFile: オーバーロードに付けた番号が別の関数の名前と重なる場合は、同じファイルを書く関数を取得順に返す")
  void testFunctionsSharingDefinitionFile() {
    var calc1 = function("public", "calc", 1, 2, "a integer");
    var calc2 = function("public", "calc", 2, 2, "a text");
    var calcOne = function("public", "calc_1", 1, 1, "");
    // 別のスキーマの同じ名前は、ファイルが別のため重ならない
    var otherSchema = function("sales", "calc_1", 1, 1, "");

    assertEquals(
        Map.of("public/function/calc_1.md", List.of(calc1, calcOne)),
        DocumentLocations.functionsSharingDefinitionFile(
            Functions.of(List.of(calc1, calc2, calcOne, otherSchema))));
  }

  @Test
  @DisplayName("functionsSharingDefinitionFile: オーバーロードがあってもファイルが重ならなければ空を返す")
  void testFunctionsSharingDefinitionFileWithoutCollision() {
    assertEquals(
        Map.of(),
        DocumentLocations.functionsSharingDefinitionFile(
            Functions.of(
                List.of(
                    function("public", "calc", 1, 2, "a integer"),
                    function("public", "calc", 2, 2, "a text"),
                    function("public", "calc_v2", 1, 1, "")))));
  }

  private static FunctionEntity function(
      String schema, String name, int overloadIndex, int overloadCount, String arguments) {
    return new FunctionEntity(
        "testdb",
        schema,
        name,
        overloadIndex,
        overloadCount,
        "FUNCTION",
        arguments,
        "int",
        "sql",
        "");
  }

  @Test
  @DisplayName("linkFromDatabaseRoot/linkFromDefinition: 参照元の配置に応じた相対リンク")
  void testLinks() {
    assertEquals(
        "./tableList_testdb.md", DocumentLocations.linkFromDatabaseRoot("tableList_testdb.md"));
    assertEquals(
        "../../tableList_testdb.md", DocumentLocations.linkFromDefinition("tableList_testdb.md"));
  }

  @Test
  @DisplayName("readmeFile: README.md")
  void testReadmeFile() {
    assertEquals("README.md", DocumentLocations.readmeFile());
  }

  @Test
  @DisplayName("viewpointFile: 観点ページのファイル名には、表示名ではなく識別子を用いる")
  void testViewpointFile() {
    assertEquals(
        "viewpoint_testdb_order.md",
        DocumentLocations.viewpointFile(
            "testdb", Viewpoint.of("order", "受注 管理", "", List.of("orders"))));
  }
}
