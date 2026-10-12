package com.dbxray.mcp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.ColumnHit;
import com.dbxray.mcp.catalog.ColumnQuery;
import com.dbxray.mcp.catalog.Direction;
import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.FunctionOverloads;
import com.dbxray.mcp.catalog.JoinPaths;
import com.dbxray.mcp.catalog.Lookups;
import com.dbxray.mcp.catalog.MatchMode;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.ObjectReference;
import com.dbxray.mcp.catalog.RelatedTables;
import com.dbxray.mcp.catalog.RelationKind;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SchemaSummary;
import com.dbxray.mcp.catalog.SearchQuery;
import com.dbxray.mcp.catalog.SearchScope;
import com.dbxray.mcp.catalog.TableClusters;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TableFilter;
import com.dbxray.mcp.catalog.TriggerEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * cliが出力したサンプルのスナップショット（ベースライン）を読み込めることを確かめる契約テスト<br>
 * cliの出力形式を変えるとベースラインも出力し直すため、このテストで読み込み側の追従漏れを検知する
 */
class SampleSnapshotContractTest {

  private static SchemaCatalog catalog;

  @BeforeAll
  static void readSample() {
    catalog = new SnapshotDirectoryReader().read(Path.of(System.getProperty("sampleSnapshotDir")));
  }

  @Test
  @DisplayName("サンプルの全テーブル（ビュー・マテリアライズドビューを含む）を読み込む")
  void readsAllTables() {
    assertEquals(14, catalog.tables().all().size());
    final TableEntry employee = find("employee");
    assertEquals("従業員", employee.logicalName());
    assertEquals("table", employee.type());
    assertTrue(employee.json().startsWith("{\"schema\":\"sample\",\"name\":\"employee\""));
    assertEquals("view", find("employee_directory_view").type());
    assertEquals("materialized_view", find("project_summary_mv").type());
  }

  @Test
  @DisplayName("パーティション表は通常のテーブルとして読み込め（パーティションキーの項目は無視される）、子のパーティションは含まれない")
  void readsPartitionedTableWithoutPartitions() {
    final TableEntry attendance = find("attendance");

    assertEquals("table", attendance.type());
    assertEquals("勤怠（月次パーティション）", attendance.logicalName());
    assertTrue(attendance.json().contains("\"partitionKey\":\"RANGE (work_date)\""));
    assertTrue(
        catalog.tables().all().stream()
            .noneMatch(table -> table.key().name().startsWith("attendance_2")));
  }

  @Test
  @DisplayName("サイドカーYAML由来の説明・カラム備考も検索の対象になる")
  void searchesSidecarText() {
    assertEquals(
        List.of("department"),
        catalog.tables().search(SearchQuery.of("組織単位"), TableFilter.ALL, 10).hits().stream()
            .map(hit -> hit.table().key().name())
            .toList());
    assertTrue(
        catalog.tables().search(SearchQuery.of("変更対象のテーブル名"), TableFilter.ALL, 10).hits().stream()
            .anyMatch(hit -> hit.matchedIn().contains("column:table_name")));
  }

  @Test
  @DisplayName("外部キー（複合キー・自己参照を含む）と論理リレーションを、被参照側からもたどれる")
  void followsRelationsOfSample() {
    final RelatedTables employee =
        catalog.relations().relatedTables(find("employee"), 1, Direction.BOTH);
    final List<String> relations =
        employee.relations().stream()
            .map(
                found ->
                    found.relation().from().name()
                        + "->"
                        + found.relation().to().name()
                        + " "
                        + found.relation().kind()
                        + " "
                        + found.relation().cardinality())
            .toList();

    assertTrue(
        relations.contains("employee->department FOREIGN_KEY ONE_TO_MANY"), relations::toString);
    assertTrue(
        relations.contains("employee->employee FOREIGN_KEY OPTIONAL_ONE_TO_MANY"),
        relations::toString);
    assertTrue(
        relations.contains("audit_log->employee LOGICAL_RELATION OPTIONAL_ONE_TO_MANY"),
        relations::toString);
    assertTrue(
        relations.contains("employee_profile->employee FOREIGN_KEY ONE_TO_ONE"),
        relations::toString);
    assertTrue(
        relations.contains("project_assignment->employee FOREIGN_KEY ONE_TO_MANY"),
        relations::toString);

    final RelatedTables shipment =
        catalog.relations().relatedTables(find("shipment"), 1, Direction.OUTGOING);
    assertEquals(
        List.of("warehouse_code", "zone_code"),
        shipment.relations().get(0).relation().fromColumns());
    assertEquals(RelationKind.FOREIGN_KEY, shipment.relations().get(0).relation().kind());
    assertTrue(shipment.missingTables().isEmpty());
  }

  @Test
  @DisplayName("database.jsonのDBMS種別・メジャーバージョンと、スキーマごとのオブジェクトの数を読み込む")
  void summarizesSchemas() {
    assertEquals(
        List.of(new SchemaSummary("testdb", "PostgreSQL", 16, "sample", 11, 2, 1, 11, 2, 8, 3, 5)),
        catalog.schemas());
  }

  @Test
  @DisplayName("カラムの型・PK・NOT NULL・デフォルト値を読み込み、カラム名から逆引きできる")
  void findsColumnsOfSample() {
    final List<ColumnHit> hits =
        catalog
            .tables()
            .findColumns(ColumnQuery.of("employee_id", MatchMode.EXACT), SearchScope.ALL);

    final ColumnEntry primaryKey =
        hits.stream()
            .filter(hit -> hit.table().key().name().equals("employee"))
            .findFirst()
            .orElseThrow()
            .column();
    assertEquals("integer", primaryKey.type());
    assertTrue(primaryKey.primaryKey());
    assertTrue(primaryKey.notNull());
    assertEquals("nextval('sample.employee_employee_id_seq'::regclass)", primaryKey.defaultValue());
    assertTrue(
        hits.stream()
            .anyMatch(
                hit ->
                    hit.references().stream()
                        .anyMatch(reference -> reference.table().name().equals("employee"))),
        "外部キーで従業員を参照するカラムがある");
  }

  @Test
  @DisplayName("関数のシグネチャ（オーバーロードを含む）・シーケンスの所有カラム・型の種別・トリガーを読み込む")
  void readsOtherObjectsOfSample() {
    final FunctionOverloads calculateBonus =
        Lookups.found(
            catalog.functions().lookup(ObjectReference.of(null, null, "calculate_bonus")));
    assertEquals(
        List.of("p_salary numeric, p_rate numeric", "p_salary numeric"),
        calculateBonus.overloads().stream().map(FunctionEntry::arguments).toList());
    assertEquals("numeric", calculateBonus.overloads().get(0).result());
    assertEquals(
        "audit_log.log_id",
        Lookups.found(
                catalog.sequences().lookup(ObjectReference.of(null, null, "audit_log_log_id_seq")))
            .ownedBy());
    assertEquals(
        "ENUM",
        Lookups.found(
                catalog.types().lookup(ObjectReference.of(null, null, "employee_status_enum")))
            .category());
    final TriggerEntry audit =
        find("employee").triggers().stream()
            .filter(trigger -> trigger.name().equals("trg_employee_audit"))
            .findFirst()
            .orElseThrow();
    assertEquals("sample.log_employee_change", audit.function());
    assertEquals(List.of("INSERT", "DELETE", "UPDATE"), audit.events());
  }

  @Test
  @DisplayName("サンプルの相互参照（トリガー関数・シーケンス・型）を求められる")
  void findsCrossReferencesOfSample() {
    final FunctionOverloads logEmployeeChange =
        Lookups.found(
            catalog.functions().lookup(ObjectReference.of(null, null, "log_employee_change")));
    assertEquals(
        List.of("employee.trg_employee_audit"),
        catalog.tables().triggersCalling(logEmployeeChange).stream()
            .map(found -> found.table().key().name() + "." + found.trigger().name())
            .toList());
    assertEquals(
        List.of("audit_log.log_id"),
        catalog
            .tables()
            .columnsUsing(
                Lookups.found(
                    catalog
                        .sequences()
                        .lookup(ObjectReference.of(null, null, "audit_log_log_id_seq"))))
            .stream()
            .map(found -> found.table().key().name() + "." + found.column().name())
            .toList());
    assertTrue(
        catalog
            .tables()
            .columnsUsing(
                Lookups.found(
                    catalog.types().lookup(ObjectReference.of(null, null, "employee_status_enum"))))
            .stream()
            .anyMatch(found -> found.table().key().name().equals("employee")));
  }

  @Test
  @DisplayName("サンプルのテーブル同士をつなぐJOIN経路を探せる")
  void findsJoinPathOfSample() {
    final JoinPaths paths =
        catalog.relations().joinPaths(find("audit_log"), find("department"), 4, 5);

    assertEquals(
        List.of("audit_log", "employee", "department"),
        paths.paths().get(0).tables().stream().map(ObjectKey::name).toList());
  }

  @Test
  @DisplayName("サンプルのテーブルを、関連のまとまり・ハブ・関連の無いテーブルのいずれか1つに分けられる")
  void detectsClustersOfSample() {
    final TableClusters clusters = catalog.tableClusters(SearchScope.ALL, 3, false);

    assertEquals(
        List.of("employee"), clusters.hubs().stream().map(table -> table.key().name()).toList());
    assertEquals(
        List.of(
            List.of("department", "parking_spot", "audit_log", "employee_profile"),
            List.of("attendance", "attendance_note"),
            List.of("project", "project_assignment"),
            List.of("warehouse_zone", "shipment")),
        clusters.clusters().stream()
            .map(cluster -> cluster.tables().stream().map(table -> table.key().name()).toList())
            .toList());
    assertEquals(
        catalog.tables().all().size(),
        clusters.clusters().stream().mapToInt(cluster -> cluster.tables().size()).sum()
            + clusters.hubs().size()
            + clusters.unrelatedTables());
  }

  @Test
  @DisplayName("ビューのreferencedTablesを読み込み、テーブルを参照しているビューを逆引きできる（パーティションへの参照は親への参照）")
  void findsViewReferencesOfSample() {
    assertEquals(
        List.of(
            new ObjectKey("testdb", "sample", "attendance"),
            new ObjectKey("testdb", "sample", "employee_directory_view"),
            new ObjectKey("testdb", "sample_archive", "department")),
        find("attendance_monthly_view").referencedTables());
    assertEquals(
        List.of("attendance_monthly_view"),
        catalog.tables().viewsReferencing(find("attendance")).stream()
            .map(t -> t.key().name())
            .toList());
    assertEquals(
        List.of("employee_directory_view"),
        catalog.tables().viewsReferencing(find("department")).stream()
            .map(t -> t.key().name())
            .toList());
  }

  private static TableEntry find(String name) {
    return Lookups.found(catalog.tables().lookup(ObjectReference.of(null, null, name)));
  }
}
