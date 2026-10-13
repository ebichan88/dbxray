package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Triggers のテーブルキーによるインデックス化・取得順の保持・空判定に関するテスト */
public class TriggersTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("TEST_DB", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 自テーブルに属するトリガーのみを、登録順を保って返す")
  void testOfReturnsOwnTriggersInOrder() {
    var t1 =
        new TriggerEntity(
            "public", "orders", "trg_orders_1", "BEFORE", List.of("INSERT"), "ROW", "", "", "");
    var t2 =
        new TriggerEntity(
            "public", "orders", "trg_orders_2", "AFTER", List.of("UPDATE"), "ROW", "", "", "");
    var other =
        new TriggerEntity(
            "public",
            "customers",
            "trg_customers_1",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "",
            "",
            "");
    var triggers = Triggers.of(List.of(t1, t2, other));

    assertEquals(List.of(t1, t2), triggers.belongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("of: 該当するトリガーがないテーブルには空リストを返す")
  void testOfReturnsEmptyForUnknownTable() {
    var triggers = Triggers.of(List.of(EntityFixtures.trigger("public", "orders")));

    assertEquals(List.of(), triggers.belongingTo(newTable("public", "unknown")));
  }

  @Test
  @DisplayName("of: 同名テーブルでもスキーマが異なれば別のキーとして扱う")
  void testOfDistinguishesSameTableNameAcrossSchemas() {
    var publicTrigger = EntityFixtures.trigger("public", "orders");
    var salesTrigger = EntityFixtures.trigger("sales", "orders");
    var triggers = Triggers.of(List.of(publicTrigger, salesTrigger));

    assertEquals(List.of(publicTrigger), triggers.belongingTo(newTable("public", "orders")));
    assertEquals(List.of(salesTrigger), triggers.belongingTo(newTable("sales", "orders")));
  }

  @Test
  @DisplayName("asList: テーブルをまたいでも渡した順序のまま返す")
  void testAsListKeepsFetchOrder() {
    var a1 = EntityFixtures.trigger("public", "orders");
    var b = EntityFixtures.trigger("public", "customers");
    var a2 = EntityFixtures.trigger("public", "orders");

    assertEquals(List.of(a1, b, a2), Triggers.of(List.of(a1, b, a2)).asList());
  }

  @Test
  @DisplayName("isEmpty: 1件も無い場合のみtrueを返す")
  void testIsEmpty() {
    assertTrue(Triggers.of(List.of()).isEmpty());
    assertFalse(Triggers.of(List.of(EntityFixtures.trigger("public", "orders"))).isEmpty());
  }
}
