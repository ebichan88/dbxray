package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableKey のファクトリメソッド・等価性に関するテスト */
public class TableKeyTest {

  @Test
  @DisplayName("of(schema, table): 指定した値を保持するTableKeyを生成する")
  void testOfWithSchemaAndTable() {
    TableKey key = TableKey.of("public", "orders");
    assertEquals("public", key.schema());
    assertEquals("orders", key.table());
  }

  @Test
  @DisplayName("of(TableEntity): スキーマ名・物理テーブル名からTableKeyを生成する")
  void testOfWithTableEntity() {
    TableEntity table =
        new TableEntity("testdb", "public", "受注", "orders", TableType.TABLE, "", "");
    TableKey key = TableKey.of(table);
    assertEquals(new TableKey("public", "orders"), key);
  }

  @Test
  @DisplayName("of(TableEntity): 論理テーブル名やDB名の違いはキーに影響しない")
  void testOfWithTableEntityIgnoresLogicalNameAndDbName() {
    TableEntity a = new TableEntity("db1", "public", "論理名A", "orders", TableType.TABLE, "", "");
    TableEntity b = new TableEntity("db2", "public", "論理名B", "orders", TableType.VIEW, "", "");
    assertEquals(TableKey.of(a), TableKey.of(b));
  }

  @Test
  @DisplayName("equals/hashCode: スキーマ・テーブル名が同じキー同士は等価")
  void testEqualsAndHashCode() {
    TableKey a = TableKey.of("public", "orders");
    TableKey b = TableKey.of("public", "orders");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  @DisplayName("equals: スキーマまたはテーブル名が異なれば非等価")
  void testEqualsDiffersWhenSchemaOrTableDiffers() {
    TableKey base = TableKey.of("public", "orders");
    assertNotEquals(base, TableKey.of("sales", "orders"));
    assertNotEquals(base, TableKey.of("public", "customers"));
  }

  @Test
  @DisplayName("parse: 'スキーマ.テーブル'形式を解析する。前後の空白はトリムする")
  void testParseValid() {
    assertEquals(Optional.of(TableKey.of("public", "orders")), TableKey.parse("public.orders"));
    assertEquals(
        Optional.of(TableKey.of("public", "orders")), TableKey.parse("  public . orders  "));
  }

  @Test
  @DisplayName("parse: 最初のドットで分割する（テーブル名にドットが含まれる場合も対応）")
  void testParseSplitsAtFirstDot() {
    assertEquals(
        Optional.of(TableKey.of("public", "v1.orders")), TableKey.parse("public.v1.orders"));
  }

  @Test
  @DisplayName("qualifiedName: 'スキーマ.テーブル'形式の文字列を返す")
  void testQualifiedName() {
    assertEquals("public.orders", TableKey.of("public", "orders").qualifiedName());
  }

  @Test
  @DisplayName("parse: null・空白・ドット無し・トリム後にスキーマ/テーブル名が空の場合は空を返す")
  void testParseInvalid() {
    assertEquals(Optional.empty(), TableKey.parse(null));
    assertEquals(Optional.empty(), TableKey.parse(""));
    assertEquals(Optional.empty(), TableKey.parse("   "));
    assertEquals(Optional.empty(), TableKey.parse("no_dot"));
    assertEquals(Optional.empty(), TableKey.parse(".orders"));
    assertEquals(Optional.empty(), TableKey.parse("public."));
    assertEquals(Optional.empty(), TableKey.parse(" . "));
  }
}
