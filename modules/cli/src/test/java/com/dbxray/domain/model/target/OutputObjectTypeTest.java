package com.dbxray.domain.model.target;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OutputObjectType の逆引き・出力対象種別解決に関するテスト */
public class OutputObjectTypeTest {

  @Test
  @DisplayName("fromId: 各値が対応するEnumに解決される")
  void testFindByName() {
    assertEquals(OutputObjectType.TRIGGER, OutputObjectType.fromId("trigger"));
    assertEquals(OutputObjectType.FUNCTION, OutputObjectType.fromId("function"));
    assertEquals(OutputObjectType.SEQUENCE, OutputObjectType.fromId("sequence"));
    assertEquals(OutputObjectType.TYPE, OutputObjectType.fromId("type"));
  }

  @Test
  @DisplayName("fromId: 未知の値の場合はIllegalArgumentExceptionをスローする")
  void testFindByNameUnknownThrows() {
    assertThrows(IllegalArgumentException.class, () -> OutputObjectType.fromId("unknown"));
  }

  @Test
  @DisplayName("parse: 空リストの場合は全種別を返す")
  void testParseEmptyReturnsAllTypes() {
    assertEquals(EnumSet.allOf(OutputObjectType.class), OutputObjectType.parse(List.of()));
  }

  @Test
  @DisplayName("parse: 指定した種別のみを含む集合を返す")
  void testParseReturnsOnlySpecifiedTypes() {
    final Set<OutputObjectType> result = OutputObjectType.parse(List.of("function", "sequence"));
    assertEquals(Set.of(OutputObjectType.FUNCTION, OutputObjectType.SEQUENCE), result);
  }

  @Test
  @DisplayName("parse: 前後の空白を除去して解釈し、空要素は無視する")
  void testParseStripsNames() {
    // 「--output-objects=trigger, function」のようにカンマの後へ空白を入れた場合を想定する
    final Set<OutputObjectType> result =
        OutputObjectType.parse(List.of("trigger", " function ", ""));
    assertEquals(Set.of(OutputObjectType.TRIGGER, OutputObjectType.FUNCTION), result);
  }

  @Test
  @DisplayName("parse: 空白のみの要素しかない場合は全種別を返す")
  void testParseBlankOnlyReturnsAllTypes() {
    assertEquals(EnumSet.allOf(OutputObjectType.class), OutputObjectType.parse(List.of(" ")));
  }

  @Test
  @DisplayName("parse: 未知の値が含まれる場合はIllegalArgumentExceptionをスローする")
  void testParseUnknownThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OutputObjectType.parse(List.of("function", "unknown")));
  }

  @Test
  @DisplayName("getName: 各Enum値に対応する種別名を返す")
  void testGetName() {
    assertEquals("trigger", OutputObjectType.TRIGGER.id());
    assertEquals("function", OutputObjectType.FUNCTION.id());
    assertEquals("sequence", OutputObjectType.SEQUENCE.id());
    assertEquals("type", OutputObjectType.TYPE.id());
  }
}
