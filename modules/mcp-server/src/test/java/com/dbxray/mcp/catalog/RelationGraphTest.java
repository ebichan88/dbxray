package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link RelationGraph}のテスト */
class RelationGraphTest {

  @Nested
  @DisplayName("JOIN経路の探索")
  class JoinPathsTest {

    /**
     * employee → department、assignment → employee・project、project → department、audit_log ⇢
     * employee（論理）、orders → customer（スナップショットに無い）
     */
    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("department").build(),
                table("employee")
                    .foreignKey("department_id", "department", "department_id")
                    .foreignKey("manager_id", "employee", "employee_id")
                    .build(),
                table("project").foreignKey("department_id", "department", "department_id").build(),
                table("assignment")
                    .foreignKey("employee_id", "employee", "employee_id")
                    .foreignKey("project_id", "project", "project_id")
                    .build(),
                table("audit_log").logicalRelation("record_id", "employee", "employee_id").build(),
                table("orders").foreignKey("customer_id", "customer", "customer_id").build(),
                table("invoice").foreignKey("customer_id", "customer", "customer_id").build()));

    @Test
    @DisplayName("関連を向きを問わずたどり、最短の経路を、たどる順のテーブルと関連で返す")
    void findsShortestPath() {
      final JoinPaths found = paths("audit_log", "department", 4, 5);

      assertEquals(List.of("audit_log -> employee -> department"), describe(found));
      assertEquals(
          List.of(RelationKind.LOGICAL_RELATION, RelationKind.FOREIGN_KEY),
          found.paths().get(0).relations().stream().map(Relation::kind).toList());
      assertFalse(found.hasMore());
    }

    @Test
    @DisplayName("同じ長さの経路が複数ある場合はすべて返し、上限を超える分はhasMoreで示す")
    void returnsAllShortestPaths() {
      assertEquals(
          List.of("assignment -> employee -> department", "assignment -> project -> department"),
          describe(paths("assignment", "department", 4, 5)));

      final JoinPaths limited = paths("assignment", "department", 4, 1);
      assertEquals(1, limited.paths().size());
      assertTrue(limited.hasMore());
    }

    @Test
    @DisplayName("上限の長さ以内でつながらない場合・スナップショットに無いテーブルを経由しないとつながらない場合は空を返す")
    void returnsEmptyWhenUnreachable() {
      assertTrue(paths("audit_log", "project", 2, 5).paths().isEmpty());
      assertEquals(
          List.of(
              "audit_log -> employee -> department -> project",
              "audit_log -> employee -> assignment -> project"),
          describe(paths("audit_log", "project", 3, 5)));
      assertTrue(paths("orders", "invoice", 6, 5).paths().isEmpty());
    }

    private JoinPaths paths(String from, String to, int maxLength, int limit) {
      return catalog.relations().joinPaths(find(from), find(to), maxLength, limit);
    }

    private TableEntry find(String name) {
      return Lookups.found(catalog.tables().lookup(ObjectReference.of(null, null, name)));
    }

    private static List<String> describe(JoinPaths found) {
      return found.paths().stream()
          .map(path -> String.join(" -> ", path.tables().stream().map(ObjectKey::name).toList()))
          .toList();
    }
  }

  @Nested
  @DisplayName("関連のたどり")
  class RelatedTablesTest {

    /**
     * department ← employee → parking_spot、employee → employee（自己参照）、audit_log ⇢
     * employee（論理）、assignment → employee・project
     */
    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("department").build(),
                table("parking_spot").build(),
                table("employee")
                    .foreignKey("department_id", "department", "department_id")
                    .foreignKey("manager_id", "employee", "employee_id")
                    .foreignKey("parking_spot_id", "parking_spot", "parking_spot_id")
                    .build(),
                table("audit_log").logicalRelation("record_id", "employee", "employee_id").build(),
                table("project").build(),
                table("assignment")
                    .foreignKey("employee_id", "employee", "employee_id")
                    .foreignKey("project_id", "project", "project_id")
                    .build()));

    @Test
    @DisplayName("参照先へ向かう関連・参照元から来る関連の順に返し、自己参照は1回だけ返す")
    void returnsBothDirections() {
      final RelatedTables related = related("employee", 1, Direction.BOTH);

      assertEquals(
          List.of(
              "employee.department_id->department FOREIGN_KEY 1",
              "employee.manager_id->employee FOREIGN_KEY 1",
              "employee.parking_spot_id->parking_spot FOREIGN_KEY 1",
              "audit_log.record_id->employee LOGICAL_RELATION 1",
              "assignment.employee_id->employee FOREIGN_KEY 1"),
          describe(related));
      assertEquals(
          List.of("employee", "department", "parking_spot", "audit_log", "assignment"),
          related.tables().stream().map(table -> table.key().name()).toList());
    }

    @Test
    @DisplayName("向きを指定すると、その向きの関連だけをたどる")
    void followsDirection() {
      assertEquals(
          List.of(
              "employee.department_id->department FOREIGN_KEY 1",
              "employee.manager_id->employee FOREIGN_KEY 1",
              "employee.parking_spot_id->parking_spot FOREIGN_KEY 1"),
          describe(related("employee", 1, Direction.OUTGOING)));
      assertEquals(
          List.of(
              "employee.manager_id->employee FOREIGN_KEY 1",
              "audit_log.record_id->employee LOGICAL_RELATION 1",
              "assignment.employee_id->employee FOREIGN_KEY 1"),
          describe(related("employee", 1, Direction.INCOMING)));
    }

    @Test
    @DisplayName("段数を増やすと、たどった先のテーブルの関連も段数付きで返す")
    void followsDepth() {
      final List<String> relations = describe(related("department", 2, Direction.BOTH));

      assertEquals("employee.department_id->department FOREIGN_KEY 1", relations.get(0));
      assertTrue(relations.contains("assignment.employee_id->employee FOREIGN_KEY 2"));
      assertTrue(relations.contains("audit_log.record_id->employee LOGICAL_RELATION 2"));
      assertTrue(
          relations.stream().noneMatch(relation -> relation.startsWith("assignment.project_id")),
          "3段目（assignment → project）はたどらない");
    }

    @Test
    @DisplayName("参照先がスナップショットに無いテーブルは、関連は返すがその先はたどらない")
    void reportsMissingTables() {
      final SchemaCatalog withMissing =
          TestCatalogs.of(
              List.of(
                  table("orders").foreignKey("customer_id", "customer", "customer_id").build()));
      final TableEntry orders = withMissing.tables().all().get(0);

      final RelatedTables related =
          withMissing.relations().relatedTables(orders, 3, Direction.BOTH);

      assertEquals(List.of("orders.customer_id->customer FOREIGN_KEY 1"), describe(related));
      assertEquals(List.of(new ObjectKey("testdb", "sample", "customer")), related.missingTables());
      assertEquals(List.of(orders), related.tables());
    }

    @Test
    @DisplayName("参照先のスキーマが未設定の関連は、参照元と同じスキーマとみなす")
    void defaultsReferenceSchemaToOwnSchema() {
      final SchemaCatalog sameSchema =
          TestCatalogs.of(
              List.of(
                  table("customer").build(),
                  table("orders")
                      .foreignKey(
                          new RelationEntry(
                              "fk",
                              List.of("customer_id"),
                              null,
                              "customer",
                              List.of("customer_id"),
                              null))
                      .build()));

      final RelatedTables related =
          sameSchema
              .relations()
              .relatedTables(sameSchema.tables().all().get(0), 1, Direction.INCOMING);

      assertEquals(List.of("orders.customer_id->customer FOREIGN_KEY 1"), describe(related));
    }

    private RelatedTables related(String name, int depth, Direction direction) {
      final TableEntry start =
          Lookups.found(catalog.tables().lookup(ObjectReference.of(null, null, name)));
      return catalog.relations().relatedTables(start, depth, direction);
    }
  }

  @Nested
  @DisplayName("関連の数")
  class RelationCountsTest {

    /**
     * department ← employee → parking_spot、employee → employee（自己参照）、audit_log ⇢
     * employee（論理）、assignment → employee（2つの外部キー）・project、employee → other.outside（スナップショットに無い）
     */
    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("department").build(),
                table("parking_spot").build(),
                table("employee")
                    .foreignKey("department_id", "department", "department_id")
                    .foreignKey("manager_id", "employee", "employee_id")
                    .foreignKey("parking_spot_id", "parking_spot", "parking_spot_id")
                    .foreignKey(
                        new RelationEntry(
                            "employee_outside_fkey",
                            List.of("outside_id"),
                            "other",
                            "outside",
                            List.of("outside_id"),
                            "ONE_TO_MANY"))
                    .build(),
                table("audit_log").logicalRelation("record_id", "employee", "employee_id").build(),
                table("project").build(),
                table("assignment")
                    .foreignKey("employee_id", "employee", "employee_id")
                    .foreignKey("approver_id", "employee", "employee_id")
                    .foreignKey("project_id", "project", "project_id")
                    .build()));

    @Test
    @DisplayName("参照元・参照先をテーブル単位で数え、自己参照とスナップショットに無いテーブルは数えない")
    void countsDistinctTablesExcludingSelfAndMissing() {
      assertEquals(new RelationCounts(2, 2, 2), countsOf(catalog, "employee"));
      assertEquals(new RelationCounts(0, 2, 0), countsOf(catalog, "assignment"));
      assertEquals(new RelationCounts(0, 1, 0), countsOf(catalog, "audit_log"));
    }

    @Test
    @DisplayName("影響範囲は、参照元を間接的にたどって届くテーブルも数える")
    void impactFollowsIncomingTransitively() {
      assertEquals(new RelationCounts(1, 0, 3), countsOf(catalog, "department"));
    }

    @Test
    @DisplayName("影響範囲は、参照元を3段までしかたどらない")
    void impactStopsAtDepthLimit() {
      final SchemaCatalog chain =
          TestCatalogs.of(
              List.of(
                  table("t0").build(),
                  table("t1").foreignKey("t0_id", "t0", "id").build(),
                  table("t2").foreignKey("t1_id", "t1", "id").build(),
                  table("t3").foreignKey("t2_id", "t2", "id").build(),
                  table("t4").foreignKey("t3_id", "t3", "id").build()));

      assertEquals(3, countsOf(chain, "t0").impact());
      assertEquals(3, countsOf(chain, "t1").impact());
    }

    @Test
    @DisplayName("関連の数の多い順に並べ、同数はテーブル名の順にする。関連の無いテーブルも末尾に含める")
    void listsTablesByCounts() {
      assertEquals(
          List.of("department", "parking_spot", "employee", "project", "assignment", "audit_log"),
          names(catalog.tables().list(TableFilter.ALL, TableOrder.IMPACT)));
      assertEquals(
          List.of("employee", "department", "parking_spot", "project", "assignment", "audit_log"),
          names(catalog.tables().list(TableFilter.ALL, TableOrder.INCOMING)));
      assertEquals(
          List.of("assignment", "employee", "audit_log", "department", "parking_spot", "project"),
          names(catalog.tables().list(TableFilter.ALL, TableOrder.OUTGOING)));
    }

    private static RelationCounts countsOf(SchemaCatalog catalog, String name) {
      return catalog
          .relations()
          .counts(
              catalog.tables().all().stream()
                  .filter(t -> t.key().name().equals(name))
                  .findFirst()
                  .orElseThrow());
    }

    private static List<String> names(List<TableEntry> tables) {
      return tables.stream().map(table -> table.key().name()).toList();
    }
  }

  @Nested
  @DisplayName("ER図の範囲")
  class DiagramScopeTest {

    private final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                table("customer").build(),
                table("orders")
                    .foreignKey("customer_id", "customer", "customer_id")
                    .foreignKey("store_id", "store", "store_id")
                    .foreignKey("ghost_id", "ghost", "ghost_id")
                    .build(),
                table("order_line")
                    .foreignKey("order_id", "orders", "order_id")
                    .logicalRelation("item_id", "item", "item_id")
                    .build(),
                table("store").build(),
                table("memo").build()));

    @Test
    @DisplayName("観点の所属テーブル同士の関連だけを描き、観点の外・スナップショットに無いテーブルへの関連は含めない")
    void drawsRelationsAmongViewpointTables() {
      final DiagramScope scope =
          catalog
              .relations()
              .among(
                  viewpoint("order_line", "orders", "customer", "memo", "ghost", "orders")
                      .tables());

      assertEquals(
          List.of("order_line", "orders", "customer", "memo"),
          scope.tables().stream().map(table -> table.key().name()).toList());
      assertEquals(
          List.of("order_line->orders", "orders->customer"),
          scope.relations().stream()
              .map(relation -> relation.from().name() + "->" + relation.to().name())
              .toList());
      assertEquals(List.of(new ObjectKey("testdb", "sample", "ghost")), scope.missingTables());
      assertEquals(
          List.of("order_line", "orders", "customer", "memo"),
          scope.nodes().stream().map(ObjectKey::name).toList());
    }

    @Test
    @DisplayName("関連をたどった範囲では、スナップショットに無い参照先も関連の順に箱として描く")
    void nodesIncludeMissingReferenceTables() {
      final TableEntry orderLine =
          Lookups.found(catalog.tables().lookup(ObjectReference.of(null, null, "order_line")));

      final DiagramScope scope =
          DiagramScope.of(catalog.relations().relatedTables(orderLine, 2, Direction.OUTGOING));

      assertEquals(
          List.of("order_line", "orders", "customer", "store", "item", "ghost"),
          scope.nodes().stream().map(ObjectKey::name).toList());
      assertEquals(
          List.of("item", "ghost"), scope.missingTables().stream().map(ObjectKey::name).toList());
    }

    private static ViewpointEntry viewpoint(String... tables) {
      return new ViewpointEntry(
          "testdb",
          "order",
          "受注",
          "",
          Arrays.stream(tables).map(name -> new ObjectKey("testdb", "sample", name)).toList());
    }
  }

  /** {@code 参照元.カラム->参照先 種別 段数}の形の一覧 */
  private static List<String> describe(RelatedTables related) {
    return related.relations().stream()
        .map(
            found ->
                found.relation().from().name()
                    + "."
                    + String.join(",", found.relation().fromColumns())
                    + "->"
                    + found.relation().to().name()
                    + " "
                    + found.relation().kind()
                    + " "
                    + found.depth())
        .toList();
  }
}
