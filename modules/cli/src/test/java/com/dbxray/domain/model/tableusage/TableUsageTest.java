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
  @DisplayName("FunctionTableUsage.notAnalyzable: 言語の名前を持てないUNSUPPORTED_LANGUAGEは渡せない")
  void testNotAnalyzableRejectsUnsupportedLanguage() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FunctionTableUsage.notAnalyzable(TableUsageStatus.UNSUPPORTED_LANGUAGE));
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
}
