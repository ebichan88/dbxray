package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.JunctionFixtures.ASSIGNMENT;
import static com.dbxray.mcp.catalog.JunctionFixtures.EMPLOYEE;
import static com.dbxray.mcp.catalog.JunctionFixtures.PROJECT;
import static com.dbxray.mcp.catalog.JunctionFixtures.assignment;
import static com.dbxray.mcp.catalog.JunctionFixtures.catalogOf;
import static com.dbxray.mcp.catalog.JunctionFixtures.find;
import static com.dbxray.mcp.catalog.JunctionFixtures.fk;
import static com.dbxray.mcp.catalog.JunctionFixtures.junction;
import static com.dbxray.mcp.catalog.JunctionFixtures.key;
import static com.dbxray.mcp.catalog.JunctionFixtures.role;
import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link JunctionDetector}による中間テーブルの判定のテスト（{@link SchemaCatalog#junctions}を通して確かめる） */
class JunctionDetectorTest {

  @Test
  @DisplayName("主キーが2本の外部キーの列だけで構成されるテーブルは中間テーブルで、主キー以外の列（属性）があってもよい")
  void detectsCompositePrimaryKeyOfTwoForeignKeys() {
    final SchemaCatalog catalog = catalogOf(assignment().column("role", "役割", null).build());

    final JunctionTable junction = junction(catalog, "project_assignment");

    assertEquals(ASSIGNMENT, junction.key());
    assertEquals(
        List.of(RelationKind.FOREIGN_KEY, RelationKind.FOREIGN_KEY),
        junction.links().stream().map(Relation::kind).toList());
    assertEquals(List.of(PROJECT, EMPLOYEE), junction.parents());
    assertEquals(ErRole.JUNCTION, role(catalog, "project_assignment"));
  }

  @Test
  @DisplayName("他のテーブルから参照されている中間テーブルも、構造が同じなら中間テーブルとする")
  void detectsJunctionReferencedByOtherTables() {
    final SchemaCatalog catalog =
        catalogOf(
            assignment().build(),
            table("assignment_note")
                .primaryKey("note_id")
                .foreignKey("note_id", "project_assignment", "project_id")
                .build());

    assertTrue(catalog.junctions().find(find(catalog, "project_assignment")).isPresent());
  }

  @Test
  @DisplayName("外部キーの代わりに論理リレーションで主キーが構成されていても中間テーブルとする")
  void detectsJunctionOfLogicalRelations() {
    final SchemaCatalog catalog =
        catalogOf(
            table("project_assignment")
                .primaryKey("project_id")
                .primaryKey("employee_id")
                .logicalRelation("project_id", "project", "project_id")
                .logicalRelation("employee_id", "employee", "employee_id")
                .build());

    assertEquals(
        List.of(RelationKind.LOGICAL_RELATION, RelationKind.LOGICAL_RELATION),
        junction(catalog, "project_assignment").links().stream().map(Relation::kind).toList());
  }

  @Test
  @DisplayName("外部キーと論理リレーションの混在も構成する関連とみなし、同じ列の組の関連は1本にまとめて外部キーを優先する")
  void mergesRelationsOnSameColumns() {
    final SchemaCatalog catalog =
        catalogOf(
            table("project_assignment")
                .primaryKey("project_id")
                .primaryKey("employee_id")
                .foreignKey("project_id", "project", "project_id")
                .logicalRelation("project_id", "project", "project_id")
                .logicalRelation("employee_id", "employee", "employee_id")
                .build());

    assertEquals(
        List.of(RelationKind.FOREIGN_KEY, RelationKind.LOGICAL_RELATION),
        junction(catalog, "project_assignment").links().stream().map(Relation::kind).toList());
  }

  @Test
  @DisplayName("同じ列の組の関連が複数あっても1本と数えるため、他に関連が無ければ中間テーブルではない")
  void duplicateRelationsOnSameColumnsCountOnce() {
    final SchemaCatalog catalog =
        catalogOf(
            table("project_assignment")
                .primaryKey("project_id")
                .primaryKey("employee_id")
                .foreignKey("project_id", "project", "project_id")
                .logicalRelation("project_id", "project", "project_id")
                .build());

    assertNotJunction(catalog, "project_assignment");
  }

  @Test
  @DisplayName("カラム名は大文字小文字を区別せずに突き合わせる")
  void comparesColumnNamesCaseInsensitively() {
    final SchemaCatalog catalog =
        catalogOf(
            table("PROJECT_ASSIGNMENT")
                .primaryKey("PROJECT_ID")
                .primaryKey("EMPLOYEE_ID")
                .foreignKey("project_id", "project", "project_id")
                .foreignKey("Employee_Id", "employee", "employee_id")
                .build());

    assertEquals(2, junction(catalog, "PROJECT_ASSIGNMENT").links().size());
  }

  @Test
  @DisplayName("主キーが外部キー1本の列だけのテーブル（1対1の拡張）は中間テーブルではない")
  void primaryKeyOfSingleForeignKeyIsNotJunction() {
    final SchemaCatalog catalog =
        catalogOf(
            table("employee_profile")
                .primaryKey("employee_id")
                .foreignKey("employee_id", "employee", "employee_id")
                .build());

    assertNotJunction(catalog, "employee_profile");
  }

  @Test
  @DisplayName("複合主キー全体を覆う複合外部キー1本のテーブルは、関連が1本なので中間テーブルではない")
  void singleCompositeForeignKeyIsNotJunction() {
    final SchemaCatalog catalog =
        catalogOf(
            table("shipment")
                .primaryKey("warehouse_code")
                .primaryKey("zone_code")
                .foreignKey(
                    new RelationEntry(
                        "shipment_zone_fkey",
                        List.of("warehouse_code", "zone_code"),
                        "sample",
                        "warehouse_zone",
                        List.of("warehouse_code", "zone_code"),
                        "ONE_TO_MANY"))
                .build());

    assertNotJunction(catalog, "shipment");
  }

  @Test
  @DisplayName("複合外部キーと別の外部キーで主キーを過不足なく覆う場合は、複合外部キーも1本として中間テーブルとする")
  void compositeForeignKeyCountsAsOneLink() {
    final SchemaCatalog catalog =
        catalogOf(
            table("zone_staff")
                .primaryKey("warehouse_code")
                .primaryKey("zone_code")
                .primaryKey("employee_id")
                .foreignKey(
                    new RelationEntry(
                        "zone_staff_zone_fkey",
                        List.of("warehouse_code", "zone_code"),
                        "sample",
                        "warehouse_zone",
                        List.of("warehouse_code", "zone_code"),
                        "ONE_TO_MANY"))
                .foreignKey("employee_id", "employee", "employee_id")
                .build());

    assertEquals(
        List.of(key("warehouse_zone"), EMPLOYEE), junction(catalog, "zone_staff").parents());
  }

  @Test
  @DisplayName("主キーに外部キーでない列が含まれるテーブルは中間テーブルではない")
  void primaryKeyWithNonForeignKeyColumnIsNotJunction() {
    final SchemaCatalog catalog =
        catalogOf(
            table("project_assignment")
                .primaryKey("project_id")
                .primaryKey("employee_id")
                .primaryKey("assigned_at")
                .foreignKey("project_id", "project", "project_id")
                .foreignKey("employee_id", "employee", "employee_id")
                .build());

    assertNotJunction(catalog, "project_assignment");
  }

  @Test
  @DisplayName("主キーの外にある列の外部キーは、中間テーブルを構成する関連に数えない")
  void foreignKeysOutsidePrimaryKeyAreNotLinks() {
    final SchemaCatalog catalog =
        catalogOf(
            table("order_line")
                .primaryKey("order_id")
                .primaryKey("line_no")
                .column("product_id")
                .foreignKey("order_id", "project", "project_id")
                .foreignKey("product_id", "employee", "employee_id")
                .build());

    assertNotJunction(catalog, "order_line");
  }

  @Test
  @DisplayName("複数の関連が列を共有していても（テナントIDを含む複合キー）、それぞれに固有の列があれば中間テーブルとする")
  void detectsRelationsSharingColumns() {
    final SchemaCatalog catalog =
        catalogOf(
            table("a").primaryKey("a_id").build(),
            table("b").primaryKey("b_id").build(),
            table("tenant_member")
                .primaryKey("tenant_id")
                .primaryKey("a_id")
                .primaryKey("b_id")
                .foreignKey(fk("a", "ONE_TO_MANY", "tenant_id", "a_id"))
                .foreignKey(fk("b", "ONE_TO_MANY", "tenant_id", "b_id"))
                .build());

    assertEquals(List.of(key("a"), key("b")), junction(catalog, "tenant_member").parents());
    assertEquals(
        List.of(new ManyToMany(key("b"), key("tenant_member"))),
        catalog.junctions().manyToMany(find(catalog, "a")));
  }

  @Test
  @DisplayName("主キーに含まれるだけの余分な関連（近道の外部キー）があっても、主キーを過不足なく覆う関連の組を取り出す")
  void ignoresRedundantRelationInsidePrimaryKey() {
    final SchemaCatalog catalog =
        catalogOf(
            table("order_option")
                .primaryKey("order_id")
                .primaryKey("line_no")
                .primaryKey("option_id")
                .foreignKey(fk("order_lines", "ONE_TO_MANY", "order_id", "line_no"))
                .foreignKey(fk("options", "ONE_TO_MANY", "option_id"))
                .foreignKey(fk("orders", "ONE_TO_MANY", "order_id"))
                .build());

    assertEquals(
        List.of(key("order_lines"), key("options")), junction(catalog, "order_option").parents());
  }

  @Test
  @DisplayName("主キー全体を覆う関連が、その一部を覆う関連を含む場合は、含まれる側が余分で関連が1本になるため中間テーブルではない")
  void relationContainingAnotherIsNotJunction() {
    final SchemaCatalog catalog =
        catalogOf(
            table("overlapping")
                .primaryKey("a")
                .primaryKey("b")
                .primaryKey("c")
                .foreignKey(fk("x", "ONE_TO_MANY", "a", "b", "c"))
                .foreignKey(fk("y", "ONE_TO_MANY", "a", "b"))
                .build());

    assertNotJunction(catalog, "overlapping");
  }

  @Test
  @DisplayName("主キーを覆う組み合わせが複数あれば、関連の数が最も多いものを採る（複合外部キーより列ごとの外部キー）")
  void prefersCoverWithMostRelations() {
    final SchemaCatalog catalog =
        catalogOf(
            table("wide")
                .primaryKey("a")
                .primaryKey("b")
                .primaryKey("c")
                .foreignKey(fk("ab", "ONE_TO_MANY", "a", "b"))
                .foreignKey(fk("pa", "ONE_TO_MANY", "a"))
                .foreignKey(fk("pb", "ONE_TO_MANY", "b"))
                .foreignKey(fk("pc", "ONE_TO_MANY", "c"))
                .build());

    assertEquals(List.of(key("pa"), key("pb"), key("pc")), junction(catalog, "wide").parents());
  }

  @Test
  @DisplayName("関連の数が同じ組み合わせが複数あれば、関連の並びで先のものを採る")
  void prefersEarlierCoverOnTie() {
    final SchemaCatalog catalog =
        catalogOf(
            table("tie")
                .primaryKey("a")
                .primaryKey("b")
                .primaryKey("c")
                .foreignKey(fk("x", "ONE_TO_MANY", "a", "b"))
                .foreignKey(fk("y", "ONE_TO_MANY", "b", "c"))
                .foreignKey(fk("z", "ONE_TO_MANY", "a", "c"))
                .build());

    assertEquals(List.of(key("x"), key("y")), junction(catalog, "tie").parents());
  }

  @Test
  @DisplayName("1対1（一意）の関連は、多対多をつなぐ関連に数えない")
  void oneToOneRelationIsNotLink() {
    for (final String oneToOne : List.of("ONE_TO_ONE", "OPTIONAL_ONE_TO_ONE")) {
      final SchemaCatalog catalog =
          catalogOf(
              table("unique_member")
                  .primaryKey("a")
                  .primaryKey("b")
                  .foreignKey(fk("pa", oneToOne, "a"))
                  .foreignKey(fk("pb", "ONE_TO_MANY", "b"))
                  .build());

      assertNotJunction(catalog, "unique_member");
    }
  }

  @Test
  @DisplayName("多重度が未設定の関連は、1対1とみなさず数える")
  void relationWithoutCardinalityIsLink() {
    final SchemaCatalog catalog =
        catalogOf(
            table("unknown_cardinality")
                .primaryKey("a")
                .primaryKey("b")
                .foreignKey(fk("pa", "", "a"))
                .foreignKey(fk("pb", "", "b"))
                .build());

    assertEquals(2, junction(catalog, "unknown_cardinality").links().size());
  }

  @Test
  @DisplayName("同じ列の組の外部キーが1対1なら、同じ列の論理リレーション（既定の1対多）があっても数えない")
  void oneToOneForeignKeyWinsOverLogicalRelationOnSameColumns() {
    final SchemaCatalog catalog =
        catalogOf(
            table("unique_member")
                .primaryKey("a")
                .primaryKey("b")
                .foreignKey(fk("pa", "ONE_TO_ONE", "a"))
                .logicalRelation("a", "pa", "a")
                .foreignKey(fk("pb", "ONE_TO_MANY", "b"))
                .build());

    assertNotJunction(catalog, "unique_member");
  }

  @Test
  @DisplayName("主キーに収まる関連が上限（MAX_CANDIDATES本）ならば判定し、上限を超えるテーブルは判定しない")
  void skipsTablesWithTooManyCandidates() {
    final TableEntry atLimit = tableWithCandidates(JunctionDetector.MAX_CANDIDATES);
    final TableEntry overLimit = tableWithCandidates(JunctionDetector.MAX_CANDIDATES + 1);

    assertTrue(catalogOf(atLimit).junctions().find(atLimit).isPresent());
    assertTrue(catalogOf(overLimit).junctions().find(overLimit).isEmpty());
  }

  @Test
  @DisplayName("主キーを持たないテーブルは中間テーブルではない")
  void tableWithoutPrimaryKeyIsNotJunction() {
    final SchemaCatalog catalog =
        catalogOf(
            table("project_assignment")
                .column("project_id")
                .column("employee_id")
                .foreignKey("project_id", "project", "project_id")
                .foreignKey("employee_id", "employee", "employee_id")
                .build());

    assertNotJunction(catalog, "project_assignment");
  }

  @Test
  @DisplayName("ビュー・マテリアライズドビューは中間テーブルにしない")
  void viewsAreNeverJunctions() {
    for (final String type : List.of("view", "materialized_view")) {
      final SchemaCatalog catalog = catalogOf(assignment().type(type).build());

      assertNotJunction(catalog, "project_assignment");
    }
  }

  /**
   * 主キー(a, b, c, d)の列の部分集合を参照元とする外部キーを{@code count}本持つテーブル<br>
   * 単独の列の外部キー(a)(b)(c)(d)を先に置く（この4本だけで主キーを過不足なく覆えるため、判定されれば中間テーブルになる）
   */
  private static TableEntry tableWithCandidates(int count) {
    final List<String> names = List.of("a", "b", "c", "d");
    final List<Integer> masks =
        IntStream.rangeClosed(1, (1 << names.size()) - 1)
            .boxed()
            .sorted(Comparator.comparingInt(Integer::bitCount).thenComparingInt(mask -> mask))
            .toList();
    final TestTables table =
        table("many").primaryKey("a").primaryKey("b").primaryKey("c").primaryKey("d");
    for (final int mask : masks.subList(0, count)) {
      final String[] columns =
          IntStream.range(0, names.size())
              .filter(bit -> (mask & (1 << bit)) != 0)
              .mapToObj(names::get)
              .toArray(String[]::new);
      table.foreignKey(fk("parent_" + mask, "ONE_TO_MANY", columns));
    }
    return table.build();
  }

  private static void assertNotJunction(SchemaCatalog catalog, String name) {
    assertTrue(catalog.junctions().find(find(catalog, name)).isEmpty(), name);
    assertTrue(catalog.junctions().roleOf(find(catalog, name)).isEmpty(), name);
  }
}
