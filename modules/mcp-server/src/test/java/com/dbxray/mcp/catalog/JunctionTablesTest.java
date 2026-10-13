package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.JunctionFixtures.ASSIGNMENT;
import static com.dbxray.mcp.catalog.JunctionFixtures.EMPLOYEE;
import static com.dbxray.mcp.catalog.JunctionFixtures.PROJECT;
import static com.dbxray.mcp.catalog.JunctionFixtures.assignment;
import static com.dbxray.mcp.catalog.JunctionFixtures.catalogOf;
import static com.dbxray.mcp.catalog.JunctionFixtures.find;
import static com.dbxray.mcp.catalog.JunctionFixtures.junction;
import static com.dbxray.mcp.catalog.JunctionFixtures.key;
import static com.dbxray.mcp.catalog.JunctionFixtures.role;
import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link JunctionTables}による多対多の関係・ER上の区分での絞り込みと、{@link ErRole}のテスト */
class JunctionTablesTest {

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
      final TableFilter junctions = TableFilter.ALL.withJunctionsOnly(catalog.junctions());

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
      final TableFilter junctions = TableFilter.ALL.withJunctionsOnly(catalog.junctions());

      assertEquals(1, catalog.tables().search(SearchQuery.of("project"), junctions, 10).total());
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
}
