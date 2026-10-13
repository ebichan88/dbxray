package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableDetail の組み立て（複数テーブル分の詳細情報のテーブルごとへの振り分け）に関するテスト */
public class TableDetailTest {

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("assembleAll: 詳細情報を所属するテーブルごとに振り分け、テーブルの並びを保つ")
  void testAssembleAllGroupsByTable() {
    var orders = table("public", "orders");
    var customers = table("public", "customers");
    var ordersId = EntityFixtures.column("public", "orders", "id", "int", true);
    var customersId = EntityFixtures.column("public", "customers", "id", "int", true);
    var ordersIndex = EntityFixtures.index("public", "orders");
    var customersConstraint = EntityFixtures.constraint("public", "customers");

    List<TableDetail> details =
        TableDetail.assembleAll(
            List.of(orders, customers),
            List.of(customersId, ordersId),
            List.of(ordersIndex),
            List.of(customersConstraint));

    assertEquals(
        List.of(
            new TableDetail(orders, List.of(ordersId), List.of(ordersIndex), List.of()),
            new TableDetail(
                customers, List.of(customersId), List.of(), List.of(customersConstraint))),
        details);
  }

  @Test
  @DisplayName("assembleAll: 同名のテーブルでもスキーマが異なる詳細情報は振り分けない")
  void testAssembleAllDistinguishesSchemas() {
    var publicOrders = table("public", "orders");
    var salesOrdersId = EntityFixtures.column("sales", "orders", "id", "int", true);

    List<TableDetail> details =
        TableDetail.assembleAll(
            List.of(publicOrders), List.of(salesOrdersId), List.of(), List.of());

    assertEquals(List.of(), details.get(0).columns());
  }
}
