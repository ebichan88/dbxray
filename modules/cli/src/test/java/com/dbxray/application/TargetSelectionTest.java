package com.dbxray.application;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.target.OutputObjectType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TargetSelection の生成（設定値の型への変換・検証）に関するテスト */
public class TargetSelectionTest {

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 出力対象オブジェクト種別名を種別の集合へ変換する")
  void testOfParsesOutputObjectTypes() {
    TargetSelection selection =
        TargetSelection.of(List.of(), List.of(), List.of("trigger", "function"));

    assertEquals(
        Set.of(OutputObjectType.TRIGGER, OutputObjectType.FUNCTION), selection.outputObjectTypes());
  }

  @Test
  @DisplayName("of: 出力対象オブジェクト種別が未指定の場合は全種別を対象とする")
  void testOfDefaultsToAllOutputObjectTypes() {
    TargetSelection selection = TargetSelection.of(List.of(), List.of(), List.of());

    assertEquals(EnumSet.allOf(OutputObjectType.class), selection.outputObjectTypes());
  }

  @Test
  @DisplayName("of: 未知の出力対象オブジェクト種別名はIllegalArgumentExceptionで検知する")
  void testOfRejectsUnknownOutputObjectType() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetSelection.of(List.of(), List.of(), List.of("trigers")));
    assertTrue(e.getMessage().contains("trigers"));
  }

  @Test
  @DisplayName("of: スキーマ・テーブルの指定から出力対象の範囲を組み立てる")
  void testOfBuildsTableScope() {
    TargetSelection selection = TargetSelection.of(List.of("sample"), List.of("!tmp_*"), List.of());

    assertEquals(List.of("sample"), selection.tableScope().schemaNames());
    assertTrue(selection.tableScope().matches(table("sample", "employee")));
    assertFalse(selection.tableScope().matches(table("sample", "tmp_work")));
    assertFalse(selection.tableScope().matches(table("public", "employee")));
  }

  @Test
  @DisplayName("of: テーブル名パターンと出力対象オブジェクト種別の両方に誤りがある場合は、まとめて（1行に1件）示す")
  void testOfReportsBothErrorsAtOnce() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetSelection.of(List.of(), List.of("sample."), List.of("trigers")));

    List<String> lines = e.getMessage().lines().toList();
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("sample."));
    assertTrue(lines.get(1).contains("trigers"));
  }
}
