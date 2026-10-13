package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ViewReferences のビュー・参照されるテーブルの両方向からの取得に関するテスト */
class ViewReferencesTest {

  private static final ViewReferenceEntity VIEW_TO_ORDERS =
      new ViewReferenceEntity("public", "v", TableType.VIEW, "public", "orders", TableType.TABLE);
  private static final ViewReferenceEntity VIEW_TO_ITEMS =
      new ViewReferenceEntity("public", "v", TableType.VIEW, "public", "items", TableType.TABLE);
  private static final ViewReferenceEntity MV_TO_ORDERS =
      new ViewReferenceEntity(
          "sales", "mv", TableType.MATERIALIZED_VIEW, "public", "orders", TableType.TABLE);

  private final ViewReferences references =
      ViewReferences.of(List.of(VIEW_TO_ORDERS, VIEW_TO_ITEMS, MV_TO_ORDERS));

  @Test
  @DisplayName("belongingTo: ビューが参照するテーブルを取得順に返す")
  void testBelongingToReturnsReferencedTables() {
    assertEquals(
        List.of(VIEW_TO_ORDERS, VIEW_TO_ITEMS), references.belongingTo(table("public", "v")));
  }

  @Test
  @DisplayName("referencingTo: テーブルを参照しているビューを、スキーマをまたいで返す")
  void testReferencingToReturnsViews() {
    assertEquals(
        List.of(VIEW_TO_ORDERS, MV_TO_ORDERS), references.referencingTo(table("public", "orders")));
  }

  @Test
  @DisplayName("belongingTo・referencingTo: 該当が無い場合は空のリストを返す")
  void testReturnsEmptyWhenAbsent() {
    assertEquals(List.of(), references.belongingTo(table("public", "orders")));
    assertEquals(List.of(), references.referencingTo(table("public", "v")));
  }

  private static TableEntity table(String schema, String name) {
    return new TableEntity("TEST_DB", schema, "", name, TableType.TABLE, "", "");
  }
}
