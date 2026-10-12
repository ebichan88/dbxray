package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.EntityFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Constraints のテーブルキーによるインデックス化に関するテスト */
public class ConstraintsTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("TEST_DB", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: 自テーブルに属する制約のみを、登録順を保って返す")
  void testOfReturnsOwnConstraintsInOrder() {
    var pk = new ConstraintEntity("public", "orders", "pk_orders", "PRIMARY KEY", "(id)", "");
    var uq = new ConstraintEntity("public", "orders", "uq_orders_code", "UNIQUE", "(code)", "");
    var other =
        new ConstraintEntity("public", "customers", "pk_customers", "PRIMARY KEY", "(id)", "");
    var constraints = Constraints.of(List.of(pk, uq, other));

    assertEquals(List.of(pk, uq), constraints.belongingTo(newTable("public", "orders")));
  }

  @Test
  @DisplayName("of: 該当する制約がないテーブルには空リストを返す")
  void testOfReturnsEmptyForUnknownTable() {
    var constraints = Constraints.of(List.of(EntityFixtures.constraint("public", "orders")));

    assertEquals(List.of(), constraints.belongingTo(newTable("public", "unknown")));
  }

  @Test
  @DisplayName("of: 同名テーブルでもスキーマが異なれば別のキーとして扱う")
  void testOfDistinguishesSameTableNameAcrossSchemas() {
    var publicConstraint = EntityFixtures.constraint("public", "orders");
    var salesConstraint = EntityFixtures.constraint("sales", "orders");
    var constraints = Constraints.of(List.of(publicConstraint, salesConstraint));

    assertEquals(List.of(publicConstraint), constraints.belongingTo(newTable("public", "orders")));
    assertEquals(List.of(salesConstraint), constraints.belongingTo(newTable("sales", "orders")));
  }
}
