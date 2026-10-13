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
import com.dbxray.mcp.catalog.SequenceEntry;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TestCatalogs;
import com.dbxray.mcp.catalog.TypeEntry;
import com.dbxray.mcp.catalog.ViewpointEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TableDefinitionTools}のテスト */
class TableDefinitionToolsTest {

  private final TableDefinitionTools tools =
      new TableDefinitionTools(
          SchemaCatalog.of(
                  List.of(new DatabaseEntry("testdb", "PostgreSQL", 16)),
                  List.of(
                      table("department").logicalName("部署").description("組織のマスタ").build(),
                      table("employee")
                          .logicalName("従業員")
                          .column(
                              new ColumnEntry(
                                  "employee_id",
                                  null,
                                  "integer",
                                  true,
                                  true,
                                  "nextval('sample.employee_id_seq'::regclass)",
                                  null))
                          .column(
                              new ColumnEntry(
                                  "status", null, "sample.status", false, true, null, null))
                          .column("department_id", "部署ID", null)
                          .foreignKey("department_id", "department", "department_id")
                          .trigger("trg_employee_audit", "sample.log_change")
                          .build(),
                      table("audit_log")
                          .logicalRelation("record_id", "employee", "employee_id")
                          .build(),
                      table("testdb", "archive", "employee").build(),
                      table("project")
                          .type("table")
                          .json(
                              "{\"schema\":\"sample\",\"name\":\"project\",\"type\":\"table\","
                                  + "\"columns\":[{\"name\":\"project_id\"},{\"name\":\"title\"},{\"name\":\"budget\"}],"
                                  + "\"indexes\":[{\"name\":\"project_pkey\"}],"
                                  + "\"triggers\":[{\"name\":\"trg_project\"}],"
                                  + "\"addedByNewerCli\":1}")
                          .column("project_id")
                          .column("title")
                          .column("budget")
                          .build()),
                  List.of(
                      function(
                          "calc_bonus",
                          "p_salary numeric",
                          "{\"schema\":\"sample\",\"name\":\"calc_bonus\",\"kind\":\"FUNCTION\","
                              + "\"arguments\":\"p_salary numeric\",\"result\":\"numeric\",\"language\":\"sql\","
                              + "\"definition\":\"CREATE FUNCTION ...\"}"),
                      function(
                          "calc_bonus",
                          "p_salary numeric, p_rate numeric",
                          "{\"schema\":\"sample\",\"name\":\"calc_bonus\",\"kind\":\"FUNCTION\","
                              + "\"arguments\":\"p_salary numeric, p_rate numeric\",\"result\":\"numeric\","
                              + "\"language\":\"sql\",\"definition\":\"CREATE FUNCTION ...\"}"),
                      new FunctionEntry(
                          new ObjectKey("testdb", "sample", "log_change"),
                          "FUNCTION",
                          "",
                          "trigger",
                          "plpgsql",
                          "{}",
                          ""),
                      function(
                          "withhold_tax",
                          "p_price numeric",
                          "{\"schema\":\"sample\",\"name\":\"withhold_tax\",\"kind\":\"FUNCTION\","
                              + "\"arguments\":\"p_price numeric\",\"result\":\"numeric\",\"language\":\"sql\","
                              + "\"definition\":\"CREATE FUNCTION withhold_tax(p_price numeric) ...\"}"),
                      function(
                          "withhold_tax",
                          "p_price numeric, p_rate numeric",
                          "{\"schema\":\"sample\",\"name\":\"withhold_tax\",\"kind\":\"FUNCTION\","
                              + "\"arguments\":\"p_price numeric, p_rate numeric\",\"result\":\"numeric\","
                              + "\"language\":\"sql\",\"definition\":\""
                              + "A".repeat(4500)
                              + "\"}")),
                  List.of(
                      new SequenceEntry(
                          new ObjectKey("testdb", "sample", "employee_id_seq"),
                          "employee.employee_id",
                          "{\"schema\":\"sample\",\"name\":\"employee_id_seq\",\"ownedBy\":\"employee.employee_id\"}")),
                  List.of(
                      new TypeEntry(
                          new ObjectKey("testdb", "sample", "status"),
                          "ENUM",
                          "{\"schema\":\"sample\",\"name\":\"status\",\"category\":\"ENUM\",\"definition\":\"A, B\"}")))
              .withViewpoints(
                  List.of(
                      new ViewpointEntry(
                          "testdb",
                          "org",
                          "組織",
                          "",
                          List.of(
                              new ObjectKey("testdb", "sample", "department"),
                              new ObjectKey("testdb", "sample", "employee")))))
              .withFunctionTableUsages(
                  List.of(
                      new FunctionTableUsageEntry(
                          new ObjectKey("testdb", "sample", "withhold_tax"),
                          "p_price numeric",
                          "",
                          "analyzed",
                          "",
                          List.of(
                              new UsedTable("sample", "employee", "", List.of(), List.of("R", "U")),
                              new UsedTable(
                                  "",
                                  "project",
                                  "synonym",
                                  List.of("sample", "archive"),
                                  List.of("D"))),
                          List.of("EXECUTE"),
                          true,
                          false),
                      new FunctionTableUsageEntry(
                          new ObjectKey("testdb", "sample", "withhold_tax"),
                          "p_price numeric, p_rate numeric",
                          "",
                          "analyzed",
                          "",
                          List.of(),
                          List.of(),
                          false,
                          true),
                      new FunctionTableUsageEntry(
                          new ObjectKey("testdb", "sample", "log_change"),
                          "",
                          "",
                          "unsupported_language",
                          "plpython3u",
                          List.of(),
                          List.of(),
                          false,
                          false))));

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("全体像をつかむ・探す・詳細を見る、の順に、読み取り専用のツールとして登録する")
  void registersReadOnlyTools() {
    final List<SyncToolSpecification> specifications = tools.specifications();

    assertEquals(
        List.of(
            "list_schemas",
            "list_viewpoints",
            "list_table_clusters",
            "search_tables",
            "list_tables",
            "get_table",
            "find_columns",
            "find_similar_tables",
            "get_related_tables",
            "find_join_path",
            "get_er_diagram",
            "list_functions",
            "get_function",
            "list_sequences",
            "get_sequence",
            "list_types",
            "get_type",
            "list_triggers"),
        specifications.stream().map(specification -> specification.tool().name()).toList());
    assertTrue(
        specifications.stream()
            .allMatch(specification -> specification.tool().annotations().readOnlyHint()));
    assertTrue(
        specifications.stream().allMatch(specification -> !specification.tool().title().isBlank()),
        "MCPクライアントの表示用に、全ツールにtitleを付ける");
    assertEquals(
        List.of("query"), specification("search_tables").tool().inputSchema().get("required"));
  }

  @Test
  @DisplayName("list_schemasは、DBごとにDBMS種別・メジャーバージョン・スキーマと、スキーマごとのオブジェクトの数を返す")
  void listSchemas() throws Exception {
    assertEquals(
        "{\"databases\":[{\"name\":\"testdb\",\"dbms\":\"PostgreSQL\",\"majorVersion\":16,\"schemas\":["
            + "{\"name\":\"archive\",\"tables\":1,\"views\":0,\"materializedViews\":0,"
            + "\"functions\":0,\"procedures\":0,\"sequences\":0,\"types\":0,\"triggers\":0},"
            + "{\"name\":\"sample\",\"tables\":4,\"views\":0,\"materializedViews\":0,"
            + "\"functions\":5,\"procedures\":0,\"sequences\":1,\"types\":1,\"triggers\":1}]}]}",
        json(call("list_schemas", Map.of())).toString());
  }

  @Test
  @DisplayName("list_viewpointsは、識別子・表示名・説明・所属テーブル数を返す")
  void listViewpoints() throws Exception {
    assertEquals(
        "{\"viewpoints\":[{\"database\":\"testdb\",\"id\":\"org\",\"name\":\"組織\",\"tableCount\":2}]}",
        json(call("list_viewpoints", Map.of())).toString());
  }

  @Test
  @DisplayName("list_table_clustersは、関連でつながるテーブルのまとまりと、含まれる観点・関連の無いテーブルの数を返す")
  void listTableClusters() throws Exception {
    assertEquals(
        objectMapper.readTree(
            "{\"total\":1,\"unrelatedTables\":2,\"clusters\":[{\"database\":\"testdb\","
                + "\"representative\":\"sample.department\",\"size\":3,\"tables\":["
                + "{\"name\":\"sample.department\",\"logicalName\":\"部署\"},"
                + "{\"name\":\"sample.employee\",\"logicalName\":\"従業員\"},"
                + "{\"name\":\"sample.audit_log\"}],\"viewpoints\":[\"org\"]}]}"),
        json(call("list_table_clusters", Map.of())));

    final JsonNode unassigned =
        json(call("list_table_clusters", Map.of("excludeViewpointTables", true)));
    assertEquals(0, unassigned.get("total").asInt());
    assertEquals(3, unassigned.get("unrelatedTables").asInt());
  }

  @Test
  @DisplayName("list_tablesは、テーブルの概要を名前の順に返し、続きがある場合はnextOffsetを返す")
  void listTables() throws Exception {
    final JsonNode first = json(call("list_tables", Map.of("schema", "sample", "limit", 2)));

    assertEquals(4, first.get("total").asInt());
    assertEquals(2, first.get("nextOffset").asInt());
    assertEquals(List.of("audit_log", "department"), first.get("tables").findValuesAsText("name"));
    assertFalse(first.get("tables").get(1).has("description"), "既定では説明を返さない");

    final JsonNode rest =
        json(
            call(
                "list_tables",
                Map.of("schema", "sample", "offset", 2, "limit", 2, "includeDescription", true)));
    assertEquals(List.of("employee", "project"), rest.get("tables").findValuesAsText("name"));
    assertFalse(rest.has("nextOffset"), "続きが無い場合はnextOffsetを返さない");

    final JsonNode described = json(call("list_tables", Map.of("includeDescription", true)));
    assertEquals("組織のマスタ", described.get("tables").get(2).get("description").asText());
  }

  @Test
  @DisplayName("list_tablesは区分で絞り込め、未知の区分はエラーにする")
  void listTablesByType() throws Exception {
    assertEquals(0, json(call("list_tables", Map.of("type", "view"))).get("total").asInt());

    final CallToolResult invalid = call("list_tables", Map.of("type", "index"));
    assertTrue(invalid.isError());
    assertEquals(
        "引数typeにはtable, view, materialized_viewのいずれかを指定してください。 [value=index]", text(invalid));
  }

  @Test
  @DisplayName("list_tablesは、関連の数を返し、orderByで関連の数の多い順に並べられる")
  void listTablesOrderedByRelations() throws Exception {
    final JsonNode byName = json(call("list_tables", Map.of("schema", "sample")));
    assertEquals(
        "{\"database\":\"testdb\",\"schema\":\"sample\",\"name\":\"department\","
            + "\"logicalName\":\"部署\",\"type\":\"table\",\"incoming\":1,\"outgoing\":0,\"impact\":2}",
        byName.get("tables").get(1).toString());

    final JsonNode byImpact =
        json(call("list_tables", Map.of("schema", "sample", "orderBy", "impact")));
    assertEquals(
        List.of("department", "employee", "audit_log", "project"),
        byImpact.get("tables").findValuesAsText("name"));

    final CallToolResult invalid = call("list_tables", Map.of("orderBy", "size"));
    assertTrue(invalid.isError());
    assertEquals(
        "引数orderByにはname, incoming, outgoing, impactのいずれかを指定してください。 [value=size]", text(invalid));
  }

  @Test
  @DisplayName("search_tablesは、一致したテーブルの概要と一致した項目を、値の無い項目を省いて返す")
  void searchTables() throws Exception {
    final JsonNode result = json(call("search_tables", Map.of("query", "部署")));

    assertEquals(2, result.get("total").asInt());
    final JsonNode first = result.get("tables").get(0);
    assertEquals("department", first.get("name").asText());
    assertEquals("部署", first.get("logicalName").asText());
    assertEquals("組織のマスタ", first.get("description").asText());
    assertEquals("[\"logicalName\"]", first.get("matchedIn").toString());
    final JsonNode second = result.get("tables").get(1);
    assertEquals("employee", second.get("name").asText());
    assertFalse(second.has("description"), "値の無い項目は出力しない");
  }

  @Test
  @DisplayName("search_tablesはschema・limitで絞り込める")
  void searchTablesWithScopeAndLimit() throws Exception {
    assertEquals(
        1,
        json(call("search_tables", Map.of("query", "employee", "schema", "archive")))
            .get("tables")
            .size());
    final JsonNode limited = json(call("search_tables", Map.of("query", "employee", "limit", 1)));
    assertEquals(2, limited.get("total").asInt());
    assertEquals(1, limited.get("tables").size());
  }

  @Test
  @DisplayName("list_tablesは、viewpointで指定した観点の所属テーブルだけに絞り込める")
  void listTablesByViewpoint() throws Exception {
    final JsonNode result = json(call("list_tables", Map.of("viewpoint", "org")));

    assertEquals(List.of("department", "employee"), result.get("tables").findValuesAsText("name"));
  }

  @Test
  @DisplayName("list_tablesは、存在しない観点を指定すると、観点の一覧を示すエラーを返す")
  void listTablesByUnknownViewpoint() {
    final CallToolResult result = call("list_tables", Map.of("viewpoint", "nope"));

    assertTrue(result.isError());
    assertEquals("観点nopeが見つかりません。観点: org", text(result));
  }

  @Test
  @DisplayName("search_tablesは、viewpointで指定した観点の所属テーブルだけに絞り込む（観点外の一致は除く）")
  void searchTablesByViewpoint() throws Exception {
    assertEquals(2, json(call("search_tables", Map.of("query", "employee"))).get("total").asInt());

    final JsonNode result =
        json(call("search_tables", Map.of("query", "employee", "viewpoint", "org")));
    assertEquals(1, result.get("total").asInt());
    final JsonNode hit = result.get("tables").get(0);
    assertEquals("sample", hit.get("schema").asText());
    assertEquals("employee", hit.get("name").asText());
  }

  @Test
  @DisplayName("get_tableは、観点に所属しないテーブルではスナップショットの1行をそのまま返す")
  void getTable() {
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"audit_log\"}",
        text(call("get_table", Map.of("table", "audit_log"))));
  }

  @Test
  @DisplayName("get_tableは、テーブルが所属する観点（id・表示名）をviewpointsで返す")
  void getTableWithViewpoints() {
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"department\","
            + "\"viewpoints\":[{\"id\":\"org\",\"name\":\"組織\"}]}",
        text(call("get_table", Map.of("table", "department"))));
  }

  @Test
  @DisplayName("get_tableは、ビューの参照するテーブルに加え、テーブルを参照しているビューをreferencedByViewsで返す。sectionsで絞り込める")
  void getTableWithViewReferences() throws Exception {
    final TableDefinitionTools viewTools =
        new TableDefinitionTools(
            TestCatalogs.of(
                List.of(
                    table("orders").build(),
                    table("v_orders")
                        .type("view")
                        .referencedTable("orders")
                        .json(
                            "{\"schema\":\"sample\",\"name\":\"v_orders\",\"type\":\"view\","
                                + "\"referencedTables\":[{\"schema\":\"sample\",\"name\":\"orders\",\"type\":\"table\"}]}")
                        .build())));
    final SyncToolSpecification getTable =
        viewTools.specifications().stream()
            .filter(candidate -> candidate.tool().name().equals("get_table"))
            .findFirst()
            .orElseThrow();

    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"orders\","
            + "\"referencedByViews\":[{\"schema\":\"sample\",\"name\":\"v_orders\",\"type\":\"view\"}]}",
        text(
            getTable
                .callHandler()
                .apply(
                    null,
                    CallToolRequest.builder("get_table")
                        .arguments(Map.of("table", "orders"))
                        .build())));
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"orders\"}",
        text(
            getTable
                .callHandler()
                .apply(
                    null,
                    CallToolRequest.builder("get_table")
                        .arguments(Map.of("table", "orders", "sections", List.of("columns")))
                        .build())));
    assertEquals(
        "[{\"schema\":\"sample\",\"name\":\"orders\",\"type\":\"table\"}]",
        objectMapper
            .readTree(
                text(
                    getTable
                        .callHandler()
                        .apply(
                            null,
                            CallToolRequest.builder("get_table")
                                .arguments(Map.of("table", "v_orders"))
                                .build())))
            .get("referencedTables")
            .toString());
  }

  @Test
  @DisplayName("get_tableは、sectionsを指定しても所属する観点を返す")
  void getTableWithViewpointsAndSections() throws Exception {
    final JsonNode result =
        json(call("get_table", Map.of("table", "department", "sections", List.of("indexes"))));

    assertEquals("[{\"id\":\"org\",\"name\":\"組織\"}]", result.get("viewpoints").toString());
  }

  @Test
  @DisplayName("get_tableでtableを複数指定した場合は、テーブルごとに所属する観点を返す（所属しないテーブルには付けない）")
  void getTableMultipleWithViewpoints() throws Exception {
    final JsonNode tables =
        json(call("get_table", Map.of("table", List.of("department", "project")))).get("tables");

    assertEquals("[{\"id\":\"org\",\"name\":\"組織\"}]", tables.get(0).get("viewpoints").toString());
    assertFalse(tables.get(1).has("viewpoints"));
  }

  @Test
  @DisplayName("get_tableは、sectionsで指定した項目と常に返す項目だけを返し、スナップショットの未知の項目も残す")
  void getTableWithSections() throws Exception {
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"project\",\"type\":\"table\","
            + "\"triggers\":[{\"name\":\"trg_project\"}],\"addedByNewerCli\":1}",
        json(call("get_table", Map.of("table", "project", "sections", List.of("triggers"))))
            .toString());
  }

  @Test
  @DisplayName("get_tableは、columnsで指定したカラムだけを定義の並び順で返す（sectionsに無くてもcolumnsの項目は返す）")
  void getTableWithColumns() throws Exception {
    final JsonNode onlyColumns =
        json(
            call(
                "get_table",
                Map.of("table", "project", "columns", List.of("BUDGET", "project_id"))));
    assertEquals(
        "[{\"name\":\"project_id\"},{\"name\":\"budget\"}]", onlyColumns.get("columns").toString());
    assertTrue(onlyColumns.has("indexes"), "sectionsを指定しなければ他の項目も返す");

    final JsonNode withSections =
        json(
            call(
                "get_table",
                Map.of("table", "project", "sections", "indexes", "columns", List.of("title"))));
    assertEquals("[{\"name\":\"title\"}]", withSections.get("columns").toString());
    assertTrue(withSections.has("indexes"));
    assertFalse(withSections.has("triggers"));
  }

  @Test
  @DisplayName("get_tableで、テーブルに無いカラム・未知の項目を指定した場合はエラーにする")
  void getTableWithInvalidSelection() {
    final CallToolResult column =
        call("get_table", Map.of("table", "project", "columns", List.of("title", "owner")));
    assertTrue(column.isError());
    assertEquals("テーブルsample.projectにカラムownerがありません。カラム: project_id, title, budget", text(column));

    final CallToolResult section =
        call("get_table", Map.of("table", "project", "sections", List.of("columns", "partitions")));
    assertTrue(section.isError());
    assertTrue(text(section).startsWith("引数sectionsにはcolumns, indexes,"), text(section));
  }

  @Test
  @DisplayName("find_columnsは、カラムを持つテーブルと、カラムの型・参照先を返す")
  void findColumns() throws Exception {
    final JsonNode result = json(call("find_columns", Map.of("column", "部署ID")));

    assertEquals(
        "{\"total\":1,\"columns\":[{\"database\":\"testdb\",\"schema\":\"sample\","
            + "\"table\":\"employee\",\"tableLogicalName\":\"従業員\",\"column\":\"department_id\","
            + "\"logicalName\":\"部署ID\",\"type\":\"integer\",\"primaryKey\":false,\"notNull\":false,"
            + "\"references\":[{\"table\":\"sample.department\",\"column\":\"department_id\","
            + "\"kind\":\"foreignKey\"}]}]}",
        result.toString());
    assertEquals(
        0, json(call("find_columns", Map.of("column", "project"))).get("total").asInt(), "既定は完全一致");
    assertEquals(
        1,
        json(call("find_columns", Map.of("column", "project", "match", "partial")))
            .get("total")
            .asInt());
  }

  @Test
  @DisplayName("list_functionsは、関数のシグネチャを定義本体を除いて返し、名前の一部で絞り込める")
  void listFunctions() throws Exception {
    final JsonNode result = json(call("list_functions", Map.of("query", "BONUS")));

    assertEquals(2, result.get("total").asInt());
    assertEquals(
        "{\"database\":\"testdb\",\"schema\":\"sample\",\"name\":\"calc_bonus\",\"kind\":\"FUNCTION\","
            + "\"arguments\":\"p_salary numeric\",\"result\":\"void\",\"language\":\"sql\"}",
        result.get("functions").get(0).toString());
  }

  @Test
  @DisplayName("get_functionは、オーバーロードをまとめて、定義本体を除いたシグネチャを返す")
  void getFunction() throws Exception {
    assertEquals(
        "{\"database\":\"testdb\",\"schema\":\"sample\",\"name\":\"calc_bonus\",\"overloads\":["
            + "{\"kind\":\"FUNCTION\",\"arguments\":\"p_salary numeric\",\"result\":\"numeric\",\"language\":\"sql\"},"
            + "{\"kind\":\"FUNCTION\",\"arguments\":\"p_salary numeric, p_rate numeric\",\"result\":\"numeric\","
            + "\"language\":\"sql\"}]}",
        json(call("get_function", Map.of("function", "sample.calc_bonus"))).toString());

    final CallToolResult notFound = call("get_function", Map.of("function", "calc"));
    assertTrue(notFound.isError());
    assertEquals("関数calcが見つかりません。名前の似た関数: testdb:sample.calc_bonus", text(notFound));
  }

  @Test
  @DisplayName("get_functionは、参考情報がある場合にオーバーロードごとの利用しているテーブル（tableUsage）を返す")
  void getFunctionWithTableUsage() throws Exception {
    final JsonNode overloads =
        json(call("get_function", Map.of("function", "withhold_tax"))).get("overloads");

    assertEquals(
        "{\"tables\":[{\"table\":\"sample.employee\",\"operations\":\"RU\"},"
            + "{\"table\":\"project\",\"schemaResolution\":\"synonym\","
            + "\"schemaCandidates\":[\"sample\",\"archive\"],\"operations\":\"D\"}],"
            + "\"dynamicSql\":[\"EXECUTE\"],\"incomplete\":true}",
        overloads.get(0).get("tableUsage").toString());
    assertEquals(
        "{\"tables\":[],\"overloadsMerged\":true}",
        overloads.get(1).get("tableUsage").toString(),
        "該当なしでも、解析したことが分かるよう空の配列を返す");
    assertEquals(
        "{\"status\":\"unsupported_language\",\"language\":\"plpython3u\"}",
        json(call("get_function", Map.of("function", "log_change")))
            .get("overloads")
            .get(0)
            .get("tableUsage")
            .toString());
    assertFalse(
        json(call("get_function", Map.of("function", "calc_bonus")))
            .get("overloads")
            .get(0)
            .has("tableUsage"),
        "参考情報が無い関数には項目ごと返さない");
  }

  @Test
  @DisplayName("get_functionは、トリガー関数を実行するトリガーも返す")
  void getFunctionWithTriggers() throws Exception {
    assertEquals(
        "[{\"table\":\"sample.employee\",\"trigger\":\"trg_employee_audit\",\"timing\":\"AFTER\","
            + "\"events\":[\"INSERT\"]}]",
        json(call("get_function", Map.of("function", "log_change")))
            .get("calledByTriggers")
            .toString());
  }

  @Test
  @DisplayName("get_functionは、includeDefinitionを指定すると定義本体を返す。" + "オーバーロードの本体が同じ場合は1つにまとめる")
  void getFunctionWithSharedDefinition() throws Exception {
    final JsonNode result =
        json(call("get_function", Map.of("function", "calc_bonus", "includeDefinition", true)));
    assertEquals("CREATE FUNCTION ...", result.get("definition").asText());
    assertFalse(result.get("overloads").get(0).has("definition"));
    assertFalse(result.get("overloads").get(1).has("definition"));

    final JsonNode withoutDefinition = json(call("get_function", Map.of("function", "calc_bonus")));
    assertFalse(withoutDefinition.has("definition"), "includeDefinition未指定では返さない");
  }

  @Test
  @DisplayName("get_functionは、includeDefinition指定時にオーバーロードの本体が異なる場合はそれぞれ残し、" + "長い場合は切り詰める")
  void getFunctionWithDifferingDefinitions() throws Exception {
    final JsonNode result =
        json(call("get_function", Map.of("function", "withhold_tax", "includeDefinition", true)));
    assertFalse(result.has("definition"), "本体が異なる場合はオーバーロードごとに返す");

    final JsonNode overloads = result.get("overloads");
    assertEquals(
        "CREATE FUNCTION withhold_tax(p_price numeric) ...",
        overloads.get(0).get("definition").asText());

    final String truncated = overloads.get(1).get("definition").asText();
    assertTrue(truncated.length() < 4500, "長い本体は切り詰められる");
    assertTrue(truncated.contains("切り詰め"), truncated);
  }

  @Test
  @DisplayName("list_sequences・get_sequenceは、シーケンスの所有カラム・定義と、採番に使うカラムを返す")
  void sequences() throws Exception {
    assertEquals(
        "{\"total\":1,\"sequences\":[{\"database\":\"testdb\",\"schema\":\"sample\","
            + "\"name\":\"employee_id_seq\",\"ownedBy\":\"employee.employee_id\"}]}",
        json(call("list_sequences", Map.of())).toString());
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"employee_id_seq\",\"ownedBy\":\"employee.employee_id\","
            + "\"usedByColumns\":[\"sample.employee.employee_id\"]}",
        text(call("get_sequence", Map.of("sequence", "EMPLOYEE_ID_SEQ"))));

    final CallToolResult notFound = call("get_sequence", Map.of("sequence", "invoice_seq"));
    assertTrue(notFound.isError());
    assertEquals("シーケンスinvoice_seqが見つかりません。list_sequencesで探してください。", text(notFound));
  }

  @Test
  @DisplayName("list_types・get_typeは、ユーザー定義型の種別・定義と型を使うカラムを返し、種別で絞り込める")
  void types() throws Exception {
    assertEquals(1, json(call("list_types", Map.of("category", "ENUM"))).get("total").asInt());
    assertEquals(0, json(call("list_types", Map.of("category", "DOMAIN"))).get("total").asInt());
    assertEquals(
        "{\"schema\":\"sample\",\"name\":\"status\",\"category\":\"ENUM\",\"definition\":\"A, B\","
            + "\"usedByColumns\":[\"sample.employee.status\"]}",
        text(call("get_type", Map.of("type", "status"))));
  }

  @Test
  @DisplayName("list_triggersは、テーブルをまたいでトリガーと実行される関数を返す")
  void listTriggers() throws Exception {
    assertEquals(
        "{\"total\":1,\"triggers\":[{\"database\":\"testdb\",\"schema\":\"sample\",\"table\":\"employee\","
            + "\"name\":\"trg_employee_audit\",\"timing\":\"AFTER\",\"events\":[\"INSERT\"],"
            + "\"orientation\":\"ROW\",\"function\":\"sample.log_change\"}]}",
        json(call("list_triggers", Map.of())).toString());
  }

  @Test
  @DisplayName("get_tableで同名のテーブルが複数ある場合は、候補を示すエラーを返す")
  void getTableAmbiguous() {
    final CallToolResult result = call("get_table", Map.of("table", "employee"));

    assertTrue(result.isError());
    assertEquals(
        "テーブルemployeeが複数あります。schema（DBが異なる場合はdatabase）を指定してください。候補: testdb:archive.employee, testdb:sample.employee",
        text(result));
  }

  @Test
  @DisplayName("get_tableで見つからない場合は、名前で検索した候補（カラム名での一致を含む）、無ければsearch_tablesを案内するエラーを返す")
  void getTableNotFound() {
    final CallToolResult similar = call("get_table", Map.of("table", "depart"));
    assertTrue(similar.isError());
    assertEquals(
        "テーブルdepartが見つかりません。名前の似たテーブル: testdb:sample.department, testdb:sample.employee",
        text(similar));

    final CallToolResult none = call("get_table", Map.of("table", "invoice"));
    assertTrue(none.isError());
    assertEquals("テーブルinvoiceが見つかりません。search_tablesで探してください。", text(none));
  }

  @Test
  @DisplayName("get_tableはtableに配列を指定すると、複数のテーブルをまとめて返す")
  void getTableMultiple() throws Exception {
    final JsonNode result =
        json(call("get_table", Map.of("table", List.of("department", "project"))));

    final JsonNode tables = result.get("tables");
    assertEquals(2, tables.size());
    assertEquals("department", tables.get(0).get("name").asText());
    assertEquals("project", tables.get(1).get("name").asText());
  }

  @Test
  @DisplayName("get_tableで配列のうち1つでも解決できない名前があれば、まとめてエラーを返す")
  void getTableMultipleWithError() {
    final CallToolResult result =
        call("get_table", Map.of("table", List.of("department", "invoice", "employee")));

    assertTrue(result.isError());
    assertTrue(text(result).contains("テーブルinvoiceが見つかりません"), text(result));
    assertTrue(text(result).contains("テーブルemployeeが複数あります"), text(result));
  }

  @Test
  @DisplayName("get_tableでtableを複数指定した場合、columnsは使えない")
  void getTableMultipleWithColumns() {
    final CallToolResult result =
        call(
            "get_table",
            Map.of("table", List.of("department", "project"), "columns", List.of("title")));

    assertTrue(result.isError());
    assertEquals("引数columnsは、tableを1件指定した場合だけ使えます。", text(result));
  }

  @Test
  @DisplayName("get_tableでtableの件数が上限を超える場合はエラーにする")
  void getTableTooMany() {
    final List<String> names = IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();

    final CallToolResult result = call("get_table", Map.of("table", names));

    assertTrue(result.isError());
    assertEquals("引数tableは10件までにしてください。 [count=11]", text(result));
  }

  @Test
  @DisplayName("get_related_tablesは、関連と、関連に現れたテーブルの概要を返す")
  void getRelatedTables() throws Exception {
    final JsonNode result = json(call("get_related_tables", Map.of("table", "sample.employee")));

    assertEquals("sample.employee", result.get("table").asText());
    assertEquals(
        "[{\"from\":\"sample.employee\",\"fromColumns\":[\"department_id\"],\"to\":\"sample.department\","
            + "\"toColumns\":[\"department_id\"],\"kind\":\"foreignKey\",\"cardinality\":\"ONE_TO_MANY\","
            + "\"name\":\"employee_department_id_fkey\",\"depth\":1},"
            + "{\"from\":\"sample.audit_log\",\"fromColumns\":[\"record_id\"],\"to\":\"sample.employee\","
            + "\"toColumns\":[\"employee_id\"],\"kind\":\"logicalRelation\",\"cardinality\":\"OPTIONAL_ONE_TO_MANY\","
            + "\"name\":\"record_id\",\"depth\":1}]",
        result.get("relations").toString());
    assertEquals(
        List.of("sample.employee", "sample.department", "sample.audit_log"),
        result.get("tables").findValuesAsText("name"));
    assertFalse(result.has("missingTables"));
  }

  @Test
  @DisplayName("get_related_tablesは、direction・depthを指定できる")
  void getRelatedTablesWithDirection() throws Exception {
    final JsonNode result =
        json(
            call(
                "get_related_tables",
                Map.of("table", "department", "direction", "incoming", "depth", 2)));

    assertEquals(
        List.of("sample.employee", "sample.audit_log"),
        result.get("relations").findValuesAsText("from"));
    assertEquals(
        List.of(1, 2),
        result.get("relations").findValues("depth").stream().map(JsonNode::asInt).toList());
  }

  @Test
  @DisplayName("find_join_pathは、最短の経路をたどる順のテーブルと、各段の関連で返す")
  void findJoinPath() throws Exception {
    final JsonNode result =
        json(call("find_join_path", Map.of("from", "audit_log", "to", "sample.department")));

    assertEquals("sample.audit_log", result.get("from").asText());
    assertEquals(1, result.get("paths").size());
    final JsonNode path = result.get("paths").get(0);
    assertEquals(
        "[\"sample.audit_log\",\"sample.employee\",\"sample.department\"]",
        path.get("tables").toString());
    assertEquals(
        List.of("logicalRelation", "foreignKey"), path.get("joins").findValuesAsText("kind"));
    assertFalse(path.get("joins").get(0).has("depth"));
    assertFalse(result.has("hasMore"));
  }

  @Test
  @DisplayName("find_join_pathでつながらない場合は、次に何をすればよいかを返し、同じテーブルはエラーにする")
  void findJoinPathUnreachable() throws Exception {
    final JsonNode result =
        json(call("find_join_path", Map.of("from", "project", "to", "department")));
    assertFalse(result.has("paths"));
    assertTrue(result.get("message").asText().contains("maxLength"), result.toString());

    final CallToolResult same =
        call("find_join_path", Map.of("from", "department", "to", "sample.department"));
    assertTrue(same.isError());
    assertEquals("fromとtoに同じテーブルが指定されています。", text(same));
  }

  @Test
  @DisplayName("引数の誤りは、ツールのエラーとして直し方を返す")
  void returnsArgumentErrors() {
    final CallToolResult missing = call("search_tables", Map.of());
    assertTrue(missing.isError());
    assertEquals("引数queryを指定してください。", text(missing));
  }

  @Test
  @DisplayName("get_er_diagramは、viewpointを指定すると観点の所属テーブルとその間の関連をMermaidで返す")
  void getErDiagramOfViewpoint() throws Exception {
    final JsonNode result = json(call("get_er_diagram", Map.of("viewpoint", "ORG")));

    assertEquals("org", result.get("viewpoint").asText());
    assertFalse(result.has("table"));
    assertEquals(
        "erDiagram\n"
            + "    sample_department[\"department（部署）\"]\n"
            + "    sample_employee[\"employee（従業員）\"]\n"
            + "    sample_department ||--o{ sample_employee : \"employee_department_id_fkey\"\n"
            + "    sample_employee {\n"
            + "        integer department_id FK \"部署ID\"\n"
            + "    }\n",
        result.get("mermaid").asText());
    assertEquals(
        List.of("sample.department", "sample.employee"), jsonStrings(result.get("tables")));
    assertFalse(result.has("message"));
  }

  @Test
  @DisplayName("get_er_diagramは、tableを指定するとたどった関連を描き、論理リレーションは破線にする")
  void getErDiagramOfTable() throws Exception {
    final JsonNode result =
        json(call("get_er_diagram", Map.of("table", "audit_log", "direction", "outgoing")));

    assertEquals("sample.audit_log", result.get("table").asText());
    assertEquals(
        "erDiagram\n"
            + "    sample_audit_log[\"audit_log\"]\n"
            + "    sample_employee[\"employee（従業員）\"]\n"
            + "    sample_employee |o..o{ sample_audit_log : \"record_id\"\n"
            + "    sample_employee {\n"
            + "        integer employee_id PK\n"
            + "    }\n",
        result.get("mermaid").asText());
  }

  @Test
  @DisplayName("get_er_diagramは、関連が無い場合も箱だけの図を返し、その旨をmessageで示す")
  void getErDiagramWithoutRelations() throws Exception {
    final JsonNode result = json(call("get_er_diagram", Map.of("table", "project")));

    assertEquals("erDiagram\n    sample_project[\"project\"]\n", result.get("mermaid").asText());
    assertEquals("図に描くテーブルの間に、外部キー・論理リレーションはありません。", result.get("message").asText());
  }

  @Test
  @DisplayName("get_er_diagramは、tableとviewpointのどちらか一方だけを受け付け、観点の図にtable用の引数を指定すると誤りにする")
  void getErDiagramRejectsInvalidArguments() {
    assertEquals("引数tableとviewpointは、どちらか一方を指定してください。", text(call("get_er_diagram", Map.of())));
    assertEquals(
        "引数tableとviewpointは、どちらか一方を指定してください。",
        text(call("get_er_diagram", Map.of("table", "employee", "viewpoint", "org"))));
    final CallToolResult withDepth =
        call("get_er_diagram", Map.of("viewpoint", "org", "depth", 2, "schema", "sample"));
    assertTrue(withDepth.isError());
    assertEquals("引数schema・depthは、tableを指定した場合だけ使えます。", text(withDepth));
    assertEquals(
        "観点nopeが見つかりません。観点: org", text(call("get_er_diagram", Map.of("viewpoint", "nope"))));
  }

  @Test
  @DisplayName("get_er_diagramは、図に描くテーブルが上限を超える場合は、範囲の絞り方を示す誤りにする")
  void getErDiagramRejectsTooLargeDiagram() {
    final List<TableEntry> tables = new ArrayList<>();
    tables.add(table("hub").build());
    for (int i = 0; i < DiagramTools.MAX_NODES; i++) {
      tables.add(table("t" + i).foreignKey("hub_id", "hub", "hub_id").build());
    }
    final SyncToolSpecification tool =
        new TableDefinitionTools(TestCatalogs.of(tables))
            .specifications().stream()
                .filter(candidate -> candidate.tool().name().equals("get_er_diagram"))
                .findFirst()
                .orElseThrow();

    final CallToolResult result =
        tool.callHandler()
            .apply(
                null,
                CallToolRequest.builder("get_er_diagram")
                    .arguments(Map.of("table", "hub"))
                    .build());

    assertTrue(result.isError());
    assertEquals("図に描くテーブルが81件となり、上限（80件）を超えます。depthを小さくするか、directionで向きを絞ってください。", text(result));
  }

  private static List<String> jsonStrings(JsonNode array) {
    final List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static FunctionEntry function(String name, String arguments, String json) {
    return new FunctionEntry(
        new ObjectKey("testdb", "sample", name), "FUNCTION", arguments, "void", "sql", json, "");
  }

  private CallToolResult call(String name, Map<String, Object> arguments) {
    return specification(name)
        .callHandler()
        .apply(null, CallToolRequest.builder(name).arguments(arguments).build());
  }

  private SyncToolSpecification specification(String name) {
    return tools.specifications().stream()
        .filter(candidate -> candidate.tool().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private JsonNode json(CallToolResult result) throws Exception {
    assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
    return objectMapper.readTree(text(result));
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
