package com.dbxray.mcp.tool;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.DatabaseEntry;
import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TestTables;
import com.dbxray.mcp.catalog.TypeEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code get_impact}のテスト */
class ImpactToolsTest {

  private static final String HASH = "h";
  private static final String ZERO_COVERAGE =
      "{\"total\":0,\"analyzed\":0,\"unsupported\":0,\"noInsight\":0,\"stale\":0,"
          + "\"withDynamicSql\":0,\"incomplete\":0,\"triggersWithoutFunction\":0}";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("影響を受けるもの（参照元・ビュー・トリガー・関数）と、依存するもの（シーケンス・型）を、キーで分けて返す")
  void returnsAffectedAndDependsOn() throws Exception {
    final List<FunctionEntry> functions =
        List.of(
            function("PROCEDURE", "close_project", "IN p integer"),
            function("FUNCTION", "count_rows", ""),
            function("FUNCTION", "view_reader", ""));
    final List<FunctionTableUsageEntry> usages =
        List.of(
            usage(functions.get(0), List.of(), used("sample", "employee", "U")),
            usage(
                functions.get(1),
                List.of("EXECUTE"),
                new UsedTable("", "employee", "search_path", List.of("sample"), List.of("R"))),
            usage(functions.get(2), List.of(), used("sample", "v_emp", "R")));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee")
                    .column(
                        column(
                            "employee_id",
                            "integer",
                            "nextval('sample.employee_id_seq'::regclass)"))
                    .column(column("status", "sample.status_enum", ""))
                    .trigger("trg_audit", "sample.log_change")
                    .build(),
                table("attendance").foreignKey("employee_id", "employee", "employee_id").build(),
                table("attendance_note")
                    .foreignKey("attendance_id", "attendance", "attendance_id")
                    .build(),
                table("v_emp")
                    .type("view")
                    .referencedTable("employee")
                    .trigger("trg_v_emp_insert", "sample.view_reader")
                    .build()),
            functions,
            usages,
            List.of(new TypeEntry(new ObjectKey("testdb", "sample", "status_enum"), "ENUM", "{}")));

    final JsonNode result = json(call(catalog, Map.of("table", "employee")));

    assertEquals("sample.employee", result.get("table").asText());
    assertEquals("testdb", result.get("database").asText());
    assertEquals("table", result.get("type").asText());
    assertEquals(
        objectMapper.readTree(
            "{\"referencedBy\":{"
                + "\"direct\":[{\"table\":\"sample.attendance\",\"kind\":\"foreignKey\","
                + "\"columns\":[\"employee_id\"],\"referencedColumns\":[\"employee_id\"],"
                + "\"cardinality\":\"ONE_TO_MANY\"}],"
                + "\"indirect\":[{\"table\":\"sample.attendance_note\",\"depth\":2,"
                + "\"via\":\"sample.attendance\"}],\"indirectTotal\":1},"
                + "\"views\":[{\"view\":\"sample.v_emp\",\"type\":\"view\"}],"
                + "\"triggers\":[{\"name\":\"trg_audit\",\"timing\":\"AFTER\","
                + "\"events\":[\"INSERT\"],\"function\":\"sample.log_change\"}],"
                + "\"functions\":{\"preview\":true,"
                + "\"coverage\":{\"total\":3,\"analyzed\":3,\"unsupported\":0,\"noInsight\":0,"
                + "\"stale\":0,\"withDynamicSql\":1,\"incomplete\":0,\"triggersWithoutFunction\":0},"
                + "\"usages\":["
                + "{\"function\":\"sample.close_project\",\"kind\":\"PROCEDURE\","
                + "\"overloads\":[\"IN p integer\"],\"operations\":\"U\"},"
                + "{\"function\":\"sample.count_rows\",\"kind\":\"FUNCTION\",\"overloads\":[\"()\"],"
                + "\"operations\":\"R\",\"match\":\"possible\",\"schemaResolution\":\"search_path\","
                + "\"dynamicSql\":[\"EXECUTE\"]},"
                + "{\"function\":\"sample.view_reader\",\"kind\":\"FUNCTION\",\"overloads\":[\"()\"],"
                + "\"operations\":\"R\",\"via\":\"sample.v_emp\","
                + "\"calledByTriggers\":[\"sample.v_emp.trg_v_emp_insert\"]}]}}"),
        result.get("affected"));
    assertEquals(
        objectMapper.readTree(
            "{\"sequences\":[{\"sequence\":\"sample.employee_id_seq\",\"columns\":[\"employee_id\"]}],"
                + "\"types\":[{\"type\":\"sample.status_enum\",\"category\":\"ENUM\","
                + "\"columns\":[\"status\"]}]}"),
        result.get("dependsOn"));
    assertEquals(3, result.get("notes").size());
    assertTrue(result.get("notes").get(0).asText().contains("--preview"));
    assertTrue(result.get("notes").get(2).asText().contains("dependsOn"));
  }

  @Test
  @DisplayName("関連もビューも関数も依存先も無いテーブルは、値の無い項目を出力せず、参照元の総数と関数の範囲だけを返す")
  void omitsEmptySections() throws Exception {
    final ObjectNode result =
        (ObjectNode) json(call(catalogOf(List.of(table("memo").build())), Map.of("table", "memo")));

    assertEquals(2, result.get("notes").size(), "関数が1つも無いDBでは、functionsの参考値の注意を出さない");
    assertFalse(result.get("notes").toString().contains("functionsは"));
    result.remove("notes");
    assertEquals(
        objectMapper.readTree(
            "{\"table\":\"sample.memo\",\"database\":\"testdb\",\"type\":\"table\","
                + "\"affected\":{\"referencedBy\":{\"indirectTotal\":0},"
                + "\"functions\":{\"preview\":true,\"coverage\":"
                + ZERO_COVERAGE
                + "}}}"),
        result);
  }

  @Test
  @DisplayName("参考情報が無い・古い関数があればその対応を、関数名を持たないトリガーがあればOracleの注意を、notesに加える")
  void addsNotesForCoverage() throws Exception {
    final List<FunctionEntry> functions =
        List.of(function("FUNCTION", "no_insight", ""), function("FUNCTION", "stale", ""));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(table("employee").trigger("trg_body_only", "").build()),
            functions,
            List.of(
                new FunctionTableUsageEntry(
                    functions.get(1).key(),
                    "",
                    "old",
                    FunctionTableUsageEntry.ANALYZED,
                    "",
                    List.of(),
                    List.of(),
                    false,
                    false)),
            List.of());

    final JsonNode result = json(call(catalog, Map.of("table", "employee")));

    assertEquals(
        objectMapper.readTree(
            "{\"total\":2,\"analyzed\":0,\"unsupported\":0,\"noInsight\":1,\"stale\":1,"
                + "\"withDynamicSql\":0,\"incomplete\":0,\"triggersWithoutFunction\":1}"),
        result.get("affected").get("functions").get("coverage"));
    assertEquals(5, result.get("notes").size());
    assertTrue(result.get("notes").get(1).asText().contains("noInsight"));
    assertTrue(result.get("notes").get(1).asText().contains("--preview"));
    assertTrue(result.get("notes").get(2).asText().contains("1件"));
    assertTrue(result.get("notes").get(2).asText().contains("Oracle"));
  }

  @Test
  @DisplayName("各リストは50件までを決まった順で返し、超えた場合は総数を示す。間接参照の総数は常に示す")
  void limitsLists() throws Exception {
    final int count = 51;
    final List<TableEntry> tables = new ArrayList<>();
    final TestTables master = table("master");
    IntStream.rangeClosed(1, count)
        .forEach(number -> master.trigger("trg_%02d".formatted(number), "sample.fn"));
    tables.add(master.build());
    IntStream.rangeClosed(1, 60)
        .forEach(
            number ->
                tables.add(
                    table("child_%02d".formatted(number))
                        .foreignKey("master_id", "master", "master_id")
                        .build()));
    IntStream.rangeClosed(1, 55)
        .forEach(
            number ->
                tables.add(
                    table("grand_%02d".formatted(number))
                        .foreignKey("child_id", "child_01", "child_id")
                        .build()));
    IntStream.rangeClosed(1, count)
        .forEach(
            number ->
                tables.add(
                    table("view_%02d".formatted(number))
                        .type("view")
                        .referencedTable("master")
                        .build()));
    final List<FunctionEntry> functions =
        IntStream.rangeClosed(1, count)
            .mapToObj(number -> function("FUNCTION", "fn_%02d".formatted(number), ""))
            .toList();
    final List<FunctionTableUsageEntry> usages =
        functions.stream()
            .map(function -> usage(function, List.of(), used("sample", "master", "R")))
            .toList();

    final SchemaCatalog catalog = catalogOf(tables, functions, usages, List.of());
    final JsonNode result = json(call(catalog, Map.of("table", "master")));
    final JsonNode affected = result.get("affected");

    final JsonNode referencedBy = affected.get("referencedBy");
    assertEquals(50, referencedBy.get("direct").size());
    assertEquals(60, referencedBy.get("directTotal").asInt());
    assertEquals("sample.child_01", referencedBy.get("direct").get(0).get("table").asText());
    assertEquals("sample.child_50", referencedBy.get("direct").get(49).get("table").asText());
    assertEquals(50, referencedBy.get("indirect").size());
    assertEquals(55, referencedBy.get("indirectTotal").asInt());
    assertEquals(50, affected.get("views").size());
    assertEquals(count, affected.get("viewsTotal").asInt());
    assertEquals(50, affected.get("triggers").size());
    assertEquals(count, affected.get("triggersTotal").asInt());
    assertEquals(50, affected.get("functions").get("usages").size());
    assertEquals(count, affected.get("functions").get("usagesTotal").asInt());
    assertEquals(
        "sample.fn_01", affected.get("functions").get("usages").get(0).get("function").asText());
    assertEquals(
        objectMapper.readTree(
            "[{\"section\":\"directReferences\",\"total\":60,\"nextOffset\":50},"
                + "{\"section\":\"indirectReferences\",\"total\":55,\"nextOffset\":50},"
                + "{\"section\":\"views\",\"total\":51,\"nextOffset\":50},"
                + "{\"section\":\"triggers\",\"total\":51,\"nextOffset\":50},"
                + "{\"section\":\"functions\",\"total\":51,\"nextOffset\":50}]"),
        result.get("more"),
        "切り捨てたリストと続きの取り方を、概要に並ぶ順に示す");
    assertTrue(result.get("notes").toString().contains("section"), "続きの取り方をnotesでも示す");

    final JsonNode secondPage =
        json(call(catalog, Map.of("table", "master", "section", "directReferences", "offset", 50)));
    assertEquals(60, secondPage.get("total").asInt());
    assertEquals(10, secondPage.get("items").size());
    assertEquals("sample.child_51", secondPage.get("items").get(0).get("table").asText());
    assertFalse(secondPage.has("nextOffset"), "最後のページには、続きが無い");
  }

  private static SchemaCatalog pagingCatalog() {
    final List<TableEntry> tables = new ArrayList<>();
    final TestTables master = table("master");
    IntStream.rangeClosed(1, 7)
        .forEach(number -> master.trigger("trg_%d".formatted(number), "sample.fn"));
    tables.add(master.build());
    IntStream.rangeClosed(1, 7)
        .forEach(
            number ->
                tables.add(
                    table("child_%d".formatted(number))
                        .foreignKey("master_id", "master", "master_id")
                        .build()));
    IntStream.rangeClosed(1, 7)
        .forEach(
            number ->
                tables.add(
                    table("grand_%d".formatted(number))
                        .foreignKey("child_id", "child_1", "child_id")
                        .build()));
    IntStream.rangeClosed(1, 7)
        .forEach(
            number ->
                tables.add(
                    table("view_%d".formatted(number))
                        .type("view")
                        .referencedTable("master")
                        .build()));
    final List<FunctionEntry> functions = new ArrayList<>();
    final List<FunctionTableUsageEntry> usages = new ArrayList<>();
    IntStream.rangeClosed(1, 7)
        .forEach(
            number -> {
              final FunctionEntry used = function("FUNCTION", "used_%d".formatted(number), "");
              functions.add(used);
              usages.add(usage(used, List.of(), used("sample", "master", "R")));
              functions.add(function("FUNCTION", "no_insight_%d".formatted(number), ""));
            });
    return catalogOf(tables, functions, usages, List.of());
  }

  @Test
  @DisplayName("sectionを指定すると、6つのリストをそれぞれ1ページずつ返し、概要の各リストと同じ形・同じ並びの要素を、offsetとnextOffsetで順にたどれる")
  void pagesEachSection() throws Exception {
    final SchemaCatalog catalog = pagingCatalog();
    final JsonNode overview = json(call(catalog, Map.of("table", "master")));
    assertFalse(overview.has("more"), "どのリストも上限以内なら、続きは示さない");
    final Map<String, String> overviewPaths =
        Map.of(
            "directReferences", "/affected/referencedBy/direct",
            "indirectReferences", "/affected/referencedBy/indirect",
            "views", "/affected/views",
            "triggers", "/affected/triggers",
            "functions", "/affected/functions/usages",
            "unverifiedFunctions", "/affected/functions/unverified");

    for (final Map.Entry<String, String> entry : overviewPaths.entrySet()) {
      final String section = entry.getKey();
      final JsonNode all = overview.at(entry.getValue());
      assertEquals(7, all.size(), section);

      final JsonNode first =
          json(call(catalog, Map.of("table", "master", "section", section, "limit", 3)));
      assertEquals("sample.master", first.get("table").asText(), section);
      assertEquals("testdb", first.get("database").asText(), section);
      assertEquals(section, first.get("section").asText());
      assertEquals(7, first.get("total").asInt(), section);
      assertEquals(3, first.get("nextOffset").asInt(), section);
      assertEquals(3, first.get("items").size(), section);
      for (int i = 0; i < 3; i++) {
        assertEquals(all.get(i), first.get("items").get(i), section + "[" + i + "]");
      }

      final JsonNode second =
          json(
              call(
                  catalog, Map.of("table", "master", "section", section, "offset", 3, "limit", 3)));
      assertEquals(6, second.get("nextOffset").asInt(), section);
      assertEquals(all.get(3), second.get("items").get(0), section);

      final JsonNode last =
          json(
              call(
                  catalog, Map.of("table", "master", "section", section, "offset", 6, "limit", 3)));
      assertEquals(1, last.get("items").size(), section);
      assertEquals(all.get(6), last.get("items").get(0), section);
      assertFalse(last.has("nextOffset"), section + ": 最後のページには、続きが無い");
    }
  }

  @Test
  @DisplayName("sectionで総数を超えるoffsetを指定した場合は、総数だけを返し、要素もnextOffsetも返さない")
  void returnsOnlyTotalBeyondTheEnd() throws Exception {
    final JsonNode result =
        json(call(pagingCatalog(), Map.of("table", "master", "section", "views", "offset", 100)));

    assertEquals(7, result.get("total").asInt());
    assertFalse(result.has("items"));
    assertFalse(result.has("nextOffset"));
  }

  @Test
  @DisplayName("関数の確かめきれないオーバーロード（unverified）を、関数・引数・理由とともに返す")
  void returnsUnverifiedFunctions() throws Exception {
    final List<FunctionEntry> functions =
        List.of(function("FUNCTION", "dyn", ""), function("FUNCTION", "no_insight", "a integer"));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(table("employee").build()),
            functions,
            List.of(usage(functions.get(0), List.of("EXECUTE"))),
            List.of());

    final JsonNode functionsNode =
        json(call(catalog, Map.of("table", "employee"))).get("affected").get("functions");

    assertEquals(
        objectMapper.readTree(
            "[{\"function\":\"sample.dyn\",\"arguments\":\"()\",\"reason\":\"dynamicSql\"},"
                + "{\"function\":\"sample.no_insight\",\"arguments\":\"a integer\",\"reason\":\"noInsight\"}]"),
        functionsNode.get("unverified"));
    assertFalse(functionsNode.has("unverifiedTotal"));
  }

  @Test
  @DisplayName("offset・limitはsectionなしでは使えず、未知のsectionもエラーにして、直し方を示す")
  void rejectsInvalidPagingArguments() {
    final SchemaCatalog catalog = pagingCatalog();
    for (final Map<String, Object> arguments :
        List.of(
            Map.<String, Object>of("table", "master", "offset", 5),
            Map.<String, Object>of("table", "master", "limit", 10),
            Map.<String, Object>of("table", "master", "section", "nope"))) {
      final CallToolResult result = call(catalog, arguments);

      assertTrue(result.isError(), arguments.toString());
      assertTrue(text(result).contains("section"), text(result));
    }
    assertTrue(
        text(call(catalog, Map.of("table", "master", "offset", 5))).contains("directReferences"),
        "指定できるsectionを示す");
  }

  @Test
  @DisplayName("ビューを指定した場合は、区分とそのビューのトリガー・参照するビューを返す")
  void analyzesView() throws Exception {
    final JsonNode result =
        json(
            call(
                catalogOf(
                    List.of(
                        table("employee").build(),
                        table("v1")
                            .type("view")
                            .referencedTable("employee")
                            .trigger("trg_v1", "sample.view_insert")
                            .build(),
                        table("v2").type("materialized_view").referencedTable("v1").build())),
                Map.of("table", "v1")));

    assertEquals("view", result.get("type").asText());
    assertEquals(
        List.of("sample.v2"), result.get("affected").get("views").findValuesAsText("view"));
    assertEquals(
        "materialized_view", result.get("affected").get("views").get(0).get("type").asText());
    assertEquals(
        List.of("trg_v1"), result.get("affected").get("triggers").findValuesAsText("name"));
  }

  @Test
  @DisplayName("存在しないテーブルを指定した場合は、名前の似たテーブルを示すエラーを返す。tableが無い場合もエラー")
  void rejectsUnknownOrMissingTable() {
    final SchemaCatalog catalog = catalogOf(List.of(table("employee").build()));

    final CallToolResult unknown = call(catalog, Map.of("table", "employe"));
    assertTrue(unknown.isError());
    assertTrue(text(unknown).contains("見つかりません"), text(unknown));
    assertTrue(text(unknown).contains("sample.employee"), text(unknown));

    final CallToolResult missing = call(catalog, Map.of());
    assertTrue(missing.isError());
    assertTrue(text(missing).contains("table"), text(missing));
  }

  private static SchemaCatalog catalogOf(List<TableEntry> tables) {
    return catalogOf(tables, List.of(), List.of(), List.of());
  }

  private static SchemaCatalog catalogOf(
      List<TableEntry> tables,
      List<FunctionEntry> functions,
      List<FunctionTableUsageEntry> usages,
      List<TypeEntry> types) {
    return SchemaCatalog.of(
            List.of(new DatabaseEntry("testdb", "PostgreSQL", 16)),
            tables,
            functions,
            List.of(),
            types)
        .withFunctionTableUsages(usages);
  }

  private static ColumnEntry column(String name, String type, String defaultValue) {
    return new ColumnEntry(name, null, type, false, false, defaultValue, null);
  }

  private static FunctionEntry function(String kind, String name, String arguments) {
    return new FunctionEntry(
        new ObjectKey("testdb", "sample", name), kind, arguments, "void", "plpgsql", "{}", HASH);
  }

  private static FunctionTableUsageEntry usage(
      FunctionEntry function, List<String> dynamicSql, UsedTable... tables) {
    return new FunctionTableUsageEntry(
        function.key(),
        function.arguments(),
        HASH,
        FunctionTableUsageEntry.ANALYZED,
        "",
        List.of(tables),
        dynamicSql,
        false,
        false);
  }

  private static UsedTable used(String schema, String name, String operations) {
    return new UsedTable(
        schema,
        name,
        "",
        List.of(),
        operations.chars().mapToObj(operation -> String.valueOf((char) operation)).toList());
  }

  private static CallToolResult call(SchemaCatalog catalog, Map<String, Object> arguments) {
    return new TableDefinitionTools(catalog)
        .specifications().stream()
            .filter(specification -> specification.tool().name().equals("get_impact"))
            .findFirst()
            .orElseThrow()
            .callHandler()
            .apply(null, CallToolRequest.builder("get_impact").arguments(arguments).build());
  }

  private JsonNode json(CallToolResult result) throws Exception {
    assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
    return objectMapper.readTree(text(result));
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
