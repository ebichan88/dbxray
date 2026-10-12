package com.dbxray.domain.model.viewpoint;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Viewpoint の生成時の検証・所属テーブルの判定・出力内容の組み立てに関するテスト */
public class ViewpointTest {

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "");
  }

  @Test
  @DisplayName("of: 識別子・表示名・説明の前後の空白を除去し、表示名を省略した場合は識別子を表示名とする")
  void testOfNormalizesValues() {
    Viewpoint named = Viewpoint.of(" order ", " 受注管理 ", " 受注の流れ \n", List.of("orders"));
    // 表示名・説明の未指定は、null・空白のどちらでも同じに扱う
    Viewpoint omitted = Viewpoint.of("order", null, null, List.of("orders"));
    Viewpoint blank = Viewpoint.of("order", " ", "", List.of("orders"));

    assertEquals("order", named.id());
    assertEquals("受注管理", named.name());
    assertEquals("受注の流れ", named.description());
    assertEquals("order", omitted.name());
    assertEquals("", omitted.description());
    assertEquals("order", blank.name());
    assertEquals("", blank.description());
  }

  @Test
  @DisplayName("of: 識別子が空、またはファイル名に使えない文字（日本語・空白・/等）を含む場合は誤りとする")
  void testOfRejectsInvalidId() {
    for (String id : new String[] {"", " ", "受注", "order list", "order/list", null}) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> Viewpoint.of(id, "受注管理", "", List.of("orders")),
              "id=" + id);
      assertTrue(e.getMessage().contains("Invalid viewpoint id"));
    }
    assertDoesNotThrow(() -> Viewpoint.of("Order-01_a", "", "", List.of("orders")));
  }

  @Test
  @DisplayName("of: 包含パターンが無い（未指定・除外パターンのみ）場合と、テーブル名パターンの書き誤りは誤りとする")
  void testOfRejectsInvalidTablePatterns() {
    IllegalArgumentException noInclusion =
        assertThrows(
            IllegalArgumentException.class,
            () -> Viewpoint.of("order", "", "", List.of("!orders_bk")));
    assertTrue(noInclusion.getMessage().contains("no table pattern to include"));
    assertThrows(IllegalArgumentException.class, () -> Viewpoint.of("order", "", "", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> Viewpoint.of("order", "", "", List.of("sales.")));
  }

  @Test
  @DisplayName("contains: table=と同じ記法（ワイルドカード・除外・スキーマ修飾）で所属テーブルを判定する")
  void testContains() {
    Viewpoint viewpoint =
        Viewpoint.of("order", "", "", List.of("sales.order*", "customer", "!sales.order_bk"));

    assertTrue(viewpoint.contains(table("sales", "orders")));
    assertTrue(viewpoint.contains(table("sales", "order_detail")));
    assertTrue(viewpoint.contains(table("crm", "customer")));
    assertFalse(viewpoint.contains(table("sales", "order_bk")));
    assertFalse(viewpoint.contains(table("other", "orders")));
  }

  @Test
  @DisplayName("unmatchedPatterns: 出力対象のどのテーブルにも一致しない包含パターンを返す")
  void testUnmatchedPatterns() {
    Viewpoint viewpoint = Viewpoint.of("order", "", "", List.of("sales.order*", "sales.custmer"));

    assertEquals(
        List.of("sales.custmer"),
        viewpoint.unmatchedPatterns(Tables.of(List.of(table("sales", "orders")))));
  }

  @Test
  @DisplayName("resolve: 所属テーブルを出力対象の並び順で求め、関連を観点内（両端が所属）と観点外（片端だけが所属）に分ける")
  void testResolve() {
    TableEntity orders = table("sales", "orders");
    TableEntity customer = table("sales", "customer");
    TableEntity product = table("sales", "product");
    TableEntity stock = table("inventory", "stock");
    ForeignKeyEntity ordersToCustomer =
        ForeignKeyFixtures.physical("sales", "orders", "fk_orders_customer", "sales", "customer");
    ForeignKeyEntity ordersToProduct =
        ForeignKeyFixtures.logical("sales", "orders", "rel_orders_product", "sales", "product");
    ForeignKeyEntity stockToProduct =
        ForeignKeyFixtures.physical("inventory", "stock", "fk_stock_product", "sales", "product");
    Viewpoint viewpoint = Viewpoint.of("order", "", "", List.of("sales.orders", "sales.customer"));

    ViewpointContent content =
        viewpoint.resolve(
            Tables.of(List.of(customer, orders, product, stock)),
            ForeignKeys.of(List.of(ordersToCustomer, ordersToProduct, stockToProduct)));

    assertSame(viewpoint, content.viewpoint());
    assertEquals(List.of(customer, orders), content.tables());
    assertEquals(List.of(ordersToCustomer), content.relations().foreignKeys());
    assertEquals(
        List.of(TableKey.of("sales", "customer"), TableKey.of("sales", "orders")),
        content.relations().nodes());
    assertEquals(List.of(ordersToProduct), content.outsideRelations());
  }
}
