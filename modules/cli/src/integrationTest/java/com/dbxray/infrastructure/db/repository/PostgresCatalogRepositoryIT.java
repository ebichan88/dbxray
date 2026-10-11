package com.dbxray.infrastructure.db.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.DatabaseEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.testsupport.SampleDatabase;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgresCatalogRepository（PostgreSQL用mapperのSQLとDTOからの変換）の結合テスト<br>
 * {@code docs/sample/postgres/ddl.sql}を流し込んだ実DBに対してSQLを実行し、DDLに用意した形（複合外部キー・自己参照・
 * 1対1・ビュー・トリガー・オーバーロード等）が取得できることを確かめる。どのSQLが壊れたかを特定できるよう、取得メソッドごとに検証する
 */
class PostgresCatalogRepositoryIT {

  private static final List<String> SAMPLE_SCHEMA = List.of("sample");

  private static PostgresCatalogRepository repository;

  @BeforeAll
  static void setUp() {
    repository = new PostgresCatalogRepository(SampleDatabase.sqlSessionFactory());
  }

  @Test
  @DisplayName("selectDatabase: DB名・RDBMS名・メジャーバージョンを取得する")
  void testSelectDatabase() {
    assertEquals(
        new DatabaseEntity(SampleDatabase.DATABASE_NAME, Dbms.POSTGRESQL, 16),
        repository.selectDatabase());
  }

  @Test
  @DisplayName("selectTableList: テーブル・ビュー・マテリアライズドビューを区分とDBコメント由来の論理名つきで取得する")
  void testSelectTableList() {
    final Map<String, TableEntity> tables = byName(tables(), TableEntity::physicalTableName);

    assertEquals(
        List.of(
            "attendance",
            "attendance_monthly_view",
            "attendance_note",
            "audit_log",
            "department",
            "employee",
            "employee_directory_view",
            "employee_profile",
            "parking_spot",
            "project",
            "project_assignment",
            "project_summary_mv",
            "shipment",
            "warehouse_zone"),
        tables.keySet().stream().sorted().toList());
    assertEquals(TableType.TABLE, tables.get("employee").tableType());
    assertEquals("従業員", tables.get("employee").logicalTableName());
    // コメントが無い場合は空文字（NULLにしない）
    assertEquals("", tables.get("department").logicalTableName());
    assertEquals(TableType.VIEW, tables.get("employee_directory_view").tableType());
    assertTrue(
        tables.get("employee_directory_view").definition().contains("JOIN sample.department"));
    assertEquals(TableType.MATERIALIZED_VIEW, tables.get("project_summary_mv").tableType());
    assertEquals("プロジェクト別要員数集計", tables.get("project_summary_mv").logicalTableName());
  }

  @Test
  @DisplayName("selectTableList: パーティションの子（多段の中間・別スキーマのものを含む）は含めず、パーティション表だけがパーティションキーを持つ")
  void testSelectTableListExcludesPartitions() {
    final Map<String, TableEntity> tables = byName(tables(), TableEntity::physicalTableName);

    assertTrue(
        tables.keySet().stream()
            .noneMatch(
                name -> name.startsWith("attendance_2") || name.equals("attendance_default")));
    final TableEntity attendance = tables.get("attendance");
    assertEquals(TableType.TABLE, attendance.tableType());
    assertEquals("勤怠（月次パーティション）", attendance.logicalTableName());
    assertEquals("RANGE (work_date)", attendance.partitionKey());
    assertTrue(attendance.isPartitioned());
    assertEquals("", tables.get("employee").partitionKey());
    // 別スキーマに置いた子は、スキーマを指定しない一覧にも、子のスキーマを指定した一覧にも載らない
    assertTrue(
        repository.selectTableList(List.of()).stream()
            .noneMatch(table -> table.physicalTableName().equals("attendance_2025")));
    assertEquals(
        List.of("department"),
        repository.selectTableList(List.of("sample_archive")).stream()
            .map(TableEntity::physicalTableName)
            .toList());
  }

  @Test
  @DisplayName("selectPartitionList: 下位のパーティションを親から子へ階層順に、範囲・親つきで取得する（別スキーマの子・DEFAULT・多段を含む）")
  void testSelectPartitions() {
    final List<PartitionEntity> partitions = repository.selectPartitionList(SAMPLE_SCHEMA);

    assertEquals(
        List.of(
            "attendance_2025",
            "attendance_2026_01",
            "attendance_2026_02",
            "attendance_2026_03",
            "attendance_2026_03_a",
            "attendance_2026_03_b",
            "attendance_default"),
        partitions.stream().map(PartitionEntity::partitionName).toList());
    assertTrue(
        partitions.stream()
            .allMatch(p -> p.tableKey().equals(TableKey.of("sample", "attendance"))));
    final Map<String, PartitionEntity> byName = byName(partitions, PartitionEntity::partitionName);
    assertEquals("sample_archive", byName.get("attendance_2025").partitionSchemaName());
    assertEquals("sample", byName.get("attendance_2026_01").partitionSchemaName());
    assertEquals(
        "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')",
        byName.get("attendance_2026_01").bound());
    assertEquals("DEFAULT", byName.get("attendance_default").bound());
    // 多段パーティション: 中間は自身のパーティションキーを持ち、その下位は中間を親とする
    assertEquals("RANGE (work_date)", byName.get("attendance_2026_03").partitionKey());
    assertEquals("attendance", byName.get("attendance_2026_03").parentName());
    assertEquals("attendance_2026_03", byName.get("attendance_2026_03_a").parentName());
    assertEquals("", byName.get("attendance_2026_03_a").partitionKey());
  }

  @Test
  @DisplayName("selectPartitionList: パーティション表（根）のスキーマで絞り込む（子のスキーマでは絞り込まない）")
  void testSelectPartitionsFiltersByRootSchema() {
    assertEquals(List.of(), repository.selectPartitionList(List.of("sample_archive")));
    assertEquals(7, repository.selectPartitionList(List.of()).size());
  }

  @Test
  @DisplayName(
      "selectViewReferenceList: ビューが参照するテーブルを1件ずつ取得し、パーティションへの参照は親のパーティション表へまとめる（別スキーマのテーブル・ビューを含む）")
  void testSelectViewReferenceList() {
    assertEquals(
        List.of(
            new ViewReferenceEntity(
                "sample",
                "attendance_monthly_view",
                TableType.VIEW,
                "sample",
                "attendance",
                TableType.TABLE),
            new ViewReferenceEntity(
                "sample",
                "attendance_monthly_view",
                TableType.VIEW,
                "sample",
                "employee_directory_view",
                TableType.VIEW),
            new ViewReferenceEntity(
                "sample",
                "attendance_monthly_view",
                TableType.VIEW,
                "sample_archive",
                "department",
                TableType.TABLE),
            new ViewReferenceEntity(
                "sample",
                "employee_directory_view",
                TableType.VIEW,
                "sample",
                "department",
                TableType.TABLE),
            new ViewReferenceEntity(
                "sample",
                "employee_directory_view",
                TableType.VIEW,
                "sample",
                "employee",
                TableType.TABLE),
            new ViewReferenceEntity(
                "sample",
                "project_summary_mv",
                TableType.MATERIALIZED_VIEW,
                "sample",
                "project",
                TableType.TABLE),
            new ViewReferenceEntity(
                "sample",
                "project_summary_mv",
                TableType.MATERIALIZED_VIEW,
                "sample",
                "project_assignment",
                TableType.TABLE)),
        repository.selectViewReferenceList(SAMPLE_SCHEMA));
    assertEquals(List.of(), repository.selectViewReferenceList(List.of("sample_archive")));
  }

  @Test
  @DisplayName("selectTableDetails: パーティション表（親）のカラムと、パーティションインデックス・制約を取得する")
  void testSelectPartitionedTableDetails() {
    final TableDetail detail = detail("attendance");

    assertEquals(
        List.of("attendance_id", "work_date", "employee_id", "work_minutes"),
        detail.columns().stream().map(ColumnEntity::physicalColumnName).toList());
    final Map<String, ColumnEntity> columns =
        byName(detail.columns(), ColumnEntity::physicalColumnName);
    assertTrue(columns.get("attendance_id").primaryKey());
    assertTrue(columns.get("work_date").primaryKey());
    assertEquals("勤務日（パーティションキー）", columns.get("work_date").logicalColumnName());
    final Map<String, IndexEntity> indexes = byName(detail.indexes(), IndexEntity::indexName);
    assertEquals(
        List.of("attendance_pkey", "idx_attendance_employee"),
        indexes.keySet().stream().sorted().toList());
    assertTrue(indexes.get("attendance_pkey").isPrimary());
    // 親の索引は、子のパーティションへ複製される元になるパーティションインデックス
    assertTrue(
        indexes
            .get("idx_attendance_employee")
            .indexDefinition()
            .contains("ON ONLY sample.attendance"));
    assertEquals(
        List.of("attendance_employee_id_fkey", "attendance_pkey"),
        detail.constraints().stream().map(ConstraintEntity::constraintName).sorted().toList());
  }

  @Test
  @DisplayName("selectTableDetails: パーティション表を参照するテーブルの制約に、参照先のパーティションごとに複製された外部キー制約を含めない")
  void testSelectConstraintsExcludesClonedForeignKeys() {
    assertEquals(
        List.of("attendance_note_attendance_id_work_date_fkey", "attendance_note_pkey"),
        detail("attendance_note").constraints().stream()
            .map(ConstraintEntity::constraintName)
            .sorted()
            .toList());
  }

  @Test
  @DisplayName("selectTableDetails: 別スキーマに同名のテーブルがあっても、インデックスは自スキーマのテーブルのものだけを取得する")
  void testSelectIndexesOfSameNamedTablesInDifferentSchemas() {
    assertEquals(
        List.of("department_department_code_key", "department_pkey"),
        detail("department").indexes().stream().map(IndexEntity::indexName).sorted().toList());

    final TableEntity archived =
        repository.selectTableList(List.of("sample_archive")).stream().findFirst().orElseThrow();
    final List<IndexEntity> archivedIndexes =
        repository.selectTableDetails(List.of(archived)).get(0).indexes();
    assertEquals(
        List.of("department_pkey", "idx_archive_department_note"),
        archivedIndexes.stream().map(IndexEntity::indexName).sorted().toList());
    assertTrue(
        archivedIndexes.stream().allMatch(index -> index.schemaName().equals("sample_archive")));
  }

  @Test
  @DisplayName("selectForeignKeyList: 親から子のパーティションへ複製された外部キー・パーティション表を参照するテーブルの複製された外部キーを含めない")
  void testSelectForeignKeysExcludesClonedOnes() {
    final List<ForeignKeyEntity> foreignKeys = repository.selectForeignKeyList(SAMPLE_SCHEMA);

    final List<ForeignKeyEntity> attendanceRelated =
        foreignKeys.stream()
            .filter(
                fk ->
                    fk.tableName().startsWith("attendance")
                        || fk.referenceTableName().startsWith("attendance"))
            .toList();
    assertEquals(
        List.of(
            "attendance.attendance_employee_id_fkey",
            "attendance_note.attendance_note_attendance_id_work_date_fkey"),
        attendanceRelated.stream()
            .map(fk -> fk.tableName() + "." + fk.foreignKeyName())
            .sorted()
            .toList());
    assertEquals(
        Cardinality.ONE_TO_MANY,
        foreignKey("attendance_note_attendance_id_work_date_fkey").cardinality());
  }

  @Test
  @DisplayName("selectTriggerList: パーティション表の行トリガーは、子のパーティションへ複製されたものを含めず親の1件だけを取得する")
  void testSelectTriggersExcludesClonedOnes() {
    final List<TriggerEntity> triggers =
        repository.selectTriggerList(SAMPLE_SCHEMA).stream()
            .filter(trigger -> trigger.tableName().startsWith("attendance"))
            .toList();

    assertEquals(1, triggers.size());
    assertEquals("attendance", triggers.get(0).tableName());
    assertEquals("trg_attendance_check_work_minutes", triggers.get(0).triggerName());
  }

  @Test
  @DisplayName("selectTableList: スキーマを指定しない場合も、システムカタログ（pg_catalog・information_schema）は含めない")
  void testSelectTableListWithoutSchemaExcludesSystemCatalogs() {
    final List<TableEntity> tables = repository.selectTableList(List.of());

    assertTrue(tables.stream().anyMatch(table -> table.physicalTableName().equals("employee")));
    assertTrue(
        tables.stream()
            .map(TableEntity::schemaName)
            .noneMatch(
                schema -> schema.equals("pg_catalog") || schema.equals("information_schema")));
  }

  @Test
  @DisplayName("selectTableDetails: カラムを定義順に、型・桁数・PK・NOT NULL・デフォルト値・論理名つきで取得する")
  void testSelectColumns() {
    final List<ColumnEntity> columns = detail("employee").columns();

    assertEquals(
        List.of(
            "employee_id",
            "employee_code",
            "employee_name",
            "department_id",
            "manager_id",
            "parking_spot_id",
            "status",
            "salary",
            "profile",
            "hired_date",
            "updated_at"),
        columns.stream().map(ColumnEntity::physicalColumnName).toList());
    final Map<String, ColumnEntity> byName = byName(columns, ColumnEntity::physicalColumnName);
    assertTrue(byName.get("employee_id").primaryKey());
    assertEquals("従業員ID", byName.get("employee_id").logicalColumnName());
    assertEquals("character varying(10)", byName.get("employee_code").columnType());
    assertEquals("10", byName.get("employee_code").precisionScale());
    assertFalse(byName.get("manager_id").notNull());
    assertEquals("sample.employee_status_enum", byName.get("status").columnType());
    assertEquals("'ACTIVE'::sample.employee_status_enum", byName.get("status").defaultValue());
    assertEquals("numeric(10,2)", byName.get("salary").columnType());
    // デフォルト値が無い場合は空文字（NULLにしない）
    assertEquals("", byName.get("employee_name").defaultValue());
  }

  @Test
  @DisplayName("selectTableDetails: btree以外の索引種別・複合索引・一意索引を取得する")
  void testSelectIndexes() {
    final Map<String, IndexEntity> indexes =
        byName(detail("employee").indexes(), IndexEntity::indexName);

    assertEquals(
        List.of(
            "employee_employee_code_key",
            "employee_parking_spot_id_key",
            "employee_pkey",
            "idx_employee_department_status",
            "idx_employee_profile_gin"),
        indexes.keySet().stream().sorted().toList());
    assertTrue(indexes.get("employee_pkey").isPrimary());
    assertEquals("gin", indexes.get("idx_employee_profile_gin").indexMethod());
    assertFalse(indexes.get("idx_employee_department_status").isUnique());
    assertTrue(
        indexes
            .get("idx_employee_department_status")
            .indexDefinition()
            .endsWith("(department_id, status)"));
  }

  @Test
  @DisplayName("selectTableDetails: 制約の区分に改行・余分な空白が混じらない（'PRIMARY KEY'の折り返しで壊れた過去の不具合）")
  void testSelectConstraints() {
    final Map<String, ConstraintEntity> constraints =
        byName(detail("employee").constraints(), ConstraintEntity::constraintName);

    assertEquals("PRIMARY KEY", constraints.get("employee_pkey").constraintType());
    assertEquals(
        "PRIMARY KEY (employee_id)", constraints.get("employee_pkey").constraintDefinition());
    assertEquals("CHECK", constraints.get("employee_salary_check").constraintType());
    assertEquals("UNIQUE", constraints.get("employee_employee_code_key").constraintType());
    assertEquals("FOREIGN KEY", constraints.get("employee_department_id_fkey").constraintType());
  }

  @Test
  @DisplayName("selectTableDetails: 指定したテーブルの詳細情報だけを、テーブルごとに振り分けて取得する")
  void testSelectTableDetailsOnlyForRequestedTables() {
    final List<TableEntity> requested =
        tables().stream()
            .filter(
                table ->
                    table.physicalTableName().equals("shipment")
                        || table.physicalTableName().equals("warehouse_zone"))
            .toList();

    final List<TableDetail> details = repository.selectTableDetails(requested);

    assertEquals(
        List.of("shipment", "warehouse_zone"),
        details.stream().map(detail -> detail.table().physicalTableName()).sorted().toList());
    details.forEach(
        detail ->
            assertTrue(
                detail.columns().stream()
                    .allMatch(
                        column -> column.tableName().equals(detail.table().physicalTableName()))));
  }

  @Test
  @DisplayName("selectColumnList: 指定したテーブルのカラムだけを、テーブルごとに定義順で取得する")
  void testSelectColumnListOnlyForRequestedTables() {
    final List<ColumnEntity> columns =
        repository.selectColumnList(
            List.of(TableKey.of("sample", "employee"), TableKey.of("sample", "department")));

    assertEquals(
        List.of("department", "employee"),
        columns.stream().map(ColumnEntity::tableName).distinct().toList());
    final List<ColumnEntity> employeeColumns =
        columns.stream().filter(column -> column.tableName().equals("employee")).toList();
    assertEquals("employee_id", employeeColumns.get(0).physicalColumnName());
    final ColumnEntity departmentId =
        employeeColumns.stream()
            .filter(column -> column.physicalColumnName().equals("department_id"))
            .findFirst()
            .orElseThrow();
    assertEquals("所属部署ID", departmentId.logicalColumnName());
    assertEquals("integer", departmentId.columnType());
  }

  @Test
  @DisplayName("selectColumnList: テーブルを指定しない場合は何も取得しない（全テーブルのカラムを取得しない）")
  void testSelectColumnListWithoutTables() {
    assertEquals(List.of(), repository.selectColumnList(List.of()));
  }

  @Test
  @DisplayName("selectForeignKeyList: 複合外部キーは、参照元と参照先の列を同じ順序で対応させる")
  void testSelectCompositeForeignKey() {
    final ForeignKeyEntity foreignKey = foreignKey("shipment_warehouse_code_zone_code_fkey");

    assertEquals("warehouse_zone", foreignKey.referenceTableName());
    assertEquals(List.of("warehouse_code", "zone_code"), foreignKey.columnNames());
    assertEquals(List.of("warehouse_code", "zone_code"), foreignKey.referenceColumnNames());
    assertEquals(Cardinality.ONE_TO_MANY, foreignKey.cardinality());
  }

  @Test
  @DisplayName("selectForeignKeyList: 参照元の列のNOT NULL・一意性から多重度を判定する（自己参照・1対1を含む）")
  void testSelectForeignKeyCardinality() {
    assertEquals(Cardinality.ONE_TO_MANY, foreignKey("employee_department_id_fkey").cardinality());
    final ForeignKeyEntity selfReference = foreignKey("employee_manager_id_fkey");
    assertEquals("employee", selfReference.tableName());
    assertEquals("employee", selfReference.referenceTableName());
    assertEquals(Cardinality.OPTIONAL_ONE_TO_MANY, selfReference.cardinality());
    assertEquals(
        Cardinality.OPTIONAL_ONE_TO_ONE, foreignKey("employee_parking_spot_id_fkey").cardinality());
    assertEquals(
        Cardinality.ONE_TO_ONE, foreignKey("employee_profile_employee_id_fkey").cardinality());
  }

  @Test
  @DisplayName("selectTriggerList: タイミング（BEFORE/AFTER/INSTEAD OF）・イベント・行/文単位を取得する")
  void testSelectTriggers() {
    final Map<String, TriggerEntity> triggers =
        byName(repository.selectTriggerList(SAMPLE_SCHEMA), TriggerEntity::triggerName);

    final TriggerEntity audit = triggers.get("trg_employee_audit");
    assertEquals("AFTER", audit.timing());
    assertEquals(List.of("INSERT", "DELETE", "UPDATE"), audit.events());
    assertEquals("ROW", audit.orientation());
    assertEquals("sample.log_employee_change", audit.functionName());
    assertEquals("BEFORE", triggers.get("trg_employee_set_updated_at").timing());
    final TriggerEntity truncate = triggers.get("trg_project_assignment_truncate");
    assertEquals(List.of("TRUNCATE"), truncate.events());
    assertEquals("STATEMENT", truncate.orientation());
    final TriggerEntity insteadOf = triggers.get("trg_employee_directory_insert");
    assertEquals("INSTEAD OF", insteadOf.timing());
    assertEquals("employee_directory_view", insteadOf.tableName());
  }

  @Test
  @DisplayName("selectFunctionList: 関数とプロシージャを区別し、オーバーロードに作成順の番号を振る")
  void testSelectFunctions() {
    final List<FunctionEntity> functions = repository.selectFunctionList(SAMPLE_SCHEMA);

    final List<FunctionEntity> overloads =
        functions.stream()
            .filter(function -> function.functionName().equals("calculate_bonus"))
            .toList();
    assertEquals(List.of(1, 2), overloads.stream().map(FunctionEntity::overloadIndex).toList());
    assertTrue(overloads.stream().allMatch(function -> function.overloadCount() == 2));
    assertEquals("p_salary numeric, p_rate numeric", overloads.get(0).functionArguments());
    final FunctionEntity procedure =
        functions.stream()
            .filter(function -> function.functionName().equals("raise_salary"))
            .findFirst()
            .orElseThrow();
    assertEquals("PROCEDURE", procedure.functionKind());
    // 一覧の取得では定義本体を取得しない
    assertEquals("", procedure.definition());
  }

  @Test
  @DisplayName("selectFunctionDefList: 定義本体を取得する")
  void testSelectFunctionDefinitions() {
    final FunctionEntity function =
        repository.selectFunctionDefList(SAMPLE_SCHEMA).stream()
            .filter(f -> f.functionName().equals("set_updated_at"))
            .findFirst()
            .orElseThrow();

    assertTrue(function.definition().contains("new.updated_at := now();"));
  }

  @Test
  @DisplayName("selectSequenceList: 列に紐付かないシーケンスは所有者が空、serial列のシーケンスは所有する列を取得する")
  void testSelectSequences() {
    final Map<String, SequenceEntity> sequences =
        byName(repository.selectSequenceList(SAMPLE_SCHEMA), SequenceEntity::sequenceName);

    final SequenceEntity invoice = sequences.get("invoice_no_seq");
    assertEquals("1000", invoice.minValue());
    assertEquals("999999", invoice.maxValue());
    assertEquals("5", invoice.cacheSize());
    assertTrue(invoice.cycle());
    assertEquals("", invoice.ownedBy());
    assertEquals("audit_log.log_id", sequences.get("audit_log_log_id_seq").ownedBy());
  }

  @Test
  @DisplayName("selectTypeList: ENUM・COMPOSITE・DOMAINのユーザー定義型を取得する")
  void testSelectTypes() {
    final Map<String, TypeEntity> types =
        byName(repository.selectTypeList(SAMPLE_SCHEMA), TypeEntity::typeName);

    assertEquals("ENUM", types.get("employee_status_enum").typeCategory());
    assertEquals("ACTIVE, ON_LEAVE, RETIRED", types.get("employee_status_enum").definition());
    assertEquals("COMPOSITE", types.get("address_type").typeCategory());
    assertEquals("DOMAIN", types.get("positive_numeric").typeCategory());
  }

  private static List<TableEntity> tables() {
    return repository.selectTableList(SAMPLE_SCHEMA);
  }

  private static TableDetail detail(String tableName) {
    final List<TableEntity> table =
        tables().stream().filter(t -> t.physicalTableName().equals(tableName)).toList();
    return repository.selectTableDetails(table).get(0);
  }

  private static ForeignKeyEntity foreignKey(String foreignKeyName) {
    return byName(repository.selectForeignKeyList(SAMPLE_SCHEMA), ForeignKeyEntity::foreignKeyName)
        .get(foreignKeyName);
  }

  private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
    return values.stream().collect(Collectors.toMap(name, Function.identity()));
  }
}
