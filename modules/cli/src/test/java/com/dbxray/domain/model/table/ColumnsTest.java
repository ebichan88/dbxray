package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Columns のテーブルキーによるインデックス化に関するテスト */
public class ColumnsTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("TEST_DB", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 自テーブルに属するカラムのみを、登録順を保って返す")
  void testOfReturnsOwnColumnsInOrder() {
    var id = EntityFixtures.column("public", "orders", "id", "int", true);
    var name = EntityFixtures.column("public", "orders", "name", "varchar", false);
    var other = EntityFixtures.column("public", "customers", "id", "int", true);
    var columns = Columns.of(List.of(id, name, other));

    assertEquals(List.of(id, name), columns.belongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("of: 該当するカラムがないテーブルには空リストを返す")
  void testOfReturnsEmptyForUnknownTable() {
    var columns = Columns.of(List.of(EntityFixtures.column("public", "orders", "id", "int", true)));

    assertEquals(List.of(), columns.belongingTo(newTable("public", "unknown")));
  }

  @Test
  @DisplayName("of: 同名テーブルでもスキーマが異なれば別のキーとして扱う")
  void testOfDistinguishesSameTableNameAcrossSchemas() {
    var publicCol = EntityFixtures.column("public", "orders", "id", "int", true);
    var salesCol = EntityFixtures.column("sales", "orders", "id", "int", true);
    var columns = Columns.of(List.of(publicCol, salesCol));

    assertEquals(List.of(publicCol), columns.belongingTo(newTable("public", "orders")));
    assertEquals(List.of(salesCol), columns.belongingTo(newTable("sales", "orders")));
  }

  @Test
  @DisplayName("of: 入力リストで他テーブルの行と入り交じっていても、同一テーブルの行は集約される")
  void testOfAggregatesInterleavedRows() {
    var ordersId = EntityFixtures.column("public", "orders", "id", "int", true);
    var customersId = EntityFixtures.column("public", "customers", "id", "int", true);
    var ordersName = EntityFixtures.column("public", "orders", "name", "varchar", false);
    var columns = Columns.of(List.of(ordersId, customersId, ordersName));

    assertEquals(List.of(ordersId, ordersName), columns.belongingTo(newTable("public", "orders")));
  }
}
