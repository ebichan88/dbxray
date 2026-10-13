package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableEntity のパーティション表の判定に関するテスト */
public class TableEntityTest {

  @Test
  @DisplayName("isPartitioned: パーティションキーを持つテーブルはパーティション表")
  void testIsPartitionedWithPartitionKey() {
    var table =
        new TableEntity("testdb", "public", "", "sales", TableType.TABLE, "", "RANGE (sold_on)");

    assertTrue(table.isPartitioned());
  }

  @Test
  @DisplayName("isPartitioned: パーティションキーを持たないテーブルはパーティション表ではない")
  void testIsPartitionedWithoutPartitionKey() {
    var table = new TableEntity("testdb", "public", "", "customer", TableType.TABLE, "", "");

    assertEquals("", table.partitionKey());
    assertFalse(table.isPartitioned());
  }
}
