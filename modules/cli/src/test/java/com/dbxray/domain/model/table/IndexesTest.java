package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Indexes のテーブルキーによるインデックス化に関するテスト */
public class IndexesTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("TEST_DB", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 自テーブルに属するインデックスのみを、登録順を保って返す")
  void testOfReturnsOwnIndexesInOrder() {
    var idx1 = new IndexEntity("public", "orders", "idx_orders_1", "", false, false, "", "");
    var idx2 = new IndexEntity("public", "orders", "idx_orders_2", "", false, false, "", "");
    var other = EntityFixtures.index("public", "customers");
    var indexes = Indexes.of(List.of(idx1, idx2, other));

    assertEquals(List.of(idx1, idx2), indexes.belongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("of: 該当するインデックスがないテーブルには空リストを返す")
  void testOfReturnsEmptyForUnknownTable() {
    var indexes = Indexes.of(List.of(EntityFixtures.index("public", "orders")));

    assertEquals(List.of(), indexes.belongingTo(newTable("public", "unknown")));
  }

  @Test
  @DisplayName("of: 同名テーブルでもスキーマが異なれば別のキーとして扱う")
  void testOfDistinguishesSameTableNameAcrossSchemas() {
    var publicIndex = EntityFixtures.index("public", "orders");
    var salesIndex = EntityFixtures.index("sales", "orders");
    var indexes = Indexes.of(List.of(publicIndex, salesIndex));

    assertEquals(List.of(publicIndex), indexes.belongingTo(newTable("public", "orders")));
    assertEquals(List.of(salesIndex), indexes.belongingTo(newTable("sales", "orders")));
  }
}
