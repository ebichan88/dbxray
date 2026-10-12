package com.dbxray.domain.service.writer.erdiagram;

import static com.dbxray.testsupport.MarkdownAssert.assertMarkdownEquals;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ErDiagramTemplates のセクション生成テスト */
public class ErDiagramTemplatesTest {

  private BaseInfoEntity baseInfo() {
    return new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
  }

  private TableEntity newTable(String schema, String physical, String logical) {
    return new TableEntity("TEST_DB", schema, logical, physical, TableType.TABLE, "");
  }

  private ForeignKeyEntity newFk(
      String schema, String table, String fkName, String refSchema, String refTable) {
    return ForeignKeyFixtures.physical(schema, table, fkName, refSchema, refTable);
  }

  /** ER図セクションを、ノード算出込みで生成するテスト用ヘルパー */
  private String erDiagram(List<ForeignKeyEntity> foreignKeys, int maxNodes) {
    return ErDiagramTemplates.erDiagram(
        ForeignKeyGroup.of(foreignKeys).planRendering(NodeLimit.of(maxNodes)),
        DiagramBoxesFixtures.none());
  }

  @Test
  @DisplayName("fileHeader: タイトルとDB名の見出しと空行を出力する")
  void testFileHeader() {
    assertMarkdownEquals(
        """
        # ER図一覧（DB名：TEST_DB）

        """,
        ErDiagramTemplates.fileHeader("ER図一覧", baseInfo()));
  }

  @Test
  @DisplayName("schemaFileHeader: DB名とスキーマ名の見出しと空行を出力する")
  void testSchemaFileHeader() {
    assertMarkdownEquals(
        """
        # ER図（DB名：TEST_DB / スキーマ名：public）

        """,
        ErDiagramTemplates.schemaFileHeader("public", baseInfo()));
  }

  @Test
  @DisplayName("erDiagram: 外部キーがない場合はMermaidを出力せずメッセージのみ")
  void testErDiagramNoForeignKeys() {
    assertMarkdownEquals(
        """
        ## ER図

        外部キーによる関連を持つテーブルはありません。

        """,
        erDiagram(List.of(), 80));
  }

  @Test
  @DisplayName("erDiagram: 参照先 ||--o{ 参照元 の向きで関係線を出力する")
  void testErDiagramRelationDirection() {
    var fk = newFk("public", "orders", "fk_orders_customer", "public", "customers");
    assertMarkdownEquals(
        """
        ## ER図

        ```mermaid
        erDiagram
            public_customers["customers"]
            public_orders["orders"]
            public_customers ||--o{ public_orders : "fk_orders_customer"
        ```

        """,
        erDiagram(List.of(fk), 80));
  }

  @Test
  @DisplayName("erDiagram: 外部キーの多重度に応じた関係線を出力する")
  void testErDiagramCardinality() {
    var oneToOne =
        ForeignKeyFixtures.physical(
            "public", "profiles", "fk_profiles_user", "public", "users", Cardinality.ONE_TO_ONE);
    var optional =
        ForeignKeyFixtures.physical(
            "public",
            "orders",
            "fk_orders_coupon",
            "public",
            "coupons",
            Cardinality.OPTIONAL_ONE_TO_MANY);
    String section = erDiagram(List.of(oneToOne, optional), 80);
    assertTrue(section.contains("public_users ||--o| public_profiles : \"fk_profiles_user\""));
    assertTrue(section.contains("public_coupons |o--o{ public_orders : \"fk_orders_coupon\""));
  }

  @Test
  @DisplayName("erDiagram: 箱には論理テーブル名と、関連をつなぐカラムだけを表示する")
  void testErDiagramShowsRelationColumns() {
    var fk =
        new ForeignKeyEntity(
            "public",
            "orders",
            "fk_orders_customer",
            List.of("customer_id"),
            "public",
            "customers",
            List.of("id"),
            Cardinality.ONE_TO_MANY,
            RelationType.PHYSICAL);
    var boxes =
        DiagramBoxesFixtures.of(
            List.of(newTable("public", "orders", "受注"), newTable("public", "customers", "")),
            List.of(fk),
            List.of(
                new ColumnEntity(
                    "public", "customers", "顧客ID", "id", "integer", "", true, true, ""),
                new ColumnEntity("public", "customers", "顧客名", "name", "text", "", false, true, ""),
                new ColumnEntity("public", "orders", "受注ID", "id", "integer", "", true, true, ""),
                new ColumnEntity(
                    "public", "orders", "顧客ID", "customer_id", "integer", "", false, true, "")));
    assertMarkdownEquals(
        """
        ## ER図

        ```mermaid
        erDiagram
            public_customers["customers"]
            public_orders["orders（受注）"]
            public_customers ||--o{ public_orders : "fk_orders_customer"
            public_customers {
                integer id PK "顧客ID"
            }
            public_orders {
                integer customer_id FK "顧客ID"
            }
        ```

        """,
        ErDiagramTemplates.erDiagram(
            ForeignKeyGroup.of(List.of(fk)).planRendering(NodeLimit.of(80)), boxes));
  }

  @Test
  @DisplayName("erDiagram: 関連カラムを取得していないテーブルは属性を出力しない")
  void testErDiagramHasNoAttributesWithoutRelationColumns() {
    var fk = newFk("public", "orders", "fk_orders_customer", "public", "customers");
    String section = erDiagram(List.of(fk), 80);
    // 属性を持つ箱は「<エンティティ名> {」で始まるブロックとして出力される
    assertFalse(section.contains(" {" + System.lineSeparator()));
  }

  @Test
  @DisplayName("erDiagram: スキーマを跨ぐ外部キーも関係線として出力する")
  void testErDiagramCrossSchemaRelation() {
    var fk = newFk("sales", "orders", "fk_orders_customer", "master", "customers");
    String section = erDiagram(List.of(fk), 80);
    assertTrue(section.contains("master_customers ||--o{ sales_orders : \"fk_orders_customer\""));
  }

  @Test
  @DisplayName("erDiagram: サニタイズ後に識別子が衝突する場合は連番で一意化する")
  void testErDiagramResolvesIdCollision() {
    // sanitizeIdentifier により "a_b" + "c" と "a" + "b_c" はいずれも "a_b_c" に潰れる
    var fk1 = newFk("a_b", "c", "fk1", "ref", "t");
    var fk2 = newFk("a", "b_c", "fk2", "ref", "t");
    String section = erDiagram(List.of(fk1, fk2), 80);
    // ノードはスキーマ名・テーブル名順に並ぶため "a"."b_c" が先に採番される
    assertTrue(section.contains("ref_t ||--o{ a_b_c_2 : \"fk1\""));
    assertTrue(section.contains("ref_t ||--o{ a_b_c : \"fk2\""));
  }

  @Test
  @DisplayName("erDiagram: 同じ図内に同名テーブルが複数スキーマにまたがる場合は表示ラベルをスキーマ.テーブルにする")
  void testErDiagramQualifiesLabelOnCrossSchemaNameCollision() {
    var fk1 = newFk("sales", "orders", "fk1", "sales", "customers");
    var fk2 = newFk("master", "invoices", "fk2", "master", "customers");
    String section = erDiagram(List.of(fk1, fk2), 80);
    assertTrue(section.contains("sales_customers[\"sales.customers\"]"));
    assertTrue(section.contains("master_customers[\"master.customers\"]"));
    // 衝突していないテーブルはテーブル名のみのまま
    assertTrue(section.contains("sales_orders[\"orders\"]"));
    assertTrue(section.contains("master_invoices[\"invoices\"]"));
  }

  @Test
  @DisplayName("erDiagram: ノード数が上限を超える場合はMermaidを出力せず省略メッセージを返す")
  void testErDiagramOmittedOnOverflow() {
    var fk1 = newFk("public", "orders", "fk_orders_customer", "public", "customers");
    var fk2 = newFk("public", "items", "fk_items_orders", "public", "orders");
    // ノードは orders / customers / items の3件のため、上限2件で超過する
    assertMarkdownEquals(
        """
        ## ER図

        ER図に描画するテーブル数が3件となり、上限（erDiagramMaxNodes = 2件）を超えるため描画を省略しました。
        代わりに外部キーによる関連を一覧で掲載します。

        """,
        erDiagram(List.of(fk1, fk2), 2));
  }

  @Test
  @DisplayName("erDiagram: 上限が0以下の場合は件数に関わらずMermaidを出力する")
  void testErDiagramNoLimit() {
    var fk1 = newFk("public", "orders", "fk_orders_customer", "public", "customers");
    var fk2 = newFk("public", "items", "fk_items_orders", "public", "orders");
    assertTrue(erDiagram(List.of(fk1, fk2), 0).contains("```mermaid"));
  }

  @Test
  @DisplayName("diagramTableLine: 定義書へのリンク付きで1行分を出力する（既存一覧と同じ[■]表記）")
  void testDiagramTableLine() {
    var table = newTable("public", "orders", "受注");
    assertMarkdownEquals(
        "|1|public|orders|受注|table|[■](./public/table/orders.md)|\n",
        ErDiagramTemplates.diagramTableLine(1, TableKey.of(table), table));
  }

  @Test
  @DisplayName("diagramTableLine: 出力対象範囲外のテーブルはリンクを張らない")
  void testDiagramTableLineWithoutDefinition() {
    assertMarkdownEquals(
        "|1|external|master_data|||-|\n",
        ErDiagramTemplates.diagramTableLine(1, TableKey.of("external", "master_data"), null));
  }

  @Test
  @DisplayName("foreignKeyTableLine: 参照元・外部キー名・参照先を出力する")
  void testForeignKeyTableLine() {
    var fk = newFk("sales", "orders", "fk_cross", "master", "customers");
    assertMarkdownEquals(
        "|1|sales.orders|fk_cross|master.customers|\n",
        ErDiagramTemplates.foreignKeyTableLine(1, fk));
  }

  @Test
  @DisplayName("groupFileHeader: DB名・スキーマ名・グループ番号の見出しと空行を出力する")
  void testGroupFileHeader() {
    assertMarkdownEquals(
        """
        # ER図（DB名：TEST_DB / スキーマ名：public / グループ2）

        """,
        ErDiagramTemplates.groupFileHeader("public", 2, baseInfo()));
  }

  @Test
  @DisplayName("groupedMessage: 分割した理由とグループ数を出力する（Mermaidは出力しない）")
  void testGroupedMessage() {
    assertMarkdownEquals(
        """
        ## ER図

        ER図に描画するテーブル数が200件となり、上限（erDiagramMaxNodes = 80件）を超えるため、
        外部キーで繋がったテーブルのまとまりごとに3個のグループへ分割しました。

        """,
        ErDiagramTemplates.groupedMessage(200, NodeLimit.of(80), 3));
  }

  @Test
  @DisplayName("groupIndexLine: グループの規模と主なテーブルを出力する")
  void testGroupIndexLine() {
    assertMarkdownEquals(
        "|1|12|15|public.orders|[■](./erDiagram_TEST_DB_public_group1.md)|\n",
        ErDiagramTemplates.groupIndexLine(
            1, 12, 15, TableKey.of("public", "orders"), "./erDiagram_TEST_DB_public_group1.md"));
  }

  @Test
  @DisplayName("groupFooter: スキーマのER図・ER図一覧・テーブル一覧へ戻るリンクを出力する")
  void testGroupFooter() {
    assertMarkdownEquals(
        """
        ___

        [スキーマのER図へ](./erDiagram_TEST_DB_public.md) / [ER図一覧へ](./erDiagramList_TEST_DB.md) / [テーブル一覧へ](./tableList_TEST_DB.md)
        """,
        ErDiagramTemplates.groupFooter("public", baseInfo()));
  }

  @Test
  @DisplayName("schemaIndex: スキーマ別ER図へのリンクとテーブル数を、渡された順に出力する")
  void testSchemaIndex() {
    final Map<String, List<TableEntity>> tablesBySchema = new LinkedHashMap<>();
    tablesBySchema.put(
        "public", List.of(newTable("public", "orders", "受注"), newTable("public", "items", "明細")));
    tablesBySchema.put("master", List.of(newTable("master", "customers", "顧客")));
    assertMarkdownEquals(
        """
        ## スキーマ別ER図

        | No. | スキーマ名 | テーブル数 | Link |
        |:---|:---|:---|:---|
        |1|public|2|[■](./erDiagram_TEST_DB_public.md)|
        |2|master|1|[■](./erDiagram_TEST_DB_master.md)|

        """,
        ErDiagramTemplates.schemaIndex(baseInfo(), tablesBySchema));
  }

  @Test
  @DisplayName("schemaFooter: ER図一覧とテーブル一覧へ戻るリンクを出力する")
  void testSchemaFooter() {
    assertMarkdownEquals(
        """
        ___

        [ER図一覧へ](./erDiagramList_TEST_DB.md) / [テーブル一覧へ](./tableList_TEST_DB.md)
        """,
        ErDiagramTemplates.schemaFooter(baseInfo()));
  }
}
