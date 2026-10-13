package com.dbxray.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TestCatalogs;
import com.dbxray.mcp.catalog.TestTables;
import com.dbxray.mcp.catalog.ViewpointEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code find_similar_tables}のテスト */
class SimilarTableToolsTest {

  private static final String[] ORDER_DETAIL = {"order_id", "item_no", "qty", "unit_price"};

  /**
   * order_detailと、カラムが同じだが数量の型が異なるorder_detail_bk、追加のカラムを持つorder_detail_hist、
   * カラムは同じだがorder_detailと外部キーでつながるorder_detail_note
   */
  private final TableDefinitionTools tools =
      new TableDefinitionTools(
          TestCatalogs.of(
              List.of(
                  table("order_detail", ORDER_DETAIL),
                  orderDetailWithQtyType("order_detail_bk", "numeric(10,2)"),
                  table(
                      "order_detail_hist",
                      "order_id",
                      "item_no",
                      "qty",
                      "unit_price",
                      "valid_from"),
                  TestTables.table("order_detail_note")
                      .foreignKey("order_id", "order_detail", "order_id")
                      .columns(ORDER_DETAIL)
                      .build())));

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static TableEntry table(String name, String... columns) {
    return TestTables.table(name).columns(columns).build();
  }

  private static TableEntry orderDetailWithQtyType(String name, String qtyType) {
    final TestTables table = TestTables.table(name);
    for (final String column : ORDER_DETAIL) {
      final String type = column.equals("qty") ? qtyType : "integer";
      table.column(new ColumnEntry(column, null, type, false, false, null, null));
    }
    return table.build();
  }

  @Test
  @DisplayName("tableに既存のテーブルを指定すると、基準と総数、似ているテーブルの類似度・種別・差分・型の違いを返し、関連でつながるテーブルには印を付ける")
  void findsSimilarTablesOfTable() throws Exception {
    final JsonNode result =
        json(call("find_similar_tables", Map.of("table", "order_detail", "minSimilarity", 30)));

    assertEquals("sample.order_detail", result.get("base").asText());
    assertEquals(3, result.get("total").asInt());
    assertEquals(
        List.of("sample.order_detail_bk", "sample.order_detail_note", "sample.order_detail_hist"),
        result.get("tables").findValuesAsText("name"));
    assertEquals(
        objectMapper.readTree(
            "{\"database\":\"testdb\",\"name\":\"sample.order_detail_bk\",\"similarity\":100,"
                + "\"kind\":\"identical\",\"sharedColumns\":4,"
                + "\"typeMismatches\":[{\"column\":\"qty\",\"base\":\"integer\","
                + "\"other\":\"numeric(10,2)\",\"difference\":\"type\"}]}"),
        result.get("tables").get(0));
    assertTrue(result.get("tables").get(1).get("relatedToBase").asBoolean());
    final JsonNode hist = result.get("tables").get(2);
    assertEquals("superset", hist.get("kind").asText());
    assertEquals(4, hist.get("sharedColumns").asInt());
    assertEquals(objectMapper.readTree("[\"valid_from\"]"), hist.get("onlyInOther"));
    assertFalse(hist.has("onlyInBase"));
    assertFalse(hist.has("typeMismatches"));
    assertFalse(hist.has("relatedToBase"));
  }

  @Test
  @DisplayName("columnsには配列でもカンマ区切りの文字列でも設計中のカラム名を指定でき、基準は返さず、件数の上限で切り捨てても総数は切り捨て前の数を返す")
  void findsSimilarTablesOfColumns() throws Exception {
    final JsonNode byArray =
        json(
            call(
                "find_similar_tables",
                Map.of(
                    "columns", List.of("order_id", "item_no", "qty", "unit_price"), "limit", 1)));
    final JsonNode byString =
        json(
            call(
                "find_similar_tables",
                Map.of("columns", "order_id, item_no, qty, unit_price", "limit", 1)));

    assertEquals(byArray, byString);
    assertFalse(byArray.has("base"));
    assertEquals(3, byArray.get("total").asInt(), "カラムが同じ3テーブル。追加のカラムを持つhistは類似度の下限（50）に満たない");
    assertEquals(1, byArray.get("tables").size());
    assertEquals("sample.order_detail", byArray.get("tables").get(0).get("name").asText());
    assertFalse(byArray.get("tables").get(0).has("typeMismatches"), "基準の型が分からないため型の違いは返さない");
  }

  @Test
  @DisplayName("条件を満たすテーブルが無い場合は、条件をゆるめる方法を示すmessageを返す")
  void explainsWhenNothingMatches() throws Exception {
    final JsonNode result =
        json(
            call(
                "find_similar_tables",
                Map.of("columns", List.of("no_such_a", "no_such_b", "no_such_c"))));

    assertEquals(0, result.get("total").asInt());
    assertFalse(result.has("tables"));
    assertTrue(result.get("message").asText().contains("minSimilarity"));
  }

  @Test
  @DisplayName("基準にだけ・相手にだけあるカラムは20件まで返し、省いた数をOmittedで示す")
  void omitsColumnsOverLimit() throws Exception {
    final TableDefinitionTools wideTools =
        new TableDefinitionTools(
            TestCatalogs.of(List.of(table("narrow", columns(12)), table("wide", columns(33)))));

    final JsonNode byTable =
        json(
            call(wideTools, "find_similar_tables", Map.of("table", "narrow", "minSimilarity", 10)));
    final JsonNode wide = byTable.get("tables").get(0);
    assertEquals("sample.wide", wide.get("name").asText());
    assertEquals(20, wide.get("onlyInOther").size());
    assertEquals(1, wide.get("onlyInOtherOmitted").asInt());
    assertFalse(wide.has("onlyInBase"));
    assertFalse(wide.has("onlyInBaseOmitted"));

    final JsonNode byColumns =
        json(
            call(
                wideTools,
                "find_similar_tables",
                Map.of("columns", List.of(columns(40)), "minSimilarity", 10)));
    final JsonNode narrow = byColumns.get("tables").get(1);
    assertEquals("sample.narrow", narrow.get("name").asText());
    assertEquals(20, narrow.get("onlyInBase").size());
    assertEquals(8, narrow.get("onlyInBaseOmitted").asInt());
  }

  @Test
  @DisplayName("tableとcolumnsは、どちらか一方を指定する。両方・どちらも無い場合はエラーを返す")
  void requiresExactlyOneOfTableAndColumns() {
    final List<Map<String, Object>> invalid =
        List.of(Map.of(), Map.of("table", "order_detail", "columns", List.of("order_id")));

    for (final Map<String, Object> arguments : invalid) {
      final CallToolResult result = call("find_similar_tables", arguments);

      assertTrue(result.isError());
      assertEquals("引数tableとcolumnsは、どちらか一方を指定してください。", text(result));
    }
  }

  @Test
  @DisplayName("存在しないテーブルを指定した場合は、名前の似たテーブルを示すエラーを返す")
  void rejectsUnknownTable() {
    final CallToolResult result = call("find_similar_tables", Map.of("table", "order_detai"));

    assertTrue(result.isError());
    assertTrue(text(result).contains("見つかりません"), text(result));
    assertTrue(text(result).contains("sample.order_detail"), text(result));
  }

  @Test
  @DisplayName("columnsが空の場合は、エラーを返す")
  void rejectsEmptyColumns() {
    final CallToolResult result = call("find_similar_tables", Map.of("columns", List.of()));

    assertTrue(result.isError());
    assertTrue(text(result).contains("columns"), text(result));
  }

  @Test
  @DisplayName("tableを指定した場合、schemaは探す範囲の絞り込みに使い、基準はスキーマ名.テーブル名で特定する")
  void narrowsBySchemaWithQualifiedBase() throws Exception {
    final TableDefinitionTools schemaTools =
        new TableDefinitionTools(
            TestCatalogs.of(
                List.of(
                    TestTables.table("testdb", "sales", "order_detail")
                        .columns(ORDER_DETAIL)
                        .build(),
                    TestTables.table("testdb", "archive", "order_detail")
                        .columns(ORDER_DETAIL)
                        .build(),
                    TestTables.table("testdb", "sales", "order_detail_bk")
                        .columns(ORDER_DETAIL)
                        .build())));

    final JsonNode result =
        json(
            call(
                schemaTools,
                "find_similar_tables",
                Map.of("table", "sales.order_detail", "schema", "archive")));

    assertEquals("sales.order_detail", result.get("base").asText());
    assertEquals(List.of("archive.order_detail"), result.get("tables").findValuesAsText("name"));

    final CallToolResult ambiguous =
        call(
            schemaTools, "find_similar_tables", Map.of("table", "order_detail", "schema", "sales"));
    assertTrue(ambiguous.isError());
    assertTrue(
        text(ambiguous).contains("tableをスキーマ名.テーブル名の形（DBが異なる場合はdatabaseも）で指定してください。"),
        text(ambiguous));
  }

  @Test
  @DisplayName("viewpointを指定すると、観点の所属テーブルだけから探す")
  void narrowsByViewpoint() throws Exception {
    final TableDefinitionTools viewpointTools =
        new TableDefinitionTools(
            TestCatalogs.of(
                    List.of(
                        table("order_detail", ORDER_DETAIL),
                        table("order_detail_bk", ORDER_DETAIL),
                        table("order_detail_old", ORDER_DETAIL)))
                .withViewpoints(
                    List.of(
                        new ViewpointEntry(
                            "testdb",
                            "backup",
                            "バックアップ",
                            null,
                            List.of(new ObjectKey("testdb", "sample", "order_detail_old"))))));

    final JsonNode result =
        json(
            call(
                viewpointTools,
                "find_similar_tables",
                Map.of("table", "order_detail", "viewpoint", "backup")));

    assertEquals(List.of("sample.order_detail_old"), result.get("tables").findValuesAsText("name"));
  }

  @Test
  @DisplayName("基準のカラムがminSharedより少ない場合は、minSharedを下げる値を示すmessageを返す")
  void explainsWhenColumnsAreFewerThanMinShared() throws Exception {
    final JsonNode result =
        json(
            call("find_similar_tables", Map.of("columns", List.of("order_id", "ORDER_ID", "qty"))));

    assertEquals(0, result.get("total").asInt());
    assertEquals(
        "基準のカラムは2個で、minShared（3）に満たないため、条件を満たすテーブルはありません。minSharedを2以下にしてください。",
        result.get("message").asText());
  }

  @Test
  @DisplayName("基準のテーブルにカラムが無い場合は、比べられないことを示すmessageを返す")
  void explainsWhenBaseHasNoColumns() throws Exception {
    final TableDefinitionTools emptyTools =
        new TableDefinitionTools(TestCatalogs.of(List.of(table("empty"), table("other", "a"))));

    final JsonNode result = json(call(emptyTools, "find_similar_tables", Map.of("table", "empty")));

    assertEquals("基準のテーブルにカラムが無いため、比べられません。", result.get("message").asText());
  }

  /** c01〜cNN（NNは{@code count}）のカラム名 */
  private static String[] columns(int count) {
    return IntStream.rangeClosed(1, count).mapToObj("c%02d"::formatted).toArray(String[]::new);
  }

  private CallToolResult call(String name, Map<String, Object> arguments) {
    return call(tools, name, arguments);
  }

  private static CallToolResult call(
      TableDefinitionTools target, String name, Map<String, Object> arguments) {
    return target.specifications().stream()
        .filter(specification -> specification.tool().name().equals(name))
        .findFirst()
        .orElseThrow()
        .callHandler()
        .apply(null, CallToolRequest.builder(name).arguments(arguments).build());
  }

  private JsonNode json(CallToolResult result) throws Exception {
    assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
    return objectMapper.readTree(text(result));
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
