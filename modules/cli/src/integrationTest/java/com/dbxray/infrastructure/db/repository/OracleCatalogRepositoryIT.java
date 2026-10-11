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
import com.dbxray.testsupport.OracleSampleDatabase;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OracleCatalogRepository（Oracle用mapperのSQLとDTOからの変換）の結合テスト<br>
 * {@code docs/sample/oracle/ddl.sql}を流し込んだ実DBに対してSQLを実行し、DDLに用意した形（複合外部キー・自己参照・
 * 1対1・関数索引・別スキーマの同名テーブル等）が取得できることを確かめる。どのSQLが壊れたかを特定できるよう、取得メソッドごとに検証する
 */
@Tag("oracle")
class OracleCatalogRepositoryIT {

  private static final List<String> SAMPLE_SCHEMA = List.of(OracleSampleDatabase.SCHEMA);

  /** 1列あたり約70バイトのため、ビューのソースが32KBを超える列数 */
  private static final int LONG_VIEW_COLUMNS = 700;

  private static OracleCatalogRepository repository;

  @BeforeAll
  static void setUp() {
    repository = new OracleCatalogRepository(OracleSampleDatabase.sqlSessionFactory());
  }

  @Test
  @DisplayName("selectDatabase: DB名（接続先のPDB名）・RDBMS名・メジャーバージョンを取得する")
  void testSelectDatabase() {
    assertEquals(
        new DatabaseEntity(OracleSampleDatabase.DATABASE_NAME, Dbms.ORACLE, 23),
        repository.selectDatabase());
  }

  @Test
  @DisplayName("selectTableList: テーブル・ビュー・マテリアライズドビューを区分とDBコメント由来の論理名つきで取得する")
  void testSelectTableList() {
    final Map<String, TableEntity> tables = byName(tables(), TableEntity::physicalTableName);

    assertEquals(
        List.of(
            "ATTENDANCE",
            "ATTENDANCE_MONTHLY_VIEW",
            "ATTENDANCE_NOTE",
            "AUDIT_LOG",
            "DEPARTMENT",
            "EMPLOYEE",
            "EMPLOYEE_DIRECTORY_VIEW",
            "EMPLOYEE_PROFILE",
            "PARKING_SPOT",
            "PROJECT",
            "PROJECT_ASSIGNMENT",
            "PROJECT_SUMMARY_MV",
            "SHIPMENT",
            "WAREHOUSE_ZONE"),
        tables.keySet().stream().sorted().toList());
    assertEquals(TableType.TABLE, tables.get("EMPLOYEE").tableType());
    assertEquals("従業員", tables.get("EMPLOYEE").logicalTableName());
    // コメントが無い場合は空文字（NULLにしない）
    assertEquals("", tables.get("DEPARTMENT").logicalTableName());
    assertEquals(TableType.VIEW, tables.get("EMPLOYEE_DIRECTORY_VIEW").tableType());
    assertEquals(TableType.MATERIALIZED_VIEW, tables.get("PROJECT_SUMMARY_MV").tableType());
    // マテリアライズドビューのコメントはALL_TAB_COMMENTSではなくALL_MVIEW_COMMENTSにある
    assertEquals("プロジェクト別要員数集計", tables.get("PROJECT_SUMMARY_MV").logicalTableName());
    // ビュー・マテリアライズドビューのソースはLONG型で格納される
    assertTrue(
        tables.get("EMPLOYEE_DIRECTORY_VIEW").definition().contains("join sample.department d"));
    assertTrue(tables.get("PROJECT_SUMMARY_MV").definition().contains("count(pa.employee_id)"));
  }

  @Test
  @DisplayName("selectTableList: パーティション表は1つのテーブルとして取得し、パーティションは別のテーブルとして含めない")
  void testSelectPartitionedTable() {
    final List<TableEntity> attendances =
        tables().stream()
            .filter(table -> table.tableType() == TableType.TABLE)
            .filter(table -> table.physicalTableName().startsWith("ATTENDANCE"))
            .toList();

    assertEquals(
        List.of("ATTENDANCE", "ATTENDANCE_NOTE"),
        attendances.stream().map(TableEntity::physicalTableName).toList());
    assertEquals("勤怠（月次パーティション）", attendances.get(0).logicalTableName());
  }

  @Test
  @DisplayName("selectTableList: スキーマを指定した場合は、そのスキーマのテーブルだけを取得する")
  void testSelectTableListOfAnotherSchema() {
    final List<TableEntity> archived = repository.selectTableList(List.of("SAMPLE_ARCHIVE"));

    assertEquals(
        List.of("SAMPLE_ARCHIVE.DEPARTMENT"),
        archived.stream().map(TableEntity::getSchemaTableName).toList());
  }

  @Test
  @DisplayName("selectTableList: スキーマを指定しない場合も、Oracleが管理するスキーマ（SYS・SYSTEM等）は含めない")
  void testSelectTableListWithoutSchemaExcludesOracleMaintainedSchemas() {
    final List<String> schemas =
        repository.selectTableList(List.of()).stream()
            .map(TableEntity::schemaName)
            .distinct()
            .sorted()
            .toList();

    assertEquals(List.of("SAMPLE", "SAMPLE_ARCHIVE"), schemas);
  }

  @Test
  @DisplayName("selectTableDetails: カラムを定義順に、型・桁数・PK・NOT NULL・論理名つきで取得する")
  void testSelectColumns() {
    final List<ColumnEntity> columns = detail("EMPLOYEE").columns();

    assertEquals(
        List.of(
            "EMPLOYEE_ID",
            "EMPLOYEE_CODE",
            "EMPLOYEE_NAME",
            "DEPARTMENT_ID",
            "MANAGER_ID",
            "PARKING_SPOT_ID",
            "STATUS",
            "SALARY",
            "PROFILE",
            "HIRED_DATE",
            "UPDATED_AT"),
        columns.stream().map(ColumnEntity::physicalColumnName).toList());
    final Map<String, ColumnEntity> byName = byName(columns, ColumnEntity::physicalColumnName);
    assertTrue(byName.get("EMPLOYEE_ID").primaryKey());
    assertEquals("従業員ID", byName.get("EMPLOYEE_ID").logicalColumnName());
    assertEquals("NUMBER(10,0)", byName.get("EMPLOYEE_ID").columnType());
    assertEquals("VARCHAR2(10)", byName.get("EMPLOYEE_CODE").columnType());
    assertEquals("10", byName.get("EMPLOYEE_CODE").precisionScale());
    assertTrue(byName.get("EMPLOYEE_CODE").notNull());
    assertFalse(byName.get("MANAGER_ID").notNull());
    assertEquals("NUMBER(10,2)", byName.get("SALARY").columnType());
    assertEquals("10,2", byName.get("SALARY").precisionScale());
    assertEquals("CLOB", byName.get("PROFILE").columnType());
    // デフォルト値はLONG型で、書いたとおり（末尾の空白・改行を含む）に格納される
    assertEquals("'ACTIVE'", byName.get("STATUS").defaultValue());
    assertEquals("trunc(sysdate)", byName.get("HIRED_DATE").defaultValue());
    // IDENTITY列のデフォルト値は、環境ごとに名前の変わるシーケンスのnextvalではなく、生成の種別で表す
    assertEquals("GENERATED BY DEFAULT AS IDENTITY", byName.get("EMPLOYEE_ID").defaultValue());
    // デフォルト値が無い場合は空文字（NULLにしない）
    assertEquals("", byName.get("EMPLOYEE_NAME").defaultValue());
  }

  @Test
  @DisplayName("LONG型の値（ビューのソース・デフォルト値・索引の式）は、&・<・引用符・マルチバイト文字を含み32KBを超えても欠けずに取得する")
  void testSelectLongValuesWithSpecialCharacters() throws SQLException {
    final String literal = "'あいうえお&<>\"''' || note";
    final String columns =
        IntStream.range(0, LONG_VIEW_COLUMNS)
            .mapToObj(i -> "        " + literal + " AS c" + i)
            .collect(Collectors.joining(",\n"));
    try {
      execute(
          "CREATE TABLE sample.zz_long_values (id NUMBER, note VARCHAR2(100) DEFAULT 'a&<b>''c''あ')",
          "CREATE INDEX sample.zz_long_values_expr ON sample.zz_long_values"
              + " (CASE WHEN id < 0 AND note <> 'x' THEN 1 END)",
          "CREATE VIEW sample.zz_long_view AS SELECT\n" + columns + "\nFROM sample.zz_long_values");

      final String definition = table("ZZ_LONG_VIEW").definition();
      assertEquals(
          LONG_VIEW_COLUMNS,
          definition.split(Pattern.quote(literal), -1).length - 1,
          "ビューのソースに含まれる列の数");
      assertTrue(definition.endsWith("FROM sample.zz_long_values"), definition);
      final TableDetail values =
          repository.selectTableDetails(List.of(table("ZZ_LONG_VALUES"))).get(0);
      assertEquals(
          "'a&<b>''c''あ'",
          byName(values.columns(), ColumnEntity::physicalColumnName).get("NOTE").defaultValue());
      assertEquals(
          "CREATE INDEX ZZ_LONG_VALUES_EXPR ON ZZ_LONG_VALUES"
              + " (CASE  WHEN (\"ID\"<0 AND \"NOTE\"<>'x') THEN 1 END )",
          values.indexes().get(0).indexDefinition());
    } finally {
      execute("DROP VIEW sample.zz_long_view", "DROP TABLE sample.zz_long_values PURGE");
    }
  }

  @Test
  @DisplayName("selectTableDetails: 精度を指定しないNUMBER（INTEGER・集計列）は、空の精度を連結せずに表記する")
  void testSelectColumnsOfNumberWithoutPrecision() {
    final ColumnEntity recordId =
        byName(detail("AUDIT_LOG").columns(), ColumnEntity::physicalColumnName).get("RECORD_ID");
    final ColumnEntity memberCount =
        byName(detail("PROJECT_SUMMARY_MV").columns(), ColumnEntity::physicalColumnName)
            .get("MEMBER_COUNT");

    assertEquals("NUMBER(*,0)", recordId.columnType());
    assertEquals("", recordId.precisionScale());
    assertEquals("NUMBER", memberCount.columnType());
  }

  @Test
  @DisplayName("selectTableDetails: オブジェクト型の列は型名で取得する")
  void testSelectColumnsOfObjectType() {
    final ColumnEntity address =
        byName(detail("EMPLOYEE_PROFILE").columns(), ColumnEntity::physicalColumnName)
            .get("ADDRESS");

    assertEquals("ADDRESS_TYPE", address.columnType());
  }

  @Test
  @DisplayName("selectTableDetails: 関数索引（式がLONG型で格納される）・複合索引・一意索引を取得する")
  void testSelectIndexes() {
    final Map<String, IndexEntity> indexes =
        byName(detail("EMPLOYEE").indexes(), IndexEntity::indexName);

    assertEquals(
        List.of(
            "EMPLOYEE_EMPLOYEE_CODE_KEY",
            "EMPLOYEE_PARKING_SPOT_ID_KEY",
            "EMPLOYEE_PKEY",
            "IDX_EMPLOYEE_DEPARTMENT_STATUS",
            "IDX_EMPLOYEE_PROFILE_SKILL"),
        indexes.keySet().stream().sorted().toList());
    assertTrue(indexes.get("EMPLOYEE_PKEY").isPrimary());
    assertTrue(indexes.get("EMPLOYEE_EMPLOYEE_CODE_KEY").isUnique());
    assertFalse(indexes.get("IDX_EMPLOYEE_DEPARTMENT_STATUS").isUnique());
    assertEquals(
        "CREATE INDEX IDX_EMPLOYEE_DEPARTMENT_STATUS ON EMPLOYEE (DEPARTMENT_ID,STATUS)",
        indexes.get("IDX_EMPLOYEE_DEPARTMENT_STATUS").indexDefinition());
    final IndexEntity functionBased = indexes.get("IDX_EMPLOYEE_PROFILE_SKILL");
    assertEquals("FUNCTION-BASED NORMAL", functionBased.indexMethod());
    assertTrue(
        functionBased.indexDefinition().contains("JSON_VALUE(\"PROFILE\""),
        functionBased.indexDefinition());
  }

  @Test
  @DisplayName("selectTableDetails: 別スキーマに同名のテーブルがあっても、インデックスは自スキーマのテーブルのものだけを取得する")
  void testSelectIndexesOfSameNamedTablesInDifferentSchemas() {
    assertEquals(
        List.of("DEPARTMENT_DEPARTMENT_CODE_KEY", "DEPARTMENT_PKEY"),
        detail("DEPARTMENT").indexes().stream().map(IndexEntity::indexName).sorted().toList());

    final TableDetail archived =
        repository.selectTableDetails(repository.selectTableList(List.of("SAMPLE_ARCHIVE"))).get(0);
    assertEquals(
        List.of("DEPARTMENT_PKEY", "IDX_ARCHIVE_DEPARTMENT_NOTE"),
        archived.indexes().stream().map(IndexEntity::indexName).sorted().toList());
    assertTrue(
        archived.indexes().stream().allMatch(index -> index.schemaName().equals("SAMPLE_ARCHIVE")));
    assertEquals(
        List.of("ARCHIVE_ID"),
        archived.columns().stream()
            .filter(ColumnEntity::primaryKey)
            .map(ColumnEntity::physicalColumnName)
            .toList());
  }

  @Test
  @DisplayName("selectTableDetails: 制約を区分・定義つきで取得し、NOT NULL（SYS_C...の名前になる）は含めない")
  void testSelectConstraints() {
    final Map<String, ConstraintEntity> constraints =
        byName(detail("EMPLOYEE").constraints(), ConstraintEntity::constraintName);

    assertEquals(
        List.of(
            "EMPLOYEE_DEPARTMENT_ID_FKEY",
            "EMPLOYEE_EMPLOYEE_CODE_KEY",
            "EMPLOYEE_MANAGER_ID_FKEY",
            "EMPLOYEE_PARKING_SPOT_ID_FKEY",
            "EMPLOYEE_PARKING_SPOT_ID_KEY",
            "EMPLOYEE_PKEY",
            "EMPLOYEE_PROFILE_CHECK",
            "EMPLOYEE_SALARY_CHECK",
            "EMPLOYEE_STATUS_CHECK"),
        constraints.keySet().stream().sorted().toList());
    assertEquals("PRIMARY KEY", constraints.get("EMPLOYEE_PKEY").constraintType());
    assertEquals(
        "PRIMARY KEY (EMPLOYEE_ID)", constraints.get("EMPLOYEE_PKEY").constraintDefinition());
    assertEquals("CHECK", constraints.get("EMPLOYEE_SALARY_CHECK").constraintType());
    assertEquals(
        "CHECK (salary >= 0)", constraints.get("EMPLOYEE_SALARY_CHECK").constraintDefinition());
    assertEquals(
        "UNIQUE (EMPLOYEE_CODE)",
        constraints.get("EMPLOYEE_EMPLOYEE_CODE_KEY").constraintDefinition());
    assertEquals(
        "FOREIGN KEY (DEPARTMENT_ID) REFERENCES DEPARTMENT(DEPARTMENT_ID)",
        constraints.get("EMPLOYEE_DEPARTMENT_ID_FKEY").constraintDefinition());
  }

  @Test
  @DisplayName("selectTableDetails: 複合キーの制約は、列を定義順に連結した1件として取得する")
  void testSelectCompositeConstraints() {
    final List<ConstraintEntity> constraints = detail("SHIPMENT").constraints();

    assertEquals(
        List.of(
            "FOREIGN KEY (WAREHOUSE_CODE,ZONE_CODE) REFERENCES WAREHOUSE_ZONE(WAREHOUSE_CODE,ZONE_CODE)",
            "PRIMARY KEY (SHIPMENT_ID)"),
        constraints.stream().map(ConstraintEntity::constraintDefinition).toList());
    assertEquals(
        List.of("PRIMARY KEY (WAREHOUSE_CODE,ZONE_CODE)"),
        detail("WAREHOUSE_ZONE").constraints().stream()
            .map(ConstraintEntity::constraintDefinition)
            .toList());
  }

  @Test
  @DisplayName("selectTableDetails: 指定したテーブルの詳細情報だけを、テーブルごとに振り分けて取得する")
  void testSelectTableDetailsOnlyForRequestedTables() {
    final List<TableEntity> requested =
        tables().stream()
            .filter(
                table ->
                    table.physicalTableName().equals("SHIPMENT")
                        || table.physicalTableName().equals("WAREHOUSE_ZONE"))
            .toList();

    final List<TableDetail> details = repository.selectTableDetails(requested);

    assertEquals(
        List.of("SHIPMENT", "WAREHOUSE_ZONE"),
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
            List.of(TableKey.of("SAMPLE", "EMPLOYEE"), TableKey.of("SAMPLE", "DEPARTMENT")));

    assertEquals(
        List.of("DEPARTMENT", "EMPLOYEE"),
        columns.stream().map(ColumnEntity::tableName).distinct().toList());
    final List<ColumnEntity> employeeColumns =
        columns.stream().filter(column -> column.tableName().equals("EMPLOYEE")).toList();
    assertEquals("EMPLOYEE_ID", employeeColumns.get(0).physicalColumnName());
    final ColumnEntity departmentId =
        employeeColumns.stream()
            .filter(column -> column.physicalColumnName().equals("DEPARTMENT_ID"))
            .findFirst()
            .orElseThrow();
    assertEquals("所属部署ID", departmentId.logicalColumnName());
  }

  @Test
  @DisplayName("selectForeignKeyList: 複合外部キーは、参照元と参照先の列を同じ順序で対応させる")
  void testSelectCompositeForeignKey() {
    final ForeignKeyEntity foreignKey = foreignKey("SHIPMENT_WAREHOUSE_CODE_ZONE_CODE_FKEY");

    assertEquals("WAREHOUSE_ZONE", foreignKey.referenceTableName());
    assertEquals(List.of("WAREHOUSE_CODE", "ZONE_CODE"), foreignKey.columnNames());
    assertEquals(List.of("WAREHOUSE_CODE", "ZONE_CODE"), foreignKey.referenceColumnNames());
    assertEquals(Cardinality.ONE_TO_MANY, foreignKey.cardinality());
  }

  @Test
  @DisplayName("selectForeignKeyList: 参照元の列のNOT NULL・一意性から多重度を判定する（自己参照・1対1・パーティション表を含む）")
  void testSelectForeignKeyCardinality() {
    assertEquals(Cardinality.ONE_TO_MANY, foreignKey("EMPLOYEE_DEPARTMENT_ID_FKEY").cardinality());
    final ForeignKeyEntity selfReference = foreignKey("EMPLOYEE_MANAGER_ID_FKEY");
    assertEquals("EMPLOYEE", selfReference.tableName());
    assertEquals("EMPLOYEE", selfReference.referenceTableName());
    assertEquals(Cardinality.OPTIONAL_ONE_TO_MANY, selfReference.cardinality());
    assertEquals(
        Cardinality.OPTIONAL_ONE_TO_ONE, foreignKey("EMPLOYEE_PARKING_SPOT_ID_FKEY").cardinality());
    assertEquals(
        Cardinality.ONE_TO_ONE, foreignKey("EMPLOYEE_PROFILE_EMPLOYEE_ID_FKEY").cardinality());
    assertEquals(
        Cardinality.ONE_TO_MANY,
        foreignKey("ATTENDANCE_NOTE_ATTENDANCE_ID_WORK_DATE_FKEY").cardinality());
  }

  @Test
  @DisplayName("selectTableList: パーティション表だけが、分割方法とキーの列からなるパーティションキーを持つ")
  void testSelectTableListPartitionKey() {
    final Map<String, TableEntity> tables = byName(tables(), TableEntity::physicalTableName);

    assertEquals("RANGE (WORK_DATE)", tables.get("ATTENDANCE").partitionKey());
    assertTrue(tables.get("ATTENDANCE").isPartitioned());
    assertEquals("", tables.get("EMPLOYEE").partitionKey());
  }

  @Test
  @DisplayName("selectPartitionList: パーティションを位置の順に、境界（LONG型の上限値）・親つきで取得する")
  void testSelectPartitions() {
    final List<PartitionEntity> partitions = repository.selectPartitionList(SAMPLE_SCHEMA);

    assertEquals(
        List.of(
            "ATTENDANCE_2025",
            "ATTENDANCE_2026_01",
            "ATTENDANCE_2026_02",
            "ATTENDANCE_2026_03",
            "ATTENDANCE_DEFAULT"),
        partitions.stream().map(PartitionEntity::partitionName).toList());
    assertTrue(
        partitions.stream()
            .allMatch(
                p ->
                    p.tableKey().equals(TableKey.of("SAMPLE", "ATTENDANCE"))
                        && p.parentName().equals("ATTENDANCE")
                        && p.partitionKey().isEmpty()));
    assertEquals(
        "VALUES LESS THAN (TO_DATE(' 2026-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS',"
            + " 'NLS_CALENDAR=GREGORIAN'))",
        partitions.get(0).bound());
    assertEquals("VALUES LESS THAN (MAXVALUE)", partitions.get(4).bound());
    assertEquals(List.of(), repository.selectPartitionList(List.of("SAMPLE_ARCHIVE")));
  }

  @Test
  @DisplayName(
      "selectViewReferenceList: ビューが参照するテーブルを1件ずつ取得する。マテリアライズドビューのコンテナ表（自身）は含めない（別スキーマのテーブル・ビューを含む）")
  void testSelectViewReferenceList() {
    assertEquals(
        List.of(
            new ViewReferenceEntity(
                "SAMPLE",
                "ATTENDANCE_MONTHLY_VIEW",
                TableType.VIEW,
                "SAMPLE",
                "ATTENDANCE",
                TableType.TABLE),
            new ViewReferenceEntity(
                "SAMPLE",
                "ATTENDANCE_MONTHLY_VIEW",
                TableType.VIEW,
                "SAMPLE",
                "EMPLOYEE_DIRECTORY_VIEW",
                TableType.VIEW),
            new ViewReferenceEntity(
                "SAMPLE",
                "ATTENDANCE_MONTHLY_VIEW",
                TableType.VIEW,
                "SAMPLE_ARCHIVE",
                "DEPARTMENT",
                TableType.TABLE),
            new ViewReferenceEntity(
                "SAMPLE",
                "EMPLOYEE_DIRECTORY_VIEW",
                TableType.VIEW,
                "SAMPLE",
                "DEPARTMENT",
                TableType.TABLE),
            new ViewReferenceEntity(
                "SAMPLE",
                "EMPLOYEE_DIRECTORY_VIEW",
                TableType.VIEW,
                "SAMPLE",
                "EMPLOYEE",
                TableType.TABLE),
            new ViewReferenceEntity(
                "SAMPLE",
                "PROJECT_SUMMARY_MV",
                TableType.MATERIALIZED_VIEW,
                "SAMPLE",
                "PROJECT",
                TableType.TABLE),
            new ViewReferenceEntity(
                "SAMPLE",
                "PROJECT_SUMMARY_MV",
                TableType.MATERIALIZED_VIEW,
                "SAMPLE",
                "PROJECT_ASSIGNMENT",
                TableType.TABLE)),
        repository.selectViewReferenceList(SAMPLE_SCHEMA));
  }

  @Test
  @DisplayName("selectPartitionList: サブパーティションは親のパーティションの下に並べ、LISTのDEFAULTは「DEFAULT」とする")
  void testSelectSubpartitions() throws SQLException {
    try {
      execute(
          "CREATE TABLE sample.zz_part (id NUMBER, region VARCHAR2(10), d DATE)"
              + " PARTITION BY LIST (region) SUBPARTITION BY RANGE (d) SUBPARTITION TEMPLATE ("
              + " SUBPARTITION early VALUES LESS THAN (DATE '2026-01-01'),"
              + " SUBPARTITION late VALUES LESS THAN (MAXVALUE))"
              + " (PARTITION east VALUES ('EAST'), PARTITION other VALUES (DEFAULT))");

      assertEquals("LIST (REGION)", table("ZZ_PART").partitionKey());
      final List<PartitionEntity> partitions =
          repository.selectPartitionList(SAMPLE_SCHEMA).stream()
              .filter(p -> p.tableName().equals("ZZ_PART"))
              .toList();
      assertEquals(
          List.of(
              "EAST:ZZ_PART:VALUES ('EAST'):RANGE (D)",
              "EAST_EARLY:EAST:VALUES LESS THAN (TO_DATE(' 2026-01-01 00:00:00',"
                  + " 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')):",
              "EAST_LATE:EAST:VALUES LESS THAN (MAXVALUE):",
              "OTHER:ZZ_PART:DEFAULT:RANGE (D)",
              "OTHER_EARLY:OTHER:VALUES LESS THAN (TO_DATE(' 2026-01-01 00:00:00',"
                  + " 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')):",
              "OTHER_LATE:OTHER:VALUES LESS THAN (MAXVALUE):"),
          partitions.stream()
              .map(
                  p ->
                      String.join(
                          ":", p.partitionName(), p.parentName(), p.bound(), p.partitionKey()))
              .toList());
    } finally {
      execute("DROP TABLE sample.zz_part PURGE");
    }
  }

  @Test
  @DisplayName("selectTriggerList: タイミング（BEFORE/AFTER/INSTEAD OF）・イベント・行/文単位・宣言部を取得する")
  void testSelectTriggers() {
    final Map<String, TriggerEntity> triggers =
        byName(repository.selectTriggerList(SAMPLE_SCHEMA), TriggerEntity::triggerName);

    assertEquals(
        List.of(
            "TRG_ATTENDANCE_CHECK_WORK_MINUTES",
            "TRG_EMPLOYEE_AUDIT",
            "TRG_EMPLOYEE_DIRECTORY_INSERT",
            "TRG_EMPLOYEE_SET_UPDATED_AT",
            "TRG_PROJECT_ASSIGNMENT_DELETE"),
        triggers.keySet().stream().sorted().toList());
    final TriggerEntity audit = triggers.get("TRG_EMPLOYEE_AUDIT");
    assertEquals("EMPLOYEE", audit.tableName());
    assertEquals("AFTER", audit.timing());
    assertEquals(List.of("INSERT", "UPDATE", "DELETE"), audit.events());
    assertEquals("ROW", audit.orientation());
    // 本体はトリガーの中に書かれ、呼び出す関数は無い
    assertEquals("", audit.functionName());
    assertEquals(
        "CREATE OR REPLACE TRIGGER sample.trg_employee_audit after insert or update or delete on"
            + " sample.employee for each row",
        audit.triggerDefinition());
    assertEquals("BEFORE", triggers.get("TRG_EMPLOYEE_SET_UPDATED_AT").timing());
    assertEquals("STATEMENT", triggers.get("TRG_PROJECT_ASSIGNMENT_DELETE").orientation());
    final TriggerEntity insteadOf = triggers.get("TRG_EMPLOYEE_DIRECTORY_INSERT");
    assertEquals("INSTEAD OF", insteadOf.timing());
    assertEquals("ROW", insteadOf.orientation());
    assertEquals("EMPLOYEE_DIRECTORY_VIEW", insteadOf.tableName());
  }

  @Test
  @DisplayName("selectTriggerList: 本体のPL/SQL（DECLARE部を含む）を、末尾の改行を除いて取得する")
  void testSelectTriggerBody() {
    final Map<String, TriggerEntity> triggers =
        byName(repository.selectTriggerList(SAMPLE_SCHEMA), TriggerEntity::triggerName);

    assertEquals(
        """
        begin
            :new.updated_at := systimestamp;
        end;""",
        triggers.get("TRG_EMPLOYEE_SET_UPDATED_AT").body());
    final String audit = triggers.get("TRG_EMPLOYEE_AUDIT").body();
    assertTrue(audit.startsWith("declare"), audit);
    assertTrue(audit.contains("insert into sample.audit_log"), audit);
    assertTrue(audit.endsWith("end;"), audit);
  }

  @Test
  @DisplayName("selectTriggerList: WHEN句を持つトリガーは、定義にWHEN句を含める")
  void testSelectTriggerWithWhenClause() throws SQLException {
    try {
      execute(
          "CREATE TABLE sample.zz_trigger_target (id NUMBER)",
          "CREATE TRIGGER sample.zz_trigger BEFORE INSERT ON sample.zz_trigger_target"
              + " FOR EACH ROW WHEN (new.id < 0) BEGIN :new.id := 0; END;");

      final TriggerEntity trigger =
          byName(repository.selectTriggerList(SAMPLE_SCHEMA), TriggerEntity::triggerName)
              .get("ZZ_TRIGGER");
      assertEquals(
          "CREATE OR REPLACE TRIGGER sample.zz_trigger BEFORE INSERT ON sample.zz_trigger_target"
              + " FOR EACH ROW WHEN (new.id < 0)",
          trigger.triggerDefinition());
    } finally {
      execute("DROP TABLE sample.zz_trigger_target PURGE");
    }
  }

  @Test
  @DisplayName("selectFunctionList: 単独の関数・プロシージャと、パッケージ内のサブプログラム（オーバーロードに番号を振る）を取得する")
  void testSelectFunctions() {
    final List<FunctionEntity> functions = repository.selectFunctionList(SAMPLE_SCHEMA);

    final List<FunctionEntity> overloads =
        functions.stream()
            .filter(function -> function.functionName().equals("BONUS.CALCULATE_BONUS"))
            .toList();
    assertEquals(List.of(1, 2), overloads.stream().map(FunctionEntity::overloadIndex).toList());
    assertTrue(overloads.stream().allMatch(function -> function.overloadCount() == 2));
    assertEquals("FUNCTION", overloads.get(0).functionKind());
    assertEquals("P_SALARY NUMBER, P_RATE NUMBER", overloads.get(0).functionArguments());
    assertEquals("NUMBER", overloads.get(0).functionResult());
    assertEquals("P_SALARY NUMBER", overloads.get(1).functionArguments());
    final FunctionEntity procedure =
        functions.stream()
            .filter(function -> function.functionName().equals("RAISE_SALARY"))
            .findFirst()
            .orElseThrow();
    assertEquals("PROCEDURE", procedure.functionKind());
    assertEquals("", procedure.functionResult());
    assertEquals(1, procedure.overloadCount());
    // 一覧の取得では定義本体を取得しない
    assertEquals("", procedure.definition());
  }

  @Test
  @DisplayName("selectFunctionDefList: 定義本体を取得する（パッケージ内のサブプログラムは、パッケージの仕様部と本体）")
  void testSelectFunctionDefinitions() {
    final Map<String, FunctionEntity> definitions =
        repository.selectFunctionDefList(SAMPLE_SCHEMA).stream()
            .filter(function -> function.overloadIndex() == 1)
            .collect(Collectors.toMap(FunctionEntity::functionName, Function.identity()));

    final String procedure = definitions.get("RAISE_SALARY").definition();
    assertTrue(
        procedure.startsWith("CREATE OR REPLACE procedure raise_salary(p_employee_id number,"),
        procedure);
    final String bonus = definitions.get("BONUS.CALCULATE_BONUS").definition();
    assertTrue(bonus.startsWith("CREATE OR REPLACE package bonus as"), bonus);
    assertTrue(bonus.contains("CREATE OR REPLACE package body bonus as"), bonus);
    assertTrue(bonus.contains("return p_salary * p_rate;"), bonus);
  }

  @Test
  @DisplayName("selectSequenceList: シーケンスを取得し、IDENTITY列が自動で作るシーケンスは含めない")
  void testSelectSequences() {
    final List<SequenceEntity> sequences = repository.selectSequenceList(SAMPLE_SCHEMA);

    assertEquals(
        List.of("INVOICE_NO_SEQ"), sequences.stream().map(SequenceEntity::sequenceName).toList());
    final SequenceEntity invoice = sequences.get(0);
    assertEquals("1", invoice.incrementBy());
    assertEquals("1000", invoice.minValue());
    assertEquals("999999", invoice.maxValue());
    assertEquals("5", invoice.cacheSize());
    assertTrue(invoice.cycle());
    assertEquals("", invoice.ownedBy());
  }

  @Test
  @DisplayName("selectTypeList: オブジェクト型は属性を、コレクション型は要素の型を定義として取得する")
  void testSelectTypes() {
    final Map<String, TypeEntity> types =
        byName(repository.selectTypeList(SAMPLE_SCHEMA), TypeEntity::typeName);

    assertEquals("OBJECT", types.get("ADDRESS_TYPE").typeCategory());
    assertEquals(
        "STREET VARCHAR2(100), CITY VARCHAR2(50), POSTAL_CODE VARCHAR2(10)",
        types.get("ADDRESS_TYPE").definition());
    assertEquals("VARRAY", types.get("PHONE_NUMBER_LIST").typeCategory());
    assertEquals("VARRAY(5) OF VARCHAR2(20)", types.get("PHONE_NUMBER_LIST").definition());
  }

  private static List<TableEntity> tables() {
    return repository.selectTableList(SAMPLE_SCHEMA);
  }

  private static TableEntity table(String tableName) {
    return tables().stream()
        .filter(t -> t.physicalTableName().equals(tableName))
        .findFirst()
        .orElseThrow();
  }

  /** 失敗した文があっても残りを実行する（後片付けで、作成に失敗したオブジェクトがあっても残りを削除するため） */
  private static void execute(String... statements) throws SQLException {
    SQLException failure = null;
    try (SqlSession session = OracleSampleDatabase.sqlSessionFactory().openSession();
        Statement statement = session.getConnection().createStatement()) {
      for (final String sql : statements) {
        try {
          statement.execute(sql);
        } catch (SQLException e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
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
