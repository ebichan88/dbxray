package com.dbxray.domain.model.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.testsupport.EntityFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TableDefinitionContent.assemble の組み立てに関するテスト<br>
 * 対象テーブル分のみが正しく抽出され、被参照側の外部キーも incomingForeignKeysとして分離されることを検証する
 */
public class TableDefinitionContentTest {

  private TableEntity newTable(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("assemble: 対象テーブルに属する情報のみを抽出し、他テーブルの情報は含まれない")
  void testAssembleExtractsOnlyTargetTableInformation() {
    var baseInfo = new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH);
    var target = newTable("public", "orders");

    var ownColumn = EntityFixtures.column("public", "orders", "id", "int", true);
    var otherColumn = EntityFixtures.column("public", "customers", "id", "int", true);
    var ownIndex = EntityFixtures.index("public", "orders");
    var ownConstraint = EntityFixtures.constraint("public", "orders");
    // 詳細情報はチャンク単位で複数テーブル分をまとめて取得し、テーブルごとに振り分けたものを渡す
    var detail =
        TableDetail.assembleAll(
                List.of(target),
                List.of(ownColumn, otherColumn),
                List.of(ownIndex, EntityFixtures.index("public", "customers")),
                List.of(ownConstraint, EntityFixtures.constraint("public", "customers")))
            .get(0);

    var outgoingFk =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var incomingFk =
        ForeignKeyFixtures.physical("public", "items", "fk_items_orders", "public", "orders");
    var foreignKeys = ForeignKeys.of(List.of(outgoingFk, incomingFk));

    var ownTrigger = EntityFixtures.trigger("public", "orders");
    var triggers = Triggers.of(List.of(ownTrigger, EntityFixtures.trigger("public", "customers")));

    var ownAnnotation = new TableAnnotation("受注テーブル", "備考", java.util.Map.of("id", "主キー"));
    var annotations =
        Annotations.of(
            java.util.Map.of(
                TableKey.of("public", "orders"),
                ownAnnotation,
                TableKey.of("public", "customers"),
                new TableAnnotation("顧客テーブル", "", java.util.Map.of())));

    var viewToOrders =
        new ViewReferenceEntity(
            "public", "v_orders", TableType.VIEW, "public", "orders", TableType.TABLE);
    var viewToCustomers =
        new ViewReferenceEntity(
            "public", "v_orders", TableType.VIEW, "public", "customers", TableType.TABLE);

    TableDefinitionContent content =
        TableDefinitionContent.assemble(
            baseInfo,
            detail,
            foreignKeys,
            triggers,
            Partitions.of(List.of()),
            ViewReferences.of(List.of(viewToOrders, viewToCustomers)),
            annotations,
            Viewpoints.empty());

    assertSame(baseInfo, content.baseInfo());
    assertSame(target, content.table());
    assertSame(ownAnnotation, content.annotation());
    assertEquals(List.of(ownColumn), content.columns());
    assertEquals(List.of(ownIndex), content.indexes());
    assertEquals(List.of(ownConstraint), content.constraints());
    assertEquals(List.of(outgoingFk), content.foreignKeys());
    assertEquals(List.of(incomingFk), content.incomingRelations());
    assertEquals(List.of(ownTrigger), content.triggers());
    assertEquals(List.of(viewToOrders), content.referencingViews());
    assertEquals(List.of(), content.referencedTables());

    // otherテーブルの情報が紛れ込んでいないことの確認
    assertFalse(content.columns().contains(otherColumn));
  }

  @Test
  @DisplayName("assemble: 関連する情報が存在しない場合は空リストになる")
  void testAssembleWithNoRelatedInformationReturnsEmptyLists() {
    var baseInfo = new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH);
    var target = newTable("public", "empty_table");

    TableDefinitionContent content =
        TableDefinitionContent.assemble(
            baseInfo,
            new TableDetail(target, List.of(), List.of(), List.of()),
            ForeignKeys.of(List.of()),
            Triggers.of(List.of()),
            Partitions.of(List.of()),
            ViewReferences.of(List.of()),
            Annotations.empty(),
            Viewpoints.empty());

    // 付帯情報が存在しないテーブルには空の付帯情報が設定される
    assertSame(TableAnnotation.EMPTY, content.annotation());
    assertEquals(List.of(), content.columns());
    assertEquals(List.of(), content.indexes());
    assertEquals(List.of(), content.constraints());
    assertEquals(List.of(), content.foreignKeys());
    assertEquals(List.of(), content.incomingRelations());
    assertEquals(List.of(), content.triggers());
  }

  @Test
  @DisplayName("assemble: 物理外部キーと論理リレーションを由来ごとに分けて保持する")
  void testAssembleSplitsRelationsByType() {
    var table = newTable("public", "orders");
    var physical =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var foreignKeys = ForeignKeys.of(List.of(physical, logical));

    var content =
        TableDefinitionContent.assemble(
            new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH),
            new TableDetail(table, List.of(), List.of(), List.of()),
            foreignKeys,
            Triggers.of(List.of()),
            Partitions.of(List.of()),
            ViewReferences.of(List.of()),
            Annotations.empty(),
            Viewpoints.empty());

    assertEquals(List.of(physical), content.foreignKeys());
    assertEquals(List.of(logical), content.logicalRelations());
  }

  @Test
  @DisplayName("assemble: パーティション表には自テーブルのパーティションのみが設定される")
  void testAssembleExtractsOwnPartitions() {
    var baseInfo = new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH);
    var sales =
        new TableEntity("testdb", "public", "", "sales", TableType.TABLE, "", "RANGE (sold_on)");
    var own =
        new PartitionEntity(
            "public", "sales", "public", "sales_2026_01", "public", "sales", "DEFAULT", "");
    var other =
        new PartitionEntity(
            "public", "logs", "public", "logs_2026_01", "public", "logs", "DEFAULT", "");

    TableDefinitionContent content =
        TableDefinitionContent.assemble(
            baseInfo,
            new TableDetail(sales, List.of(), List.of(), List.of()),
            ForeignKeys.of(List.of()),
            Triggers.of(List.of()),
            Partitions.of(List.of(own, other)),
            ViewReferences.of(List.of()),
            Annotations.empty(),
            Viewpoints.empty());

    assertEquals(List.of(own), content.partitions());
  }
}
