package com.dbxray.domain.model.relation;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DiagramBoxes の論理テーブル名・関連カラムの求め方に関するテスト */
public class DiagramBoxesTest {

  private static final TableKey ORDERS = TableKey.of("public", "orders");
  private static final TableKey CUSTOMERS = TableKey.of("public", "customers");
  private static final TableKey PRODUCTS = TableKey.of("public", "products");

  private final ForeignKeyEntity ordersToCustomers =
      relation("orders", "fk_orders_customers", List.of("customer_id"), "customers", List.of("id"));
  private final ForeignKeyEntity ordersToProducts =
      relation("orders", "fk_orders_products", List.of("product_id"), "products", List.of("id"));

  private static ForeignKeyEntity relation(
      String table,
      String name,
      List<String> columnNames,
      String refTable,
      List<String> refColumnNames) {
    return new ForeignKeyEntity(
        "public",
        table,
        name,
        columnNames,
        "public",
        refTable,
        refColumnNames,
        Cardinality.ONE_TO_MANY,
        RelationType.PHYSICAL);
  }

  private static ColumnEntity column(String table, String name, boolean primaryKey) {
    return EntityFixtures.column("public", table, name, "integer", primaryKey);
  }

  private static TableEntity table(String physical, String logical) {
    return new TableEntity("TEST_DB", "public", logical, physical, TableType.TABLE, "", "");
  }

  private DiagramBoxes.Builder builder(List<ForeignKeyEntity> foreignKeys) {
    return DiagramBoxes.builder(
        Tables.of(List.of(table("orders", "受注"), table("customers", ""))),
        ForeignKeys.of(foreignKeys));
  }

  @Test
  @DisplayName("logicalTableName: 論理テーブル名を返し、論理名が無い・出力対象に無いテーブルは空文字")
  void testLogicalTableName() {
    var boxes = builder(List.of()).build();

    assertEquals("受注", boxes.logicalTableName(ORDERS));
    assertEquals("", boxes.logicalTableName(CUSTOMERS));
    assertEquals("", boxes.logicalTableName(PRODUCTS));
  }

  @Test
  @DisplayName("Builder.tableKeys: 関連の参照元・参照先のテーブルを重複なく返す")
  void testBuilderTableKeys() {
    assertEquals(
        List.of(ORDERS, CUSTOMERS, PRODUCTS),
        builder(List.of(ordersToCustomers, ordersToProducts)).tablesNeedingColumns());
  }

  @Test
  @DisplayName("relationColumnsOf: 描画する関連で使われるカラムだけを、カラムの定義順で返す")
  void testRelationColumnsOfOnlyDrawnRelations() {
    var productId = column("orders", "product_id", false);
    var customerId = column("orders", "customer_id", false);
    var boxes =
        builder(List.of(ordersToCustomers, ordersToProducts))
            .collectRelatedColumns(List.of(column("orders", "id", true), productId, customerId))
            .build();

    assertEquals(
        List.of(new DiagramColumn(productId, true), new DiagramColumn(customerId, true)),
        boxes
            .relationColumnsOf(List.of(ordersToCustomers, ordersToProducts))
            .getOrDefault(ORDERS, List.of()));
    assertEquals(
        List.of(new DiagramColumn(customerId, true)),
        boxes.relationColumnsOf(List.of(ordersToCustomers)).getOrDefault(ORDERS, List.of()));
  }

  @Test
  @DisplayName("relationColumnsOf: 参照先のカラムは外部キーとしない")
  void testRelationColumnsOfReferencedSide() {
    var id = column("customers", "id", true);
    var boxes =
        builder(List.of(ordersToCustomers))
            .collectRelatedColumns(List.of(id, column("customers", "name", false)))
            .build();

    assertEquals(
        List.of(new DiagramColumn(id, false)),
        boxes.relationColumnsOf(List.of(ordersToCustomers)).getOrDefault(CUSTOMERS, List.of()));
  }

  @Test
  @DisplayName("relationColumnsOf: 自己参照の関連は参照元・参照先の両方のカラムを返す")
  void testRelationColumnsOfSelfReference() {
    var selfReference =
        relation("orders", "fk_orders_parent", List.of("parent_id"), "orders", List.of("id"));
    var id = column("orders", "id", true);
    var parentId = column("orders", "parent_id", false);
    var boxes =
        builder(List.of(selfReference)).collectRelatedColumns(List.of(id, parentId)).build();

    assertEquals(
        List.of(new DiagramColumn(id, false), new DiagramColumn(parentId, true)),
        boxes.relationColumnsOf(List.of(selfReference)).getOrDefault(ORDERS, List.of()));
  }

  @Test
  @DisplayName("relationColumnsOf: 取得できなかったカラム（実在しないカラム）は含めない")
  void testRelationColumnsOfSkipsMissingColumns() {
    var boxes = builder(List.of(ordersToCustomers)).collectRelatedColumns(List.of()).build();

    assertEquals(
        List.of(),
        boxes.relationColumnsOf(List.of(ordersToCustomers)).getOrDefault(CUSTOMERS, List.of()));
  }

  @Test
  @DisplayName("relationColumnsOf: 別スキーマの同名テーブルのカラムは関連カラムとしない")
  void testRelationColumnsOfIgnoresOtherSchema() {
    var boxes =
        builder(List.of(ordersToCustomers))
            .collectRelatedColumns(
                List.of(EntityFixtures.column("sales", "customers", "id", "integer", true)))
            .build();

    assertEquals(
        List.of(),
        boxes.relationColumnsOf(List.of(ordersToCustomers)).getOrDefault(CUSTOMERS, List.of()));
  }
}
