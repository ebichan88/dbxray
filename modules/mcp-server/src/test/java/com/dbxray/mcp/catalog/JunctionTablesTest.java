package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link JunctionTables}・{@link JunctionTable}・{@link ErRole}のテスト */
class JunctionTablesTest {

  private static final ObjectKey PROJECT = key("project");
  private static final ObjectKey EMPLOYEE = key("employee");
  private static final ObjectKey ASSIGNMENT = key("project_assignment");

  @Nested
  @DisplayName("中間テーブルの判定")
  class Detection {

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

      assertTrue(catalog.junctions().of(find(catalog, "project_assignment")).isPresent());
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
    @DisplayName("外部キーの列が互いに重なる場合は中間テーブルではない")
    void overlappingForeignKeysAreNotJunction() {
      final SchemaCatalog catalog =
          catalogOf(
              table("overlapping")
                  .primaryKey("a")
                  .primaryKey("b")
                  .primaryKey("c")
                  .foreignKey(
                      new RelationEntry(
                          "fk_ab", List.of("a", "b"), "sample", "project", List.of("x", "y"), ""))
                  .foreignKey(
                      new RelationEntry(
                          "fk_bc", List.of("b", "c"), "sample", "employee", List.of("y", "z"), ""))
                  .build());

      assertNotJunction(catalog, "overlapping");
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
  }

  @Nested
  @DisplayName("多対多の関係")
  class ManyToManyRelations {

    @Test
    @DisplayName("中間テーブルがつなぐ2つのテーブルは、互いに中間テーブルを介した多対多の相手になる")
    void partnersOfTwoParents() {
      final SchemaCatalog catalog = catalogOf(assignment().build());

      assertEquals(
          List.of(new ManyToMany(EMPLOYEE, ASSIGNMENT)),
          catalog.junctions().manyToMany(find(catalog, "project")));
      assertEquals(
          List.of(new ManyToMany(PROJECT, ASSIGNMENT)),
          catalog.junctions().manyToMany(find(catalog, "employee")));
      assertTrue(
          catalog.junctions().manyToMany(find(catalog, "project_assignment")).isEmpty(),
          "中間テーブル自身は多対多の相手を持たない");
    }

    @Test
    @DisplayName("3つのテーブルをつなぐ中間テーブルは、他の2つをそれぞれ相手にする")
    void partnersOfThreeParents() {
      final SchemaCatalog catalog =
          catalogOf(
              table("skill").primaryKey("skill_id").build(),
              table("project_member")
                  .primaryKey("project_id")
                  .primaryKey("employee_id")
                  .primaryKey("skill_id")
                  .foreignKey("project_id", "project", "project_id")
                  .foreignKey("employee_id", "employee", "employee_id")
                  .foreignKey("skill_id", "skill", "skill_id")
                  .build());

      assertEquals(
          List.of(
              new ManyToMany(EMPLOYEE, key("project_member")),
              new ManyToMany(key("skill"), key("project_member"))),
          catalog.junctions().manyToMany(find(catalog, "project")));
      assertEquals(3, junction(catalog, "project_member").parents().size());
    }

    @Test
    @DisplayName("相手は、相手のテーブル・中間テーブルの順（DB名・スキーマ名・テーブル名の順）に並べる")
    void sortsPartners() {
      final SchemaCatalog catalog =
          catalogOf(
              table("tag").primaryKey("tag_id").build(),
              table("project_tag")
                  .primaryKey("project_id")
                  .primaryKey("tag_id")
                  .foreignKey("project_id", "project", "project_id")
                  .foreignKey("tag_id", "tag", "tag_id")
                  .build(),
              assignment().build(),
              table("employee_project")
                  .primaryKey("project_id")
                  .primaryKey("employee_id")
                  .foreignKey("project_id", "project", "project_id")
                  .foreignKey("employee_id", "employee", "employee_id")
                  .build());

      assertEquals(
          List.of(
              new ManyToMany(EMPLOYEE, key("employee_project")),
              new ManyToMany(EMPLOYEE, ASSIGNMENT),
              new ManyToMany(key("tag"), key("project_tag"))),
          catalog.junctions().manyToMany(find(catalog, "project")));
    }

    @Test
    @DisplayName("同じテーブルを2つの外部キーで参照する中間テーブルは、そのテーブル自身を相手にする（自己多対多）")
    void selfManyToMany() {
      final SchemaCatalog catalog =
          catalogOf(
              table("employee_friend")
                  .primaryKey("employee_id")
                  .primaryKey("friend_id")
                  .foreignKey("employee_id", "employee", "employee_id")
                  .foreignKey("friend_id", "employee", "employee_id")
                  .build());

      assertEquals(List.of(EMPLOYEE), junction(catalog, "employee_friend").parents());
      assertEquals(
          List.of(new ManyToMany(EMPLOYEE, key("employee_friend"))),
          catalog.junctions().manyToMany(find(catalog, "employee")));
    }

    @Test
    @DisplayName("スナップショットに無い親テーブルとは多対多の相手にしないが、中間テーブルの判定は変えない")
    void ignoresParentsMissingFromSnapshot() {
      final SchemaCatalog catalog =
          TestCatalogs.of(
              List.of(
                  table("project").primaryKey("project_id").build(),
                  table("project_assignment")
                      .primaryKey("project_id")
                      .primaryKey("customer_id")
                      .foreignKey("project_id", "project", "project_id")
                      .foreignKey("customer_id", "customer", "customer_id")
                      .build()));

      assertEquals(ErRole.JUNCTION, role(catalog, "project_assignment"));
      assertTrue(catalog.junctions().manyToMany(find(catalog, "project")).isEmpty());
    }

    @Test
    @DisplayName("DBが異なれば同じ名前のテーブルも別のテーブルとして扱う")
    void distinguishesDatabases() {
      final SchemaCatalog catalog =
          TestCatalogs.of(
              List.of(
                  table("testdb", "sample", "project").primaryKey("project_id").build(),
                  table("testdb", "sample", "employee").primaryKey("employee_id").build(),
                  table("other", "sample", "project").primaryKey("project_id").build(),
                  table("other", "sample", "project_assignment")
                      .primaryKey("project_id")
                      .primaryKey("employee_id")
                      .foreignKey("project_id", "project", "project_id")
                      .foreignKey("employee_id", "employee", "employee_id")
                      .build()));

      final TableEntry otherProject = catalog.tables().all().get(2);
      assertEquals("other", otherProject.key().database());
      assertTrue(
          catalog.junctions().manyToMany(otherProject).isEmpty(),
          "otherのproject_assignmentは、otherに無いemployee（testdbにはある）とは多対多にならない");
    }
  }

  @Nested
  @DisplayName("ER上の区分での絞り込みと区分の値")
  class RoleFilterAndValue {

    private final SchemaCatalog catalog = catalogOf(assignment().build());

    @Test
    @DisplayName("ER上の区分で絞り込むと、中間テーブルだけを一覧にする")
    void filtersByErRole() {
      final TableFilter junctions = TableFilter.ALL.withErRole(ErRole.JUNCTION, catalog.junctions());

      assertEquals(
          List.of("project_assignment"),
          catalog.tables().list(junctions, TableOrder.NAME).stream()
              .map(table -> table.key().name())
              .toList());
      assertEquals(
          4, catalog.tables().list(TableFilter.ALL, TableOrder.NAME).size(), "条件なしでは絞り込まない");
    }

    @Test
    @DisplayName("ER上の区分での絞り込みは、検索にも効く")
    void filtersSearchByErRole() {
      final TableFilter junctions = TableFilter.ALL.withErRole(ErRole.JUNCTION, catalog.junctions());

      assertEquals(
          1, catalog.tables().search(SearchQuery.of("project"), junctions, 10).total());
    }

    @Test
    @DisplayName("区分の値は、ツールの引数・結果で使う小文字の値で引ける。空文字・未知の値は空")
    void resolvesValues() {
      assertEquals(List.of("junction"), ErRole.allValues());
      assertEquals(ErRole.JUNCTION, ErRole.of("junction").orElseThrow());
      assertTrue(ErRole.of("").isEmpty());
      assertTrue(ErRole.of("subtype").isEmpty());
    }

    @Test
    @DisplayName("通常のテーブルはER上の区分を持たない")
    void ordinaryTableHasNoRole() {
      assertFalse(catalog.junctions().roleOf(find(catalog, "project")).isPresent());
    }
  }

  /** project・employeeと、2つを主キーの列で参照する中間テーブルproject_assignment */
  private static TestTables assignment() {
    return table("project_assignment")
        .primaryKey("project_id")
        .primaryKey("employee_id")
        .foreignKey("project_id", "project", "project_id")
        .foreignKey("employee_id", "employee", "employee_id");
  }

  private static SchemaCatalog catalogOf(TableEntry... tables) {
    return TestCatalogs.of(
        Stream.concat(
                Stream.of(
                    table("project").primaryKey("project_id").build(),
                    table("employee").primaryKey("employee_id").build(),
                    table("warehouse_zone")
                        .primaryKey("warehouse_code")
                        .primaryKey("zone_code")
                        .build()),
                Stream.of(tables))
            .toList());
  }

  private static ObjectKey key(String name) {
    return new ObjectKey("testdb", "sample", name);
  }

  private static TableEntry find(SchemaCatalog catalog, String name) {
    return catalog.tables().all().stream()
        .filter(table -> table.key().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static JunctionTable junction(SchemaCatalog catalog, String name) {
    return catalog.junctions().of(find(catalog, name)).orElseThrow();
  }

  private static ErRole role(SchemaCatalog catalog, String name) {
    return catalog.junctions().roleOf(find(catalog, name)).orElseThrow();
  }

  private static void assertNotJunction(SchemaCatalog catalog, String name) {
    assertTrue(catalog.junctions().of(find(catalog, name)).isEmpty(), name);
    assertTrue(catalog.junctions().roleOf(find(catalog, name)).isEmpty(), name);
  }
}
