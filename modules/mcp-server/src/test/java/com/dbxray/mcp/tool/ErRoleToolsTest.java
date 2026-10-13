package com.dbxray.mcp.tool;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TestCatalogs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ER上の区分（中間テーブル・多対多）を返す{@code get_table}・{@code list_tables}のテスト */
class ErRoleToolsTest {

  /**
   * project・employee（多対多の相手）、2つを主キーで参照する中間テーブルproject_assignment、employeeを主キーで参照するemployee_profile（1対1の拡張）
   */
  private final SchemaCatalog catalog =
      TestCatalogs.of(
          List.of(
              table("project").primaryKey("project_id").build(),
              table("employee").primaryKey("employee_id").build(),
              table("employee_profile")
                  .primaryKey("employee_id")
                  .foreignKey("employee_id", "employee", "employee_id")
                  .build(),
              table("project_assignment")
                  .primaryKey("project_id")
                  .primaryKey("employee_id")
                  .column("role")
                  .foreignKey("project_id", "project", "project_id")
                  .foreignKey("employee_id", "employee", "employee_id")
                  .build()));

  private final TableDefinitionTools tools = new TableDefinitionTools(catalog);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("get_tableは、中間テーブルにerRole（junction）とつなぐテーブル（junctionOf）を返す")
  void getTableOfJunction() throws Exception {
    final JsonNode result = json(call("get_table", Map.of("table", "project_assignment")));

    assertEquals("junction", result.get("erRole").asText());
    assertEquals(
        objectMapper.readTree("[\"sample.project\",\"sample.employee\"]"),
        result.get("junctionOf"));
    assertFalse(result.has("manyToMany"), "中間テーブル自身は多対多の相手を持たない");
  }

  @Test
  @DisplayName("get_tableは、中間テーブルを介して多対多の関係にあるテーブルに、相手のテーブルと中間テーブル（manyToMany）を返す")
  void getTableOfManyToManyPartner() throws Exception {
    final JsonNode result = json(call("get_table", Map.of("table", "project")));

    assertEquals(
        objectMapper.readTree(
            "[{\"table\":\"sample.employee\",\"via\":\"sample.project_assignment\"}]"),
        result.get("manyToMany"));
    assertFalse(result.has("erRole"));
    assertFalse(result.has("junctionOf"));
  }

  @Test
  @DisplayName("get_tableは、1対1の拡張テーブルなど通常のテーブルには、ER上の区分も多対多も返さない")
  void getTableOfOrdinaryTable() throws Exception {
    final JsonNode result = json(call("get_table", Map.of("table", "employee_profile")));

    assertFalse(result.has("erRole"));
    assertFalse(result.has("junctionOf"));
    assertFalse(result.has("manyToMany"));
  }

  @Test
  @DisplayName("get_tableは、sectionsで項目を絞り込んでも、ER上の区分と多対多を返す")
  void getTableKeepsErRoleWithSections() throws Exception {
    final JsonNode result =
        json(
            call(
                "get_table",
                Map.of("table", "project_assignment", "sections", List.of("foreignKeys"))));

    assertEquals("junction", result.get("erRole").asText());
  }

  @Test
  @DisplayName("get_tableは、複数のテーブルをまとめて取得した場合も、テーブルごとにER上の区分と多対多を返す")
  void getTableOfSeveralTables() throws Exception {
    final JsonNode result =
        json(call("get_table", Map.of("table", List.of("project", "project_assignment"))));

    assertFalse(result.get("tables").get(0).has("erRole"));
    assertTrue(result.get("tables").get(0).has("manyToMany"));
    assertEquals("junction", result.get("tables").get(1).get("erRole").asText());
  }

  @Test
  @DisplayName("リソースを読むと、get_tableと同じく多対多を返す")
  void resourceIncludesManyToMany() {
    final String uri = "dbxray://testdb/sample/project";
    final SyncResourceSpecification project =
        new TableDefinitionResources(catalog)
            .specifications().stream()
                .filter(spec -> spec.resource().uri().equals(uri))
                .findFirst()
                .orElseThrow();

    final ReadResourceResult result =
        project.readHandler().apply(null, new ReadResourceRequest(uri));

    final String text = ((TextResourceContents) result.contents().get(0)).text();
    assertTrue(
        text.contains(
            "\"manyToMany\":[{\"table\":\"sample.employee\",\"via\":\"sample.project_assignment\"}]"),
        text);
  }

  @Test
  @DisplayName("list_tablesは、中間テーブルの行にだけerRoleを返す")
  void listTablesReturnsErRoleOnlyForJunction() throws Exception {
    final JsonNode result = json(call("list_tables", Map.of()));

    for (final JsonNode table : result.get("tables")) {
      final boolean isJunction = table.get("name").asText().equals("project_assignment");
      assertEquals(isJunction, table.has("erRole"), table.toString());
      if (isJunction) {
        assertEquals("junction", table.get("erRole").asText());
      }
    }
  }

  @Test
  @DisplayName("list_tablesは、erRoleで中間テーブルだけに絞り込める")
  void listTablesFiltersByErRole() throws Exception {
    final JsonNode result = json(call("list_tables", Map.of("erRole", "junction")));

    assertEquals(1, result.get("total").asInt());
    assertEquals(List.of("project_assignment"), result.get("tables").findValuesAsText("name"));
  }

  @Test
  @DisplayName("list_tablesは、erRoleをtypeと組み合わせて絞り込める")
  void listTablesCombinesErRoleWithType() throws Exception {
    assertEquals(
        0,
        json(call("list_tables", Map.of("erRole", "junction", "type", "view")))
            .get("total")
            .asInt());
    assertEquals(
        1,
        json(call("list_tables", Map.of("erRole", "junction", "type", "table")))
            .get("total")
            .asInt());
  }

  @Test
  @DisplayName("list_tablesは、未知のerRoleをエラーにする")
  void listTablesRejectsUnknownErRole() {
    final CallToolResult invalid = call("list_tables", Map.of("erRole", "subtype"));

    assertTrue(invalid.isError());
    assertEquals("引数erRoleにはjunctionのいずれかを指定してください。 [value=subtype]", text(invalid));
  }

  @Test
  @DisplayName("get_tableは、manyToManyが上限以内ならmanyToManyTotalを返さない")
  void getTableWithoutTotalWithinLimit() throws Exception {
    assertFalse(json(call("get_table", Map.of("table", "project"))).has("manyToManyTotal"));
  }

  @Test
  @DisplayName("get_tableは、manyToManyを上限まで返し、超える場合は全体の件数をmanyToManyTotalで示す")
  void getTableTruncatesManyToMany() throws Exception {
    final int partners = TableOutputBuilder.MAX_MANY_TO_MANY + 1;
    final List<TableEntry> tables =
        new ArrayList<>(List.of(table("tenant").primaryKey("tenant_id").build()));
    for (int i = 0; i < partners; i++) {
      tables.add(table("x" + i).primaryKey("x_id").build());
      tables.add(
          table("tenant_x" + i)
              .primaryKey("tenant_id")
              .primaryKey("x_id")
              .foreignKey("tenant_id", "tenant", "tenant_id")
              .foreignKey("x_id", "x" + i, "x_id")
              .build());
    }

    final JsonNode result =
        json(
            call(
                new TableDefinitionTools(TestCatalogs.of(tables)),
                "get_table",
                Map.of("table", "tenant")));

    assertEquals(TableOutputBuilder.MAX_MANY_TO_MANY, result.get("manyToMany").size());
    assertEquals(partners, result.get("manyToManyTotal").asInt());
    assertEquals("sample.x0", result.get("manyToMany").get(0).get("table").asText());
  }

  @Test
  @DisplayName("get_tableのjunctionOfはスナップショットに無い親テーブルも含み、manyToManyは含まない")
  void junctionOfIncludesParentsMissingFromSnapshot() throws Exception {
    final TableDefinitionTools partial =
        new TableDefinitionTools(
            TestCatalogs.of(
                List.of(
                    table("project").primaryKey("project_id").build(),
                    table("project_customer")
                        .primaryKey("project_id")
                        .primaryKey("customer_id")
                        .foreignKey("project_id", "project", "project_id")
                        .foreignKey("customer_id", "customer", "customer_id")
                        .build())));

    assertEquals(
        objectMapper.readTree("[\"sample.project\",\"sample.customer\"]"),
        json(call(partial, "get_table", Map.of("table", "project_customer"))).get("junctionOf"));
    assertFalse(json(call(partial, "get_table", Map.of("table", "project"))).has("manyToMany"));
  }

  @Test
  @DisplayName("search_tablesは、中間テーブルの行にだけerRoleを返し、erRoleで中間テーブルだけに絞り込める")
  void searchTablesByErRole() throws Exception {
    final JsonNode all = json(call("search_tables", Map.of("query", "project")));
    assertEquals(2, all.get("total").asInt());
    for (final JsonNode hit : all.get("tables")) {
      assertEquals(
          hit.get("name").asText().equals("project_assignment"), hit.has("erRole"), hit.toString());
    }

    final JsonNode junctions =
        json(call("search_tables", Map.of("query", "project", "erRole", "junction")));
    assertEquals(List.of("project_assignment"), junctions.get("tables").findValuesAsText("name"));
    assertEquals("junction", junctions.get("tables").get(0).get("erRole").asText());
  }

  @Test
  @DisplayName("search_tablesは、未知のerRoleをエラーにする")
  void searchTablesRejectsUnknownErRole() {
    final CallToolResult invalid = call("search_tables", Map.of("query", "project", "erRole", "x"));

    assertTrue(invalid.isError());
    assertEquals("引数erRoleにはjunctionのいずれかを指定してください。 [value=x]", text(invalid));
  }

  @Test
  @DisplayName("list_tables・search_tablesの入力スキーマは、erRoleをjunctionだけを許す列挙にする")
  void inputSchemasRestrictErRole() {
    for (final String name : List.of("list_tables", "search_tables")) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> properties =
          (Map<String, Object>)
              tools.specifications().stream()
                  .filter(specification -> specification.tool().name().equals(name))
                  .findFirst()
                  .orElseThrow()
                  .tool()
                  .inputSchema()
                  .get("properties");

      assertEquals(List.of("junction"), ((Map<?, ?>) properties.get("erRole")).get("enum"), name);
    }
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
