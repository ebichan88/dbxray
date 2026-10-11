package com.dbxray.domain.model.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.testsupport.EntityFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link DatabaseMetrics}のテスト */
class DatabaseMetricsTest {

  private static final BaseInfoEntity BASE_INFO =
      new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH);

  private static final TableEntity EMPLOYEE = table("hr", "employee", "社員", TableType.TABLE, "");
  private static final TableEntity DEPT = table("hr", "dept", "", TableType.TABLE, "");
  private static final TableEntity EMPLOYEE_VIEW =
      table("hr", "v_employee", "社員ビュー", TableType.VIEW, "");
  private static final TableEntity SUMMARY =
      table("hr", "mv_summary", "", TableType.MATERIALIZED_VIEW, "");
  private static final TableEntity ORDERS =
      table("sales", "orders", "受注", TableType.TABLE, "RANGE (ordered_on)");
  private static final TableEntity LONELY = table("sales", "lonely", null, TableType.TABLE, "");

  @Test
  @DisplayName("builder: 一括取得した情報とカラムから、スキーマ名の順にスキーマごとの数を求める")
  void countsBySchema() {
    final DatabaseMetrics.Builder builder =
        DatabaseMetrics.builder(
            targets(EnumSet.of(OutputObjectType.FUNCTION, OutputObjectType.SEQUENCE)));
    builder.collectColumns(
        detail(EMPLOYEE, column("hr", "employee", "社員ID"), column("hr", "employee", "")));
    builder.collectColumns(detail(ORDERS, column("sales", "orders", null)));

    final DatabaseMetrics metrics = builder.build();

    assertEquals(
        List.of(
            new SchemaMetrics("hr", 2, 0, 1, 1, 2, 1, 1, 0, 0, 2, 2, 1, 1, 0, 2, 1, 1),
            new SchemaMetrics("sales", 2, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0),
            new SchemaMetrics("util", 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        metrics.schemas());
    assertEquals(
        new SchemaMetrics("", 4, 1, 1, 1, 3, 2, 1, 1, 1, 3, 3, 1, 1, 1, 3, 2, 1), metrics.total());
    assertEquals(6, metrics.total().allTables());
    assertFalse(metrics.isEmpty());
  }

  @Test
  @DisplayName("isCounted: 取得した追加オブジェクトの種別だけをtrueとする")
  void isCounted() {
    final DatabaseMetrics metrics =
        DatabaseMetrics.builder(targets(EnumSet.of(OutputObjectType.TRIGGER))).build();

    assertTrue(metrics.isCounted(OutputObjectType.TRIGGER));
    assertFalse(metrics.isCounted(OutputObjectType.FUNCTION));
  }

  @Test
  @DisplayName("build: 出力対象のオブジェクトが1つも無い場合は空とし、合計はすべて0とする")
  void empty() {
    final ExportTargets targets =
        new ExportTargets(
            BASE_INFO,
            Tables.of(List.of()),
            ForeignKeys.of(List.of()),
            Triggers.of(List.of()),
            Partitions.of(List.of()),
            ViewReferences.of(List.of()),
            Functions.of(List.of()),
            Sequences.of(List.of()),
            Types.of(List.of()),
            Annotations.empty(),
            Viewpoints.empty(),
            Set.of());

    final DatabaseMetrics metrics = DatabaseMetrics.builder(targets).build();

    assertTrue(metrics.isEmpty());
    assertEquals(
        new SchemaMetrics("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), metrics.total());
  }

  /**
   * hr・sales・utilの3スキーマの出力対象<br>
   * 外部キーはスキーマ内（hr）とスキーマをまたぐ論理リレーション（sales→hr）、トリガーは出力対象外のテーブル（hr.ghost）のものを含む
   */
  private static ExportTargets targets(Set<OutputObjectType> objectTypes) {
    return new ExportTargets(
        BASE_INFO,
        Tables.of(List.of(EMPLOYEE, DEPT, EMPLOYEE_VIEW, SUMMARY, ORDERS, LONELY)),
        ForeignKeys.of(
            List.of(
                ForeignKeyFixtures.physical("hr", "employee", "fk_dept", "hr", "dept"),
                ForeignKeyFixtures.logical("sales", "orders", "rel_employee", "hr", "employee"))),
        Triggers.of(
            List.of(
                EntityFixtures.trigger("hr", "employee"),
                EntityFixtures.trigger("hr", "employee"),
                EntityFixtures.trigger("hr", "ghost"),
                EntityFixtures.trigger("sales", "orders"))),
        Partitions.of(List.of()),
        ViewReferences.of(List.of()),
        Functions.of(
            List.of(
                function("hr", "calc", "FUNCTION"),
                function("hr", "refresh", "PROCEDURE"),
                function("sales", "tax", "FUNCTION"))),
        Sequences.of(
            List.of(
                new SequenceEntity("testdb", "util", "seq", "1", "1", "9", "1", "1", false, ""))),
        Types.of(List.of(new TypeEntity("testdb", "sales", "status", "ENUM", ""))),
        Annotations.of(
            Map.of(TableKey.of("hr", "dept"), new TableAnnotation("部署の説明", "", Map.of()))),
        Viewpoints.of(
            List.of(Viewpoint.of("core", "", "", List.of("hr.employee", "sales.orders")))),
        objectTypes);
  }

  private static TableEntity table(
      String schema, String name, String logicalName, TableType type, String partitionKey) {
    return new TableEntity("testdb", schema, logicalName, name, type, "", partitionKey);
  }

  private static ColumnEntity column(String schema, String table, String logicalName) {
    return new ColumnEntity(schema, table, logicalName, "c", "integer", "", false, false, "");
  }

  private static TableDetail detail(TableEntity table, ColumnEntity... columns) {
    return new TableDetail(table, List.of(columns), List.of(), List.of());
  }

  private static FunctionEntity function(String schema, String name, String kind) {
    return new FunctionEntity("testdb", schema, name, 1, 1, kind, "", "", "sql", "");
  }
}
