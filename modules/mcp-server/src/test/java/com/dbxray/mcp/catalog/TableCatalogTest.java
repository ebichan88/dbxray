package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link TableCatalog}のテスト */
class TableCatalogTest {

  @Nested
  @DisplayName("テーブル検索")
  class SearchTables {

    @Test
    @DisplayName("一致の強い順に並べ、同点はDB名・スキーマ名・テーブル名の順にする")
    void ordersByScoreThenKey() {
      final SchemaCatalog catalog =
          TestCatalogs.of(
              List.of(
                  table("b_user_log").build(),
                  table("user").build(),
                  table("a_user_log").build(),
                  table("department").build()));

      final SearchResult result =
          catalog.tables().search(SearchQuery.of("user"), TableFilter.ALL, 10);

      assertEquals(3, result.total());
      assertEquals(List.of("user", "a_user_log", "b_user_log"), names(result));
    }

    @Test
    @DisplayName("件数の上限で切り捨てても、総数は切り捨てる前の数を返す")
    void limitsHitsButReportsTotal() {
      final SchemaCatalog catalog =
          TestCatalogs.of(
              List.of(table("user_a").build(), table("user_b").build(), table("user_c").build()));

      final SearchResult result =
          catalog.tables().search(SearchQuery.of("user"), TableFilter.ALL, 2);

      assertEquals(3, result.total());
      assertEquals(List.of("user_a", "user_b"), names(result));
    }

    @Test
    @DisplayName("DB名・スキーマ名で絞り込む（大文字小文字を区別しない）")
    void filtersByScope() {
      final SchemaCatalog catalog =
          TestCatalogs.of(
              List.of(
                  table("db1", "sales", "user").build(),
                  table("db1", "hr", "user").build(),
                  table("db2", "sales", "user").build()));

      final SearchResult result =
          catalog
              .tables()
              .search(SearchQuery.of("user"), TableFilter.of(new SearchScope("DB1", "SALES")), 10);

      assertEquals(List.of(new ObjectKey("db1", "sales", "user")), keys(result));
    }
  }

  @Nested
  @DisplayName("カラムの逆引き")
  class FindColumns {

    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("department").column("department_id", "部署ID", null).build(),
                table("employee")
                    .column("employee_id", "従業員ID", null)
                    .column("department_id", "所属部署ID", null)
                    .foreignKey("department_id", "department", "department_id")
                    .build(),
                table("audit_log")
                    .column("employee_no", "従業員ID", null)
                    .logicalRelation("employee_no", "employee", "employee_id")
                    .build(),
                table("project").column("leader_employee_id").column("employee").build()));

    @Test
    @DisplayName("完全一致では、物理名・論理名のどちらかが一致するカラムを返す（大文字小文字を区別しない）")
    void findsExactMatches() {
      assertEquals(
          List.of("audit_log.employee_no", "employee.employee_id"),
          describe(
              catalog
                  .tables()
                  .findColumns(ColumnQuery.of("従業員ID", MatchMode.EXACT), SearchScope.ALL)));
      assertEquals(
          List.of("department.department_id", "employee.department_id"),
          describe(
              catalog
                  .tables()
                  .findColumns(ColumnQuery.of("DEPARTMENT_ID", MatchMode.EXACT), SearchScope.ALL)));
    }

    @Test
    @DisplayName("部分一致では、完全一致・前方一致・部分一致の順に返す")
    void findsPartialMatches() {
      assertEquals(
          List.of(
              "project.employee",
              "audit_log.employee_no",
              "employee.employee_id",
              "project.leader_employee_id"),
          describe(
              catalog
                  .tables()
                  .findColumns(ColumnQuery.of("employee", MatchMode.PARTIAL), SearchScope.ALL)));
    }

    @Test
    @DisplayName("カラムが外部キー・論理リレーションで参照している先を返す")
    void returnsReferences() {
      final List<ColumnHit> hits =
          catalog
              .tables()
              .findColumns(ColumnQuery.of("department_id", MatchMode.EXACT), SearchScope.ALL);

      assertTrue(hits.get(0).references().isEmpty());
      assertEquals(
          List.of(
              new ColumnHit.ColumnReference(
                  new ObjectKey("testdb", "sample", "department"),
                  "department_id",
                  RelationKind.FOREIGN_KEY)),
          hits.get(1).references());
      assertEquals(
          RelationKind.LOGICAL_RELATION,
          catalog
              .tables()
              .findColumns(ColumnQuery.of("employee_no", MatchMode.EXACT), SearchScope.ALL)
              .get(0)
              .references()
              .get(0)
              .kind());
    }

    private static List<String> describe(List<ColumnHit> hits) {
      return hits.stream()
          .map(hit -> hit.table().key().name() + "." + hit.column().name())
          .toList();
    }
  }

  @Nested
  @DisplayName("名前の解決")
  class LookupTable {

    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("db1", "sales", "orders").build(),
                table("db1", "hr", "employee").build(),
                table("db1", "archive", "employee").build(),
                table("db1", "hr", "employee_profile").build()));

    @Test
    @DisplayName("テーブル名を大文字小文字を区別せず完全一致で解決する")
    void findsByNameIgnoringCase() {
      assertEquals(
          new ObjectKey("db1", "sales", "orders"),
          found(ObjectReference.of(null, null, "ORDERS")).key());
    }

    @Test
    @DisplayName("同名のテーブルが複数のスキーマにある場合は、候補をスキーマ名の順に返す")
    void reportsAmbiguousNames() {
      final Lookup<TableEntry> lookup =
          catalog.tables().lookup(ObjectReference.of(null, null, "employee"));

      assertEquals(
          List.of("archive.employee", "hr.employee"),
          Lookups.candidates(lookup).stream().map(table -> table.key().qualifiedName()).toList());
    }

    @Test
    @DisplayName("スキーマ名を引数で指定するか、スキーマ名.テーブル名の形で指定すると1つに定まる")
    void resolvesBySchema() {
      final ObjectKey expected = new ObjectKey("db1", "hr", "employee");

      assertEquals(expected, found(ObjectReference.of(null, "hr", "employee")).key());
      assertEquals(expected, found(ObjectReference.of(null, null, "hr.employee")).key());
    }

    @Test
    @DisplayName("見つからない場合は、スキーマの指定を外して名前の似たテーブルを候補に返す")
    void suggestsSimilarNames() {
      final Lookup<TableEntry> lookup =
          catalog.tables().lookup(ObjectReference.of(null, "sales", "employe"));

      assertEquals(
          List.of("archive.employee", "hr.employee", "hr.employee_profile"),
          Lookups.suggestions(lookup).stream().map(table -> table.key().qualifiedName()).toList());
    }

    @Test
    @DisplayName("似た名前も無い場合は、候補を空で返す")
    void returnsNoSuggestions() {
      final Lookup<TableEntry> lookup =
          catalog.tables().lookup(ObjectReference.of(null, null, "invoice"));

      assertTrue(Lookups.suggestions(lookup).isEmpty());
    }

    private TableEntry found(ObjectReference reference) {
      return Lookups.found(catalog.tables().lookup(reference));
    }
  }

  @Nested
  @DisplayName("オブジェクト間の相互参照")
  class CrossReferences {

    @Test
    @DisplayName("viewsReferencingは、テーブルを参照しているビューを名前の順に返し、別DBの同名テーブルを参照するビューは含めない")
    void findsViewsReferencingTable() {
      final SchemaCatalog views =
          TestCatalogs.of(
              List.of(
                  table("db1", "sales", "orders").build(),
                  table("db1", "sales", "v_orders").type("view").referencedTable("orders").build(),
                  table("db1", "sales", "a_mv")
                      .type("materialized_view")
                      .referencedTable("orders")
                      .referencedTable("v_orders")
                      .build(),
                  table("db2", "sales", "v_other").type("view").referencedTable("orders").build()));
      final TableEntry orders = views.tables().all().get(0);

      assertEquals(
          List.of("a_mv", "v_orders"),
          views.tables().viewsReferencing(orders).stream().map(view -> view.key().name()).toList());
      assertEquals(List.of(), views.tables().viewsReferencing(views.tables().all().get(2)));
    }

    private final SchemaCatalog catalog =
        SchemaCatalog.of(
            List.of(new DatabaseEntry("db1", "PostgreSQL", 16)),
            List.of(
                table("db1", "sales", "orders")
                    .column(
                        new ColumnEntry(
                            "id",
                            null,
                            "integer",
                            true,
                            true,
                            "nextval('sales.orders_id_seq'::regclass)",
                            null))
                    .column(
                        new ColumnEntry("status", null, "sales.status", false, true, null, null))
                    .column(new ColumnEntry("history", null, "status[]", false, false, null, null))
                    .trigger("trg_orders_audit", "sales.audit")
                    .build(),
                table("db1", "sales", "order_items")
                    .column(
                        new ColumnEntry(
                            "id", null, "integer", true, true, "nextval('orders_id_seq')", null))
                    .trigger("trg_items_audit", "audit()")
                    .build(),
                table("db1", "hr", "status").build()),
            List.of(
                new FunctionEntry(
                    new ObjectKey("db1", "sales", "audit"),
                    "FUNCTION",
                    "",
                    "trigger",
                    "plpgsql",
                    "{}",
                    "")),
            List.of(new SequenceEntry(new ObjectKey("db1", "sales", "orders_id_seq"), null, "{}")),
            List.of(new TypeEntry(new ObjectKey("db1", "sales", "status"), "ENUM", "{}")));

    @Test
    @DisplayName("関数を実行するトリガーを、トリガーの関数名のスキーマ修飾・括弧の有無に関わらず求める")
    void findsTriggersCallingFunction() {
      final FunctionOverloads audit =
          Lookups.found(catalog.functions().lookup(ObjectReference.of(null, null, "audit")));

      assertEquals(
          List.of("order_items.trg_items_audit", "orders.trg_orders_audit"),
          catalog.tables().triggersCalling(audit).stream()
              .map(found -> found.table().key().name() + "." + found.trigger().name())
              .toList());
    }

    @Test
    @DisplayName("シーケンスをデフォルト値のnextvalで使うカラムを求める")
    void findsColumnsUsingSequence() {
      assertEquals(
          List.of("order_items.id", "orders.id"),
          describe(
              catalog
                  .tables()
                  .columnsUsing(catalog.sequences().list(SearchScope.ALL, NameFilter.ALL).get(0))));
    }

    @Test
    @DisplayName("ユーザー定義型を型（配列を含む）に使うカラムを求め、同名のテーブルとは区別する")
    void findsColumnsUsingType() {
      assertEquals(
          List.of("orders.status", "orders.history"),
          describe(
              catalog
                  .tables()
                  .columnsUsing(catalog.types().list(SearchScope.ALL, NameFilter.ALL).get(0))));
    }

    private static List<String> describe(List<TableColumn> columns) {
      return columns.stream()
          .map(found -> found.table().key().name() + "." + found.column().name())
          .toList();
    }
  }

  private static List<String> names(SearchResult result) {
    return result.hits().stream().map(hit -> hit.table().key().name()).toList();
  }

  private static List<ObjectKey> keys(SearchResult result) {
    return result.hits().stream().map(hit -> hit.table().key()).toList();
  }
}
