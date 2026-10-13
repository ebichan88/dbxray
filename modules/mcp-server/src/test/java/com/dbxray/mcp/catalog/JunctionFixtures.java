package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;

import java.util.List;
import java.util.stream.Stream;

/** 中間テーブルの判定・多対多のテストで共通に使う、テーブルとカタログの組み立て */
final class JunctionFixtures {

  static final ObjectKey PROJECT = key("project");
  static final ObjectKey EMPLOYEE = key("employee");
  static final ObjectKey ASSIGNMENT = key("project_assignment");

  private JunctionFixtures() {}

  /** project・employeeと、2つを主キーの列で参照する中間テーブルproject_assignment */
  static TestTables assignment() {
    return table("project_assignment")
        .primaryKey("project_id")
        .primaryKey("employee_id")
        .foreignKey("project_id", "project", "project_id")
        .foreignKey("employee_id", "employee", "employee_id");
  }

  /** 参照先の列名を参照元と同じにした外部キー（列の組を参照元とする関連） */
  static RelationEntry fk(String referenceTable, String cardinality, String... columns) {
    return new RelationEntry(
        "fk_" + referenceTable,
        List.of(columns),
        "sample",
        referenceTable,
        List.of(columns),
        cardinality);
  }

  /** project・employee・warehouse_zoneに、指定したテーブルを加えたカタログ */
  static SchemaCatalog catalogOf(TableEntry... tables) {
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

  static ObjectKey key(String name) {
    return new ObjectKey("testdb", "sample", name);
  }

  static TableEntry find(SchemaCatalog catalog, String name) {
    return catalog.tables().all().stream()
        .filter(table -> table.key().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  static JunctionTable junction(SchemaCatalog catalog, String name) {
    return catalog.junctions().find(find(catalog, name)).orElseThrow();
  }

  static ErRole role(SchemaCatalog catalog, String name) {
    return catalog.junctions().roleOf(find(catalog, name)).orElseThrow();
  }
}
