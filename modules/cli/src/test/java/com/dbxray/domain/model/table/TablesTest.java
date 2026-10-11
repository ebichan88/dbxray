package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tables のスキーマ単位のまとまり・テーブルキーによる検索に関するテスト */
public class TablesTest {

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "");
  }

  @Test
  @DisplayName("bySchema: スキーマ単位にまとめ、スキーマ・テーブルの並びは取得順を保つ")
  void testBySchemaKeepsOrder() {
    var sales1 = table("sales", "orders");
    var public1 = table("public", "customers");
    var sales2 = table("sales", "items");
    Tables tables = Tables.of(List.of(sales1, public1, sales2));

    var bySchema = tables.bySchema();

    assertEquals(List.of("sales", "public"), List.copyOf(bySchema.keySet()));
    assertEquals(List.of(sales1, sales2), bySchema.get("sales"));
    assertEquals(List.of(public1), bySchema.get("public"));
  }

  @Test
  @DisplayName("byTableName: 物理テーブル名でまとめ、同じ名前のテーブル（スキーマ違い）は取得順に並べる")
  void testByTableName() {
    var salesOrders = table("sales", "orders");
    var customers = table("public", "customers");
    var publicOrders = table("public", "orders");
    Tables tables = Tables.of(List.of(salesOrders, customers, publicOrders));

    var byTableName = tables.byTableName();

    assertEquals(List.of("orders", "customers"), List.copyOf(byTableName.keySet()));
    assertEquals(List.of(salesOrders, publicOrders), byTableName.get("orders"));
  }

  @Test
  @DisplayName("contains/find: テーブルキーで出力対象のテーブルを引ける")
  void testContainsAndFind() {
    var orders = table("public", "orders");
    Tables tables = Tables.of(List.of(orders));

    assertTrue(tables.contains(TableKey.of("public", "orders")));
    assertFalse(tables.contains(TableKey.of("sales", "orders")));
    assertEquals(Optional.of(orders), tables.find(TableKey.of("public", "orders")));
    assertEquals(Optional.empty(), tables.find(TableKey.of("public", "customers")));
  }

  @Test
  @DisplayName("isEmpty/asList: 空の集合と、取得順のリスト")
  void testIsEmptyAndAsList() {
    assertTrue(Tables.of(List.of()).isEmpty());
    var a = table("public", "a");
    var b = table("public", "b");
    Tables tables = Tables.of(List.of(a, b));
    assertFalse(tables.isEmpty());
    assertEquals(List.of(a, b), tables.asList());
  }

  @Test
  @DisplayName("stream: 渡した順序のままテーブルを返す")
  void testStreamKeepsOrder() {
    var orders = table("public", "orders");
    var customers = table("sales", "customers");

    assertEquals(
        List.of(orders, customers), Tables.of(List.of(orders, customers)).stream().toList());
  }
}
