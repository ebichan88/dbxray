package com.dbxray.domain.model.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableUsage・FunctionTableUsage の値の扱いのテスト */
class TableUsageTest {

  private static final TableEntity SAMPLE_EMPLOYEE =
      new TableEntity("db", "sample", "", "employee", TableType.TABLE, "");

  private static final TableEntity OTHER_EMPLOYEE =
      new TableEntity("db", "other", "", "employee", TableType.VIEW, "");

  @Test
  @DisplayName("TableUsage: 操作はC・R・U・Dの順に並べ、渡したコレクションを後から変えても影響を受けない")
  void testOperationsAreOrderedAndCopied() {
    final Set<CrudOperation> operations = EnumSet.of(CrudOperation.DELETE, CrudOperation.CREATE);
    final List<TableEntity> tables = new ArrayList<>(List.of(SAMPLE_EMPLOYEE));
    final TableUsage usage = new TableUsage(tables, SchemaResolution.DETERMINED, operations);
    operations.add(CrudOperation.READ);
    tables.add(OTHER_EMPLOYEE);

    assertEquals(List.of(CrudOperation.CREATE, CrudOperation.DELETE), usage.orderedOperations());
    assertEquals(
        List.of(CrudOperation.CREATE, CrudOperation.DELETE), List.copyOf(usage.operations()));
    assertThrows(
        UnsupportedOperationException.class, () -> usage.operations().add(CrudOperation.READ));
    assertTrue(usage.has(CrudOperation.CREATE));
    assertFalse(usage.has(CrudOperation.READ));
    assertEquals(List.of(SAMPLE_EMPLOYEE), usage.tables());
    assertThrows(UnsupportedOperationException.class, () -> usage.tables().clear());
  }

  @Test
  @DisplayName("TableUsage: テーブル・操作が空なら例外にする（行に出すものが無いため）")
  void testRejectsEmpty() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TableUsage(List.of(), SchemaResolution.DETERMINED, Set.of(CrudOperation.READ)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TableUsage(List.of(SAMPLE_EMPLOYEE), SchemaResolution.DETERMINED, Set.of()));
  }

  @Test
  @DisplayName("TableUsage: スキーマが決まったのに複数のテーブルを持つ・候補の物理テーブル名が揃っていないと例外にする")
  void testRejectsInconsistentTables() {
    final TableEntity department =
        new TableEntity("db", "sample", "", "department", TableType.TABLE, "");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TableUsage(
                List.of(SAMPLE_EMPLOYEE, OTHER_EMPLOYEE),
                SchemaResolution.DETERMINED,
                Set.of(CrudOperation.READ)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TableUsage(
                List.of(SAMPLE_EMPLOYEE, department),
                SchemaResolution.SEARCH_PATH,
                Set.of(CrudOperation.READ)));
  }

  @Test
  @DisplayName("TableUsage: スキーマが決まった行はスキーマ名・区分を返し、候補は空")
  void testDetermined() {
    final TableUsage usage =
        new TableUsage(
            List.of(OTHER_EMPLOYEE), SchemaResolution.DETERMINED, Set.of(CrudOperation.READ));

    assertEquals("other", usage.schemaName());
    assertEquals("employee", usage.tableName());
    assertEquals(List.of(), usage.schemaCandidates());
    assertEquals(Optional.of(TableType.VIEW), usage.tableType());
  }

  @Test
  @DisplayName("TableUsage: スキーマが決まらない行はスキーマ名を空にし、候補を並べ、区分は候補で揃うときだけ返す")
  void testUndetermined() {
    final TableUsage mixed =
        new TableUsage(
            List.of(SAMPLE_EMPLOYEE, OTHER_EMPLOYEE),
            SchemaResolution.SEARCH_PATH,
            Set.of(CrudOperation.READ));
    final TableUsage single =
        new TableUsage(
            List.of(SAMPLE_EMPLOYEE), SchemaResolution.SEARCH_PATH, Set.of(CrudOperation.READ));

    assertEquals("", mixed.schemaName());
    assertEquals(List.of("sample", "other"), mixed.schemaCandidates());
    assertEquals(Optional.empty(), mixed.tableType());
    assertEquals(List.of("sample"), single.schemaCandidates());
    assertEquals(Optional.of(TableType.TABLE), single.tableType());
  }

  @Test
  @DisplayName(
      "FunctionTableUsage.notAnalyzable: 解析できなかった理由でない状態（UNSUPPORTED_LANGUAGE・NOT_ANALYZED・ANALYZED）は渡せない")
  void testNotAnalyzableRejectsStatusesThatAreNotReasons() {
    for (final TableUsageStatus status :
        List.of(
            TableUsageStatus.UNSUPPORTED_LANGUAGE,
            TableUsageStatus.NOT_ANALYZED,
            TableUsageStatus.ANALYZED)) {
      assertThrows(IllegalArgumentException.class, () -> FunctionTableUsage.notAnalyzable(status));
    }
  }

  @Test
  @DisplayName(
      "FunctionTableUsage: 状態と合わない値は持てない（言語はUNSUPPORTED_LANGUAGEだけ、テーブル・動的SQL・印はANALYZEDだけ）")
  void testRejectsValuesThatDoNotMatchStatus() {
    final TableUsage usage =
        new TableUsage(
            List.of(SAMPLE_EMPLOYEE), SchemaResolution.DETERMINED, Set.of(CrudOperation.READ));
    final List<DynamicSqlKind> dynamicSql = List.of(DynamicSqlKind.EXECUTE);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FunctionTableUsage(
                TableUsageStatus.WRAPPED, "plpython3u", List.of(), List.of(), false, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FunctionTableUsage(
                TableUsageStatus.NOT_ANALYZED, "", List.of(usage), List.of(), false, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FunctionTableUsage(
                TableUsageStatus.NO_DEFINITION, "", List.of(), dynamicSql, false, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FunctionTableUsage(
                TableUsageStatus.UNSUPPORTED_LANGUAGE, "JAVA", List.of(), List.of(), true, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FunctionTableUsage(
                TableUsageStatus.SUBPROGRAM_NOT_FOUND, "", List.of(), List.of(), false, true));
  }

  @Test
  @DisplayName("FunctionTableUsage.schemaUndeterminedTables: スキーマが決まらない行だけを、決まり方の宣言順に、行は元の並びでまとめる")
  void testSchemaUndeterminedTables() {
    final TableEntity department =
        new TableEntity("db", "sample", "", "department", TableType.TABLE, "");
    final TableUsage determined =
        new TableUsage(
            List.of(SAMPLE_EMPLOYEE), SchemaResolution.DETERMINED, Set.of(CrudOperation.READ));
    final TableUsage synonym =
        new TableUsage(
            List.of(OTHER_EMPLOYEE), SchemaResolution.SYNONYM, Set.of(CrudOperation.DELETE));
    final TableUsage searchPathEmployee =
        new TableUsage(
            List.of(SAMPLE_EMPLOYEE, OTHER_EMPLOYEE),
            SchemaResolution.SEARCH_PATH,
            Set.of(CrudOperation.READ));
    final TableUsage searchPathDepartment =
        new TableUsage(
            List.of(department), SchemaResolution.SEARCH_PATH, Set.of(CrudOperation.UPDATE));

    final var byResolution =
        FunctionTableUsage.analyzed(
                List.of(synonym, determined, searchPathEmployee, searchPathDepartment),
                List.of(),
                false,
                false)
            .schemaUndeterminedTables();

    assertEquals(
        List.of(SchemaResolution.SEARCH_PATH, SchemaResolution.SYNONYM),
        List.copyOf(byResolution.keySet()));
    assertEquals(
        List.of(searchPathEmployee, searchPathDepartment),
        byResolution.get(SchemaResolution.SEARCH_PATH));
    assertEquals(List.of(synonym), byResolution.get(SchemaResolution.SYNONYM));
    assertTrue(
        FunctionTableUsage.analyzed(List.of(determined), List.of(), false, false)
            .schemaUndeterminedTables()
            .isEmpty());
  }

  @Test
  @DisplayName("FunctionTableUsage: 解析しなかった結果は表・動的SQLを持たず、NOT_ANALYZEDだけを抽出を行わなかったものとする")
  void testFactories() {
    assertFalse(FunctionTableUsage.notAnalyzed().isAttempted());
    final FunctionTableUsage wrapped = FunctionTableUsage.notAnalyzable(TableUsageStatus.WRAPPED);
    assertTrue(wrapped.isAttempted());
    assertEquals(List.of(), wrapped.tables());
    final FunctionTableUsage python = FunctionTableUsage.unsupportedLanguage("plpython3u");
    assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, python.status());
    assertEquals("plpython3u", python.language());
    final FunctionTableUsage analyzed =
        FunctionTableUsage.analyzed(List.of(), List.of(DynamicSqlKind.EXECUTE), true, false);
    assertEquals(TableUsageStatus.ANALYZED, analyzed.status());
    assertEquals("", analyzed.language());
    assertEquals(List.of(DynamicSqlKind.EXECUTE), analyzed.dynamicSql());
    assertTrue(analyzed.incomplete());
    assertFalse(analyzed.overloadsMerged());
  }

  @Test
  @DisplayName("CrudOperation・DynamicSqlKind: 定義書・参考情報に出す表記を持つ")
  void testLabels() {
    assertEquals(
        "CRUD",
        String.join(
            "", EnumSet.allOf(CrudOperation.class).stream().map(CrudOperation::letter).toList()));
    assertEquals("EXECUTE IMMEDIATE", DynamicSqlKind.EXECUTE_IMMEDIATE.label());
    assertEquals("dblink", DynamicSqlKind.DBLINK.label());
  }

  @Test
  @DisplayName("TableUsageStatus・DynamicSqlKind: 参考情報（JSON）に出す値は、定数の名前によらず固定する")
  void testIds() {
    assertEquals(
        List.of(
            "not_analyzed",
            "analyzed",
            "unsupported_language",
            "no_definition",
            "wrapped",
            "subprogram_not_found"),
        EnumSet.allOf(TableUsageStatus.class).stream().map(TableUsageStatus::id).toList());
    assertEquals(
        List.of("EXECUTE", "EXECUTE IMMEDIATE", "DBMS_SQL", "OPEN FOR", "dblink"),
        EnumSet.allOf(DynamicSqlKind.class).stream().map(DynamicSqlKind::id).toList());
  }
}
