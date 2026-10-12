package com.dbxray.domain.model.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.target.TableDefinitionContent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableSnapshot のテーブル定義出力情報からの変換に関するテスト */
public class TableSnapshotTest {

  private static final BaseInfoEntity BASE_INFO =
      new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 25));

  private TableDefinitionContent content(
      TableEntity table,
      List<ColumnEntity> columns,
      List<IndexEntity> indexes,
      List<ConstraintEntity> constraints,
      List<ForeignKeyEntity> foreignKeys,
      List<ForeignKeyEntity> logicalRelations,
      List<TriggerEntity> triggers,
      TableAnnotation annotation) {
    return new TableDefinitionContent(
        BASE_INFO,
        table,
        columns,
        indexes,
        constraints,
        foreignKeys,
        logicalRelations,
        List.of(),
        List.of(),
        List.of(),
        triggers,
        List.of(),
        annotation,
        List.of());
  }

  @Test
  @DisplayName("of: テーブル・カラムの各項目を個別の値として保持し、サイドカーの付帯情報をマージする")
  void testOfConvertsTableAndColumns() {
    var table = new TableEntity("testdb", "public", "受注", "orders", TableType.TABLE, "", "");
    var id = new ColumnEntity("public", "orders", "受注ID", "id", "integer", "", true, true, " ");
    var amount =
        new ColumnEntity(
            "public", "orders", "", "amount", "numeric(10,2)", "10,2", false, false, "0");
    var annotation = new TableAnnotation("受注を管理する。", "個人情報を含む", Map.of("amount", "税込"));

    TableSnapshot snapshot =
        TableSnapshot.of(
            content(
                table,
                List.of(id, amount),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                annotation));

    assertEquals("public", snapshot.schema());
    assertEquals("orders", snapshot.name());
    assertEquals("受注", snapshot.logicalName());
    assertEquals("table", snapshot.type());
    assertEquals("受注を管理する。", snapshot.description());
    assertEquals("個人情報を含む", snapshot.remarks());
    assertNull(snapshot.definition());
    assertEquals(
        List.of(
            new TableSnapshot.Column("id", "受注ID", "integer", null, true, true, null, null),
            new TableSnapshot.Column(
                "amount", null, "numeric(10,2)", "10,2", false, false, "0", "税込")),
        snapshot.columns());
  }

  @Test
  @DisplayName("of: インデックス・制約・外部キー・論理リレーション・トリガーを構造化して保持する")
  void testOfConvertsRelatedObjects() {
    var table = new TableEntity("testdb", "public", "", "orders", TableType.TABLE, "", "");
    var index =
        new IndexEntity(
            "public", "orders", "orders_pkey", "btree", true, true, "CREATE UNIQUE INDEX ...", "");
    var constraint =
        new ConstraintEntity(
            "public", "orders", "chk_amount", "CHECK", "CHECK ((amount >= 0))", "金額は0以上");
    var foreignKey =
        new ForeignKeyEntity(
            "public",
            "orders",
            "fk_orders_item",
            List.of("item_id", "item_seq"),
            "master",
            "items",
            List.of("id", "seq"),
            Cardinality.OPTIONAL_ONE_TO_MANY,
            RelationType.PHYSICAL);
    var logicalRelation =
        ForeignKeyEntity.logical(
            TableKey.of("public", "orders"),
            "orders_user_id_lrel",
            List.of("user_id"),
            TableKey.of("public", "users"),
            List.of("id"),
            Cardinality.ONE_TO_MANY);
    var trigger =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT", "UPDATE"),
            "ROW",
            "public.f_orders",
            "CREATE TRIGGER trg_orders ...",
            "BEGIN NULL; END;");

    TableSnapshot snapshot =
        TableSnapshot.of(
            content(
                table,
                List.of(),
                List.of(index),
                List.of(constraint),
                List.of(foreignKey),
                List.of(logicalRelation),
                List.of(trigger),
                TableAnnotation.EMPTY));

    assertNull(snapshot.logicalName());
    assertNull(snapshot.description());
    assertEquals(
        List.of(
            new TableSnapshot.Index(
                "orders_pkey", "btree", true, true, "CREATE UNIQUE INDEX ...", null)),
        snapshot.indexes());
    assertEquals(
        List.of(
            new TableSnapshot.Constraint("chk_amount", "CHECK", "CHECK ((amount >= 0))", "金額は0以上")),
        snapshot.constraints());
    assertEquals(
        List.of(
            new TableSnapshot.Relation(
                "fk_orders_item",
                List.of("item_id", "item_seq"),
                "master",
                "items",
                List.of("id", "seq"),
                Cardinality.OPTIONAL_ONE_TO_MANY)),
        snapshot.foreignKeys());
    assertEquals(
        List.of(
            new TableSnapshot.Relation(
                "orders_user_id_lrel",
                List.of("user_id"),
                "public",
                "users",
                List.of("id"),
                Cardinality.ONE_TO_MANY)),
        snapshot.logicalRelations());
    assertEquals(
        List.of(
            new TableSnapshot.Trigger(
                "trg_orders",
                "BEFORE",
                List.of("INSERT", "UPDATE"),
                "ROW",
                "public.f_orders",
                "CREATE TRIGGER trg_orders ...",
                "BEGIN NULL; END;")),
        snapshot.triggers());
  }

  @Test
  @DisplayName("of: view・materialized viewはソース定義を保持する")
  void testOfKeepsViewDefinition() {
    var view =
        new TableEntity(
            "testdb", "public", "", "v_orders", TableType.VIEW, " SELECT id FROM orders;", "");

    TableSnapshot snapshot =
        TableSnapshot.of(
            content(
                view,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                TableAnnotation.EMPTY));

    assertEquals("view", snapshot.type());
    assertEquals(" SELECT id FROM orders;", snapshot.definition());
  }

  @Test
  @DisplayName("of: ビューが参照するテーブルを、スキーマ名・テーブル名・区分で保持する。参照しているビューは保持しない")
  void testOfKeepsReferencedTables() {
    var view = new TableEntity("testdb", "public", "", "v_orders", TableType.VIEW, "SELECT 1", "");
    var toOrders =
        new ViewReferenceEntity(
            "public", "v_orders", TableType.VIEW, "public", "orders", TableType.TABLE);
    var fromMv =
        new ViewReferenceEntity(
            "public", "mv", TableType.MATERIALIZED_VIEW, "public", "v_orders", TableType.VIEW);
    var content =
        new TableDefinitionContent(
            BASE_INFO,
            view,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(toOrders),
            List.of(fromMv),
            List.of(),
            List.of(),
            TableAnnotation.EMPTY,
            List.of());

    assertEquals(
        List.of(new TableSnapshot.ViewReference("public", "orders", "table")),
        TableSnapshot.of(content).referencedTables());
  }

  @Test
  @DisplayName("of: パーティション表はパーティションキーを保持し、パーティション表でないテーブルでは持たない")
  void testOfKeepsPartitionKeyOnlyForPartitionedTable() {
    var partitioned =
        new TableEntity("testdb", "public", "", "sales", TableType.TABLE, "", "RANGE (sold_on)");
    var ordinary = new TableEntity("testdb", "public", "", "customer", TableType.TABLE, "", "");

    assertEquals(
        "RANGE (sold_on)",
        TableSnapshot.of(
                content(
                    partitioned,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    TableAnnotation.EMPTY))
            .partitionKey());
    assertNull(
        TableSnapshot.of(
                content(
                    ordinary,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    TableAnnotation.EMPTY))
            .partitionKey());
  }
}
