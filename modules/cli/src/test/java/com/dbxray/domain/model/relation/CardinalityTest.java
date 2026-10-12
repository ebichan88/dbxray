package com.dbxray.domain.model.relation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cardinality の多重度判定テスト */
public class CardinalityTest {

  @Test
  @DisplayName("of: 一意でなくNOT NULLの場合は1対多")
  void testOneToMany() {
    assertEquals(Cardinality.ONE_TO_MANY, Cardinality.of(false, true));
    assertEquals("||--o{", Cardinality.ONE_TO_MANY.notation(RelationType.PHYSICAL));
  }

  @Test
  @DisplayName("of: 一意でなくNULLを許容する場合は0..1対多")
  void testOptionalOneToMany() {
    assertEquals(Cardinality.OPTIONAL_ONE_TO_MANY, Cardinality.of(false, false));
    assertEquals("|o--o{", Cardinality.OPTIONAL_ONE_TO_MANY.notation(RelationType.PHYSICAL));
  }

  @Test
  @DisplayName("of: 一意かつNOT NULLの場合は1対1")
  void testOneToOne() {
    assertEquals(Cardinality.ONE_TO_ONE, Cardinality.of(true, true));
    assertEquals("||--o|", Cardinality.ONE_TO_ONE.notation(RelationType.PHYSICAL));
  }

  @Test
  @DisplayName("of: 一意だがNULLを許容する場合は0..1対1")
  void testOptionalOneToOne() {
    assertEquals(Cardinality.OPTIONAL_ONE_TO_ONE, Cardinality.of(true, false));
    assertEquals("|o--o|", Cardinality.OPTIONAL_ONE_TO_ONE.notation(RelationType.PHYSICAL));
  }

  @Test
  @DisplayName("notation: 論理リレーションは破線（非識別関連）で描画する")
  void testLogicalNotationIsDashed() {
    assertEquals("||..o{", Cardinality.ONE_TO_MANY.notation(RelationType.LOGICAL));
    assertEquals("|o..o{", Cardinality.OPTIONAL_ONE_TO_MANY.notation(RelationType.LOGICAL));
    assertEquals("||..o|", Cardinality.ONE_TO_ONE.notation(RelationType.LOGICAL));
    assertEquals("|o..o|", Cardinality.OPTIONAL_ONE_TO_ONE.notation(RelationType.LOGICAL));
  }

  @Test
  @DisplayName("notation: 由来が異なれば端点は同じでも線種で区別される")
  void testNotationDiffersByRelationType() {
    for (Cardinality cardinality : Cardinality.values()) {
      assertNotEquals(
          cardinality.notation(RelationType.PHYSICAL),
          cardinality.notation(RelationType.LOGICAL),
          cardinality.name());
    }
  }

  @Test
  @DisplayName("notation: 子側が「1以上」となる表記は持たない")
  void testNoMandatoryChildNotation() {
    // 「親1件につき子が1件以上存在すること」はテーブル定義では表現できない
    for (Cardinality cardinality : Cardinality.values()) {
      for (RelationType relationType : RelationType.values()) {
        final String notation = cardinality.notation(relationType);
        assertFalse(notation.endsWith("|{"), cardinality.name());
        assertFalse(notation.endsWith("--||"), cardinality.name());
        assertFalse(notation.endsWith("..||"), cardinality.name());
      }
    }
  }

  @Test
  @DisplayName("label: すべての多重度にラベルが定義されている")
  void testLabelDefined() {
    for (Cardinality cardinality : Cardinality.values()) {
      assertNotNull(cardinality.label());
      assertFalse(cardinality.label().isEmpty(), cardinality.name());
    }
  }

  @Test
  @DisplayName("fromLabel: 日本語ラベルから多重度を逆引きできる")
  void testFromLabel() {
    for (Cardinality cardinality : Cardinality.values()) {
      assertEquals(
          cardinality,
          Cardinality.fromLabel(cardinality.label()).orElseThrow(),
          cardinality.name());
    }
    // 前後の空白は無視する
    assertEquals(Cardinality.ONE_TO_MANY, Cardinality.fromLabel("  1対多  ").orElseThrow());
  }

  @Test
  @DisplayName("fromLabel: 未知・未指定のラベルは空を返す")
  void testFromLabelUnknown() {
    assertTrue(Cardinality.fromLabel(null).isEmpty());
    assertTrue(Cardinality.fromLabel("").isEmpty());
    assertTrue(Cardinality.fromLabel("   ").isEmpty());
    assertTrue(Cardinality.fromLabel("多対多").isEmpty());
  }

  @Test
  @DisplayName("DEFAULT_FOR_LOGICAL_RELATION: 論理リレーションの既定値は1対多")
  void testDefaultForLogicalRelation() {
    assertEquals(Cardinality.ONE_TO_MANY, Cardinality.DEFAULT_FOR_LOGICAL_RELATION);
  }
}
