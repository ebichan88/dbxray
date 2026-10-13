package com.dbxray.domain.model.relation;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ForeignKeys のインデックス化（参照側・被参照側）に関するテスト */
public class ForeignKeysTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("TEST_DB", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 参照側(of)は自テーブルが保有する外部キーを返す")
  void testOfReturnsOwnForeignKeys() {
    var fk =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var foreignKeys = ForeignKeys.of(List.of(fk));

    assertEquals(List.of(fk), foreignKeys.belongingTo(newTable("public", "orders")));
    assertEquals(List.of(), foreignKeys.belongingTo(newTable("public", "customers")));
  }

  @Test
  @DisplayName("referencingTo: 被参照側は自テーブルを参照している外部キーを返す")
  void testIncomingOfReturnsReferencingForeignKeys() {
    var fk =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var foreignKeys = ForeignKeys.of(List.of(fk));

    assertEquals(List.of(fk), foreignKeys.referencingTo(newTable("public", "customers")));
    assertEquals(List.of(), foreignKeys.referencingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("referencingTo: 自己参照の外部キーは被参照側に含まれない（外部キー情報セクションとの重複表示を避けるため）")
  void testIncomingOfExcludesSelfReference() {
    var selfFk =
        ForeignKeyFixtures.physical(
            "public", "categories", "fk_categories_parent", "public", "categories");
    var foreignKeys = ForeignKeys.of(List.of(selfFk));

    assertEquals(List.of(selfFk), foreignKeys.belongingTo(newTable("public", "categories")));
    assertEquals(List.of(), foreignKeys.referencingTo(newTable("public", "categories")));
  }

  @Test
  @DisplayName("referencingTo: スキーマを跨いだ参照でも正しく解決される")
  void testIncomingOfAcrossSchemas() {
    var fk = ForeignKeyFixtures.physical("hr", "assignment", "fk_assignment_emp", "sales", "emp");
    var foreignKeys = ForeignKeys.of(List.of(fk));

    assertEquals(List.of(fk), foreignKeys.referencingTo(newTable("sales", "emp")));
  }

  @Test
  @DisplayName("groupBySchema: スキーマ跨ぎの外部キーは参照元・参照先の双方のスキーマに登録される")
  void testGroupBySchemaRegistersBothSides() {
    var crossFk =
        ForeignKeyFixtures.physical("hr", "assignment", "fk_assignment_emp", "sales", "emp");
    var bySchema = ForeignKeys.of(List.of(crossFk)).groupBySchema();

    assertEquals(List.of(crossFk), bySchema.get("hr"));
    assertEquals(List.of(crossFk), bySchema.get("sales"));
  }

  @Test
  @DisplayName("groupBySchema: 同一スキーマ内の外部キーは1度だけ登録される")
  void testGroupBySchemaRegistersSameSchemaOnce() {
    var fk =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var bySchema = ForeignKeys.of(List.of(fk)).groupBySchema();

    assertEquals(List.of(fk), bySchema.get("public"));
    assertEquals(1, bySchema.size());
  }

  @Test
  @DisplayName("groupBySchema: 自己参照の外部キーも所属スキーマに登録される")
  void testGroupBySchemaWithSelfReference() {
    var selfFk =
        ForeignKeyFixtures.physical(
            "public", "categories", "fk_categories_parent", "public", "categories");
    var bySchema = ForeignKeys.of(List.of(selfFk)).groupBySchema();

    assertEquals(List.of(selfFk), bySchema.get("public"));
  }

  @Test
  @DisplayName("crossSchema: スキーマを跨ぐ外部キーのみを返す")
  void testCrossSchema() {
    var sameSchemaFk =
        ForeignKeyFixtures.physical("public", "orders", "fk_same", "public", "customers");
    var crossSchemaFk = ForeignKeyFixtures.physical("hr", "assignment", "fk_cross", "sales", "emp");
    var foreignKeys = ForeignKeys.of(List.of(sameSchemaFk, crossSchemaFk));

    assertEquals(List.of(crossSchemaFk), foreignKeys.crossSchema());
  }

  @Test
  @DisplayName("physicalBelongingTo: 物理外部キーのみを返し、論理リレーションは含めない")
  void testPhysicalOfExcludesLogical() {
    var physical =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var foreignKeys = ForeignKeys.of(List.of(physical, logical));

    assertEquals(List.of(physical), foreignKeys.physicalBelongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("logicalBelongingTo: 論理リレーションのみを返し、物理外部キーは含めない")
  void testLogicalOfExcludesPhysical() {
    var physical =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var foreignKeys = ForeignKeys.of(List.of(physical, logical));

    assertEquals(List.of(logical), foreignKeys.logicalBelongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("of: 論理リレーションも物理外部キーと同じ集合として扱う（ER図で同一のグラフに描くため）")
  void testOfIncludesBothRelationTypes() {
    var physical =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var foreignKeys = ForeignKeys.of(List.of(physical, logical));

    assertEquals(List.of(physical, logical), foreignKeys.belongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("referencingTo: 論理リレーションも被参照側として解決される")
  void testIncomingOfResolvesLogical() {
    var logical =
        ForeignKeyFixtures.logical(
            "public", "audit_log", "rel_audit_employee", "public", "employee");
    var foreignKeys = ForeignKeys.of(List.of(logical));

    assertEquals(List.of(logical), foreignKeys.referencingTo(newTable("public", "employee")));
  }

  @Test
  @DisplayName("groupBySchema: スキーマを跨ぐ論理リレーションも双方のスキーマに登録される")
  void testGroupBySchemaIncludesCrossSchemaLogical() {
    var logical =
        ForeignKeyFixtures.logical("hr", "assignment", "rel_assignment_emp", "sales", "emp");
    var bySchema = ForeignKeys.of(List.of(logical)).groupBySchema();

    assertEquals(List.of(logical), bySchema.get("hr"));
    assertEquals(List.of(logical), bySchema.get("sales"));
  }

  @Test
  @DisplayName("withinTables/crossingTableSetBoundary: テーブルの集合に対し、両端が含まれる関連と片端だけが含まれる関連を分けて返す")
  void testWithinAndCrossing() {
    var inside =
        ForeignKeyFixtures.physical("sales", "orders", "fk_orders_customer", "sales", "customer");
    var outgoing =
        ForeignKeyFixtures.logical("sales", "orders", "rel_orders_product", "sales", "product");
    var incoming =
        ForeignKeyFixtures.physical("sales", "invoice", "fk_invoice_orders", "sales", "orders");
    var unrelated =
        ForeignKeyFixtures.physical("sales", "stock", "fk_stock_product", "sales", "product");
    var foreignKeys = ForeignKeys.of(List.of(inside, outgoing, incoming, unrelated));
    var tableKeys = Set.of(TableKey.of("sales", "orders"), TableKey.of("sales", "customer"));

    assertEquals(List.of(inside), foreignKeys.withinTables(tableKeys));
    assertEquals(List.of(outgoing, incoming), foreignKeys.crossingTableSetBoundary(tableKeys));
  }

  @Test
  @DisplayName("neighborhoodOf: 描画距離1は自テーブルの関連だけを、参照先へ向かう関連・参照元から来る関連の順に返す")
  void testNeighborhoodOfDistanceOne() {
    var neighborhood =
        chain().neighborhoodOf(newTable("public", "employee"), 1, NodeLimit.UNLIMITED);

    assertEquals(List.of(EMPLOYEE_DEPARTMENT, ORDERS_EMPLOYEE), neighborhood.relations());
    assertEquals(1, neighborhood.distance());
    assertFalse(neighborhood.isShortened());
  }

  @Test
  @DisplayName("neighborhoodOf: 描画距離を増やすと、向きを問わず関連先のテーブルが持つ関連まで、近い順に返す")
  void testNeighborhoodOfFollowsBothDirections() {
    var foreignKeys = chain();
    var department = newTable("public", "department");

    assertEquals(
        List.of(EMPLOYEE_DEPARTMENT, ORDERS_EMPLOYEE),
        foreignKeys.neighborhoodOf(department, 2, NodeLimit.UNLIMITED).relations());
    assertEquals(
        List.of(EMPLOYEE_DEPARTMENT, ORDERS_EMPLOYEE, ORDERS_CUSTOMERS),
        foreignKeys.neighborhoodOf(department, 3, NodeLimit.UNLIMITED).relations());
  }

  @Test
  @DisplayName("neighborhoodOf: テーブル数が上限を超える場合は、超えない距離まで縮める。1段でも超える場合は1段で描く")
  void testNeighborhoodOfShortensDistanceToFitLimit() {
    var foreignKeys = chain();
    var department = newTable("public", "department");

    var shortened = foreignKeys.neighborhoodOf(department, 3, NodeLimit.of(3));
    assertEquals(2, shortened.distance());
    assertEquals(3, shortened.requestedDistance());
    assertTrue(shortened.isShortened());
    assertEquals(List.of(EMPLOYEE_DEPARTMENT, ORDERS_EMPLOYEE), shortened.relations());

    var minimum = foreignKeys.neighborhoodOf(department, 2, NodeLimit.of(1));
    assertEquals(1, minimum.distance());
    assertEquals(List.of(EMPLOYEE_DEPARTMENT), minimum.relations());
  }

  /** department ← employee ← orders → customers */
  private static final ForeignKeyEntity EMPLOYEE_DEPARTMENT =
      ForeignKeyFixtures.physical(
          "public", "employee", "fk_employee_department", "public", "department");

  private static final ForeignKeyEntity ORDERS_EMPLOYEE =
      ForeignKeyFixtures.physical("public", "orders", "fk_orders_employee", "public", "employee");

  private static final ForeignKeyEntity ORDERS_CUSTOMERS =
      ForeignKeyFixtures.physical("public", "orders", "fk_orders_customers", "public", "customers");

  private static ForeignKeys chain() {
    return ForeignKeys.of(List.of(EMPLOYEE_DEPARTMENT, ORDERS_EMPLOYEE, ORDERS_CUSTOMERS));
  }
}
