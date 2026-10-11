package com.dbxray.domain.service.writer.tabledefinition;

import static com.dbxray.testsupport.MarkdownAssert.assertMarkdownEquals;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.DiagramNeighborhood;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.EntityFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableDefinitionTemplates のセクション生成テスト */
public class TableDefinitionTemplatesTest {

  private TableEntity newTable(
      String schema, String physical, String logical, String type, String def) {
    return new TableEntity("TEST_DB", schema, logical, physical, TableType.findByName(type), def);
  }

  @Test
  @DisplayName("fileHeader: 論理名つきのテーブル名の見出しと空行を出力する")
  void testFileHeader() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    assertMarkdownEquals(
        """
        # orders（受注）

        """,
        TableDefinitionTemplates.fileHeader(table));
  }

  @Test
  @DisplayName("baseInfo: RDBMS・DB名・作成日の表を出力する")
  void testBaseInfo() {
    var base = new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
    assertMarkdownEquals(
        """
        ## 基本情報

        | RDBMS | データベース名 | 作成日 |
        |:---|:---|:---|
        |PostgreSQL 16|TEST_DB|2025/01/01|

        """,
        TableDefinitionTemplates.baseInfo(base));
  }

  @Test
  @DisplayName("tableInfo: テーブル1行の表を出力する（サイドカーの備考が無い場合は末尾セルが空）")
  void testTableInfo() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    assertMarkdownEquals(
        """
        ## テーブル情報

        | スキーマ名 | 論理テーブル名 | 物理テーブル名 | 区分 | 備考 |
        |:---|:---|:---|:---|:---|
        |public|受注|orders|table||

        """,
        TableDefinitionTemplates.tableInfo(table, TableAnnotation.EMPTY));
  }

  @Test
  @DisplayName("tableInfo: サイドカーのテーブル備考を末尾セルへ後付けし、|・改行はエスケープする")
  void testTableInfoWithAnnotation() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var annotation = new TableAnnotation("", "個人情報|取扱\n注意", Map.of());
    String info = TableDefinitionTemplates.tableInfo(table, annotation);
    assertTrue(info.contains("|public|受注|orders|table|個人情報\\|取扱<br>注意|"));
  }

  @Test
  @DisplayName("tableInfo: 論理テーブル名（DBコメント由来）に含まれる|・改行は表を崩さないようエスケープする")
  void testTableInfoEscapesLogicalTableName() {
    TableEntity table = newTable("public", "orders", "受注|管理\n(旧:注文)", "table", "");
    String info = TableDefinitionTemplates.tableInfo(table, TableAnnotation.EMPTY);
    assertTrue(info.contains("|public|受注\\|管理<br>(旧:注文)|orders|table|"));
  }

  @Test
  @DisplayName("tableExplanation: サイドカーの説明が無い場合は見出しだけの空セクション")
  void testTableExplanationEmpty() {
    assertMarkdownEquals(
        """
        ## テーブル説明

        """,
        TableDefinitionTemplates.tableExplanation(TableAnnotation.EMPTY));
  }

  @Test
  @DisplayName("tableExplanation: サイドカーの説明本文を出力する（改行はそのまま）")
  void testTableExplanationWithDescription() {
    var annotation = new TableAnnotation("1行目\n2行目", "", Map.of());
    assertMarkdownEquals(
        """
        ## テーブル説明

        1行目
        2行目

        """,
        TableDefinitionTemplates.tableExplanation(annotation));
  }

  @Test
  @DisplayName("columns: 渡されたカラムを1から採番し、PK・Not Nullを○で表す")
  void testColumns() {
    var first =
        new ColumnEntity("public", "orders", "受注ID", "order_id", "int", "", true, false, "");
    var second =
        new ColumnEntity("public", "orders", "顧客ID", "customer_id", "int", "", false, true, "");
    assertMarkdownEquals(
        """
        ## カラム情報

        | No. | 論理名 | 物理名 | データ型 | 桁数/精度 | PK | Not Null | デフォルト | 備考 |
        |:---|:---|:---|:---|:---|:---|:---|:---|:---|
        |1|受注ID|order_id|int||○||||
        |2|顧客ID|customer_id|int|||○|||

        """,
        TableDefinitionTemplates.columns(List.of(first, second), TableAnnotation.EMPTY));
  }

  @Test
  @DisplayName("columns: 物理カラム名をキーにサイドカーのカラム備考を末尾セルへ後付けする")
  void testColumnsWithAnnotation() {
    var match =
        new ColumnEntity("public", "orders", "受注ID", "order_id", "int", "", true, false, "");
    var annotation = new TableAnnotation("", "", Map.of("order_id", "受注の主キー|連番"));
    String section = TableDefinitionTemplates.columns(List.of(match), annotation);
    assertTrue(section.contains("|1|受注ID|order_id|int||○|||受注の主キー\\|連番|"));
  }

  @Test
  @DisplayName("columns: 論理名（DBコメント由来）・デフォルト値に含まれる|・改行は表を崩さないようエスケープする")
  void testColumnsEscapesLogicalNameAndDefaultValue() {
    var column =
        new ColumnEntity(
            "public",
            "orders",
            "受注コード|旧:伝票番号\n(廃止予定)",
            "order_code",
            "text",
            "",
            false,
            true,
            "'ORD-' || nextval('orders_seq')");
    String section = TableDefinitionTemplates.columns(List.of(column), TableAnnotation.EMPTY);
    assertTrue(
        section.contains(
            "|1|受注コード\\|旧:伝票番号<br>(廃止予定)|order_code|text|||○|'ORD-' \\|\\| nextval('orders_seq')||"));
  }

  @Test
  @DisplayName("view: view でない場合は空文字")
  void testViewSectionNonView() {
    TableEntity table = newTable("public", "orders", "受注", "table", "SELECT * FROM orders");
    assertEquals("", TableDefinitionTemplates.view(table));
  }

  @Test
  @DisplayName("view: view の場合は定義をSQLコードブロックで出力する")
  void testViewSectionView() {
    TableEntity table = newTable("public", "v_orders", "受注ビュー", "view", "SELECT * FROM orders");
    assertMarkdownEquals(
        """
        ## ソース

        ```sql

        SELECT * FROM orders

        ```

        """,
        TableDefinitionTemplates.view(table));
  }

  @Test
  @DisplayName("indexes: 渡されたインデックスを1から採番して出力する")
  void testIndexes() {
    var idx1 = new IndexEntity("public", "orders", "idx_orders_1", "", false, false, "", "");
    var idx2 = new IndexEntity("public", "orders", "idx_orders_2", "", false, false, "", "");
    assertMarkdownEquals(
        """
        ## インデックス情報

        | No. | インデックス名 | 種別 | UNIQUE | PRIMARY | 定義 | 備考 |
        |:---|:---|:---|:---|:---|:---|:---|
        |1|idx_orders_1||||||
        |2|idx_orders_2||||||

        """,
        TableDefinitionTemplates.indexes(List.of(idx1, idx2)));
  }

  @Test
  @DisplayName("constraints: 渡された制約を1から採番して出力する")
  void testConstraints() {
    var c1 =
        new ConstraintEntity(
            "public", "orders", "pk_orders", "PRIMARY KEY", "PRIMARY KEY (order_id)", "");
    var c2 = new ConstraintEntity("public", "orders", "uq_orders", "UNIQUE", "UNIQUE (code)", "");
    assertMarkdownEquals(
        """
        ## 制約情報

        | No. | 制約名 | 種類 | 制約定義 | 備考 |
        |:---|:---|:---|:---|:---|
        |1|pk_orders|PRIMARY KEY|PRIMARY KEY (order_id)||
        |2|uq_orders|UNIQUE|UNIQUE (code)||

        """,
        TableDefinitionTemplates.constraints(List.of(c1, c2)));
  }

  @Test
  @DisplayName("foreignKeys: 渡された外部キーを1から採番して出力する")
  void testForeignKeys() {
    var fk1 =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var fk2 = ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");
    String section = TableDefinitionTemplates.foreignKeys(List.of(fk1, fk2));
    assertTrue(section.contains("|1|fk_orders_customer|"));
    assertTrue(section.contains("|2|fk_orders_staff|"));
  }

  @Test
  @DisplayName("foreignKeys: 行の末尾に多重度の列を追加する")
  void testForeignKeysCardinalityColumn() {
    var fk =
        ForeignKeyFixtures.physical(
            "public", "profiles", "fk_profiles_user", "public", "users", Cardinality.ONE_TO_ONE);
    assertMarkdownEquals(
        """
        ## 外部キー情報

        | No. | 外部キー名 | カラムリスト | 参照先 | 参照先カラムリスト | 多重度 |
        |:---|:---|:---|:---|:---|:---|
        |1|fk_profiles_user|unused|public.users|unused|1対1|

        """,
        TableDefinitionTemplates.foreignKeys(List.of(fk)));
  }

  @Test
  @DisplayName("triggers: 渡されたトリガーを1から採番して出力する")
  void testTriggers() {
    var t1 =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "public.f_orders",
            "CREATE TRIGGER trg_orders ...",
            "");
    var t2 =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders_audit",
            "AFTER",
            List.of("UPDATE"),
            "ROW",
            "public.f_audit",
            "CREATE TRIGGER trg_orders_audit ...",
            "");
    assertMarkdownEquals(
        """
        ## トリガー情報

        | No. | トリガー名 | タイミング | イベント | 単位 | 定義 |
        |:---|:---|:---|:---|:---|:---|
        |1|trg_orders|BEFORE|INSERT|ROW|CREATE TRIGGER trg_orders ...|
        |2|trg_orders_audit|AFTER|UPDATE|ROW|CREATE TRIGGER trg_orders_audit ...|

        """,
        TableDefinitionTemplates.triggers(List.of(t1, t2)));
  }

  @Test
  @DisplayName("triggers: 本体を持つトリガー（Oracle）は、表の後にトリガーごとの見出しとコードブロックで本体を出力する")
  void testTriggerBodies() {
    var withBody =
        new TriggerEntity(
            "SAMPLE",
            "ORDERS",
            "TRG_ORDERS",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "",
            "CREATE OR REPLACE TRIGGER sample.trg_orders before insert on sample.orders for each row",
            "begin\n    :new.created_at := systimestamp;\nend;");
    var withoutBody =
        new TriggerEntity(
            "SAMPLE", "ORDERS", "TRG_NO_BODY", "AFTER", List.of("DELETE"), "STATEMENT", "", "", "");
    assertMarkdownEquals(
        """
        ## トリガー情報

        | No. | トリガー名 | タイミング | イベント | 単位 | 定義 |
        |:---|:---|:---|:---|:---|:---|
        |1|TRG_ORDERS|BEFORE|INSERT|ROW|CREATE OR REPLACE TRIGGER sample.trg_orders before insert on sample.orders for each row|
        |2|TRG_NO_BODY|AFTER|DELETE|STATEMENT||

        ### TRG_ORDERS

        ```sql
        begin
            :new.created_at := systimestamp;
        end;
        ```

        """,
        TableDefinitionTemplates.triggers(List.of(withBody, withoutBody)));
  }

  @Test
  @DisplayName("indexes/constraints/triggers: 定義に含まれる|は表を崩さないようエスケープする")
  void testDefinitionsEscapePipe() {
    var idx =
        new IndexEntity(
            "public",
            "orders",
            "idx_orders_code",
            "btree",
            false,
            false,
            "CREATE INDEX idx_orders_code ON public.orders USING btree (((a || b)))",
            "");
    var c =
        new ConstraintEntity(
            "public", "orders", "chk_code", "CHECK", "CHECK ((code ~ '^(A|B)$'::text))", "");
    var t =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "public.f_orders",
            "CREATE TRIGGER trg_orders WHEN ((new.a || new.b) IS NOT NULL)",
            "");
    assertTrue(
        TableDefinitionTemplates.indexes(List.of(idx)).contains("USING btree (((a \\|\\| b)))|"));
    assertTrue(
        TableDefinitionTemplates.constraints(List.of(c))
            .contains("|CHECK ((code ~ '^(A\\|B)$'::text))|"));
    assertTrue(
        TableDefinitionTemplates.triggers(List.of(t))
            .contains("WHEN ((new.a \\|\\| new.b) IS NOT NULL)|"));
  }

  @Test
  @DisplayName("indexes/constraints: 備考（DBコメント由来）に含まれる|・改行は表を崩さないようエスケープする")
  void testRemarksEscapeTableCell() {
    var idx =
        new IndexEntity(
            "public",
            "orders",
            "idx_orders_code",
            "btree",
            false,
            false,
            "",
            "運用メモ|旧インデックス\n(削除予定)");
    var c = new ConstraintEntity("public", "orders", "chk_code", "CHECK", "", "運用メモ|注意事項\n(要確認)");
    assertTrue(
        TableDefinitionTemplates.indexes(List.of(idx)).contains("|運用メモ\\|旧インデックス<br>(削除予定)|"));
    assertTrue(TableDefinitionTemplates.constraints(List.of(c)).contains("|運用メモ\\|注意事項<br>(要確認)|"));
  }

  @Test
  @DisplayName("erDiagram: 関連テーブルがない場合はMermaidを出力せずメッセージのみ")
  void testErDiagramNoRelations() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    assertMarkdownEquals(
        """
        ## ER図

        関連するテーブルはありません。

        """,
        TableDefinitionTemplates.erDiagram(
            table, List.of(), neighborhood(List.of(), List.of()), DiagramBoxesFixtures.none()));
  }

  @Test
  @DisplayName("erDiagram: 参照先/参照元の関係線と、自テーブルの属性（型の括弧除去・空白はアンダースコア・PK表記）を出力する")
  void testErDiagramWithRelations() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column =
        EntityFixtures.column("public", "orders", "order_id", "character varying(20)", true);
    var outgoing =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var incoming =
        ForeignKeyFixtures.physical("public", "items", "fk_items_orders", "public", "orders");
    // 参照先(customers) -> 自テーブル(orders) -> 参照元(items) の順に関係線を出力する
    assertMarkdownEquals(
        """
        ## ER図

        ```mermaid
        erDiagram
            public_orders["orders"]
            public_customers["customers"]
            public_items["items"]
            public_customers ||--o{ public_orders : "fk_orders_customer"
            public_orders ||--o{ public_items : "fk_items_orders"
            public_orders {
                character_varying order_id PK
            }
        ```

        """,
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(outgoing), List.of(incoming)),
            DiagramBoxesFixtures.none()));
  }

  @Test
  @DisplayName("erDiagram: 描画距離を縮めた場合は、その旨を図の前に書き、関連先のテーブル同士の関連も描く")
  void testErDiagramWithShortenedNeighborhood() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var outgoing =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var secondHop =
        ForeignKeyFixtures.physical(
            "public", "customers", "fk_customers_region", "public", "region");

    assertMarkdownEquals(
        """
        ## ER図

        関連するテーブルが多く、3段先までを描画するとテーブル数が上限（erDiagramMaxNodes）を超えるため、2段先までを描画しています。

        ```mermaid
        erDiagram
            public_orders["orders"]
            public_customers["customers"]
            public_region["region"]
            public_customers ||--o{ public_orders : "fk_orders_customer"
            public_region ||--o{ public_customers : "fk_customers_region"
        ```

        """,
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(),
            new DiagramNeighborhood(List.of(outgoing, secondHop), 2, 3),
            DiagramBoxesFixtures.none()));
  }

  @Test
  @DisplayName("erDiagram: 参照先・参照元に同名テーブルが複数スキーマにまたがる場合は表示ラベルをスキーマ.テーブルにする")
  void testErDiagramQualifiesLabelOnCrossSchemaNameCollision() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column =
        EntityFixtures.column("public", "orders", "order_id", "character varying(20)", true);
    var outgoing =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_customer", "sales", "customers");
    var incoming =
        ForeignKeyFixtures.physical(
            "master", "customers", "fk_customers_orders", "public", "orders");
    String section =
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(outgoing), List.of(incoming)),
            DiagramBoxesFixtures.none());
    assertTrue(section.contains("sales_customers[\"sales.customers\"]"));
    assertTrue(section.contains("master_customers[\"master.customers\"]"));
    // 衝突していない自テーブルはテーブル名のみのまま
    assertTrue(section.contains("public_orders[\"orders\"]"));
  }

  @Test
  @DisplayName("erDiagram: 参照先・参照元それぞれの多重度に応じた関係線を出力する")
  void testErDiagramCardinality() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column =
        EntityFixtures.column("public", "orders", "order_id", "character varying(20)", true);
    var outgoing =
        ForeignKeyFixtures.physical(
            "public",
            "orders",
            "fk_orders_coupon",
            "public",
            "coupons",
            Cardinality.OPTIONAL_ONE_TO_MANY);
    var incoming =
        ForeignKeyFixtures.physical(
            "public",
            "order_details",
            "fk_details_orders",
            "public",
            "orders",
            Cardinality.ONE_TO_ONE);
    String section =
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(outgoing), List.of(incoming)),
            DiagramBoxesFixtures.none());
    assertTrue(section.contains("public_coupons |o--o{ public_orders : \"fk_orders_coupon\""));
    assertTrue(
        section.contains("public_orders ||--o| public_order_details : \"fk_details_orders\""));
  }

  @Test
  @DisplayName("erDiagram: 自テーブルは全カラムを、関連テーブルは関連をつなぐカラムだけを、論理名とともに表示する")
  void testErDiagramShowsRelationColumnsOfRelatedTables() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var id = new ColumnEntity("public", "orders", "受注ID", "id", "integer", "", true, true, "");
    var customerId =
        new ColumnEntity("public", "orders", "顧客ID", "customer_id", "integer", "", false, true, "");
    var outgoing =
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
    var incoming =
        new ForeignKeyEntity(
            "public",
            "items",
            "fk_items_orders",
            List.of("order_id"),
            "public",
            "orders",
            List.of("id"),
            Cardinality.ONE_TO_MANY,
            RelationType.PHYSICAL);
    var boxes =
        DiagramBoxesFixtures.of(
            List.of(
                table,
                newTable("public", "customers", "顧客", "table", ""),
                newTable("public", "items", "", "table", "")),
            List.of(outgoing, incoming),
            List.of(
                new ColumnEntity(
                    "public", "customers", "顧客ID", "id", "integer", "", true, true, ""),
                new ColumnEntity("public", "customers", "顧客名", "name", "text", "", false, true, ""),
                new ColumnEntity("public", "items", "", "order_id", "integer", "", true, true, ""),
                new ColumnEntity("public", "items", "", "item_no", "integer", "", true, true, "")));
    assertMarkdownEquals(
        """
        ## ER図

        ```mermaid
        erDiagram
            public_orders["orders（受注）"]
            public_customers["customers（顧客）"]
            public_items["items"]
            public_customers ||--o{ public_orders : "fk_orders_customer"
            public_orders ||--o{ public_items : "fk_items_orders"
            public_orders {
                integer id PK "受注ID"
                integer customer_id FK "顧客ID"
            }
            public_customers {
                integer id PK "顧客ID"
            }
            public_items {
                integer order_id PK, FK
            }
        ```

        """,
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(id, customerId),
            neighborhood(List.of(outgoing), List.of(incoming)),
            boxes));
  }

  @Test
  @DisplayName("erDiagram: データ型の桁数指定は除去される")
  void testErDiagramSanitizesType() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column = EntityFixtures.column("public", "orders", "amount", "numeric(10,2)", false);
    var outgoing =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    String section =
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(outgoing), List.of()),
            DiagramBoxesFixtures.none());
    assertTrue(section.contains("numeric amount"));
    assertFalse(section.contains("(10,2)"));
  }

  @Test
  @DisplayName("footer: 区切り線とテーブル一覧へのリンクを出力する")
  void testFooter() {
    var base = new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
    assertMarkdownEquals(
        """
        ___

        [テーブル一覧へ](../../tableList_TEST_DB.md)
        """,
        TableDefinitionTemplates.footer(base));
  }

  @Test
  @DisplayName("logicalRelations: 論理リレーションを専用セクションに、セクション内で1から採番して出力する")
  void testLogicalRelationsSection() {
    var rel1 =
        ForeignKeyFixtures.logical(
            "public",
            "orders",
            "rel_orders_staff",
            List.of("staff_id"),
            "public",
            "staff",
            List.of("id"),
            Cardinality.ONE_TO_MANY);
    var rel2 =
        ForeignKeyFixtures.logical(
            "public",
            "orders",
            "rel_orders_coupon",
            List.of("coupon_code"),
            "public",
            "coupons",
            List.of("code"),
            Cardinality.OPTIONAL_ONE_TO_ONE);
    // 物理外部キーの採番とは独立に、当セクション内で1から振り直す
    assertMarkdownEquals(
        """
        ## 論理リレーション情報

        ※DBに外部キー制約は存在せず、サイドカーYAMLで宣言された関連です。

        | No. | カラムリスト | 参照先 | 参照先カラムリスト | 多重度 |
        |:---|:---|:---|:---|:---|
        |1|staff_id|public.staff|id|1対多|
        |2|coupon_code|public.coupons|code|0..1対1|

        """,
        TableDefinitionTemplates.logicalRelations(List.of(rel1, rel2)));
  }

  @Test
  @DisplayName("logicalRelations: 対象が存在しない場合はセクションごと出力しない")
  void testLogicalRelationsSectionOmittedWhenEmpty() {
    assertEquals("", TableDefinitionTemplates.logicalRelations(List.of()));
  }

  @Test
  @DisplayName("incomingRelations: 参照元を物理・論理の区別付きで、セクション内で1から採番して出力する")
  void testIncomingRelationsSection() {
    var physical =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");
    var logical =
        ForeignKeyFixtures.logical(
            "audit",
            "audit_log",
            "staff_id",
            List.of("staff_id"),
            "public",
            "staff",
            List.of("id"),
            Cardinality.OPTIONAL_ONE_TO_MANY);
    String section = TableDefinitionTemplates.incomingRelations(List.of(physical, logical));

    assertTrue(section.startsWith("## 被参照情報"));
    assertTrue(section.contains("| No. | 参照元 | 参照元カラムリスト | 参照されるカラムリスト | 関連名 | 多重度 | 区分 |"));
    assertTrue(section.contains("|1|public.orders|"));
    assertTrue(section.contains("|fk_orders_staff|"));
    assertTrue(section.contains("|物理|"));
    assertTrue(section.contains("|2|audit.audit_log|staff_id|id|staff_id|0..1対多|論理|"));
  }

  @Test
  @DisplayName("incomingRelations: 参照元が存在しない場合はセクションごと出力しない")
  void testIncomingRelationsSectionOmittedWhenEmpty() {
    assertEquals("", TableDefinitionTemplates.incomingRelations(List.of()));
  }

  @Test
  @DisplayName("referencedTables: ビューが参照するテーブルを、区分付きでセクション内で1から採番して出力する")
  void testReferencedTablesSection() {
    String section =
        TableDefinitionTemplates.referencedTables(
            List.of(
                new ViewReferenceEntity(
                    "public", "v", TableType.VIEW, "public", "orders", TableType.TABLE),
                new ViewReferenceEntity(
                    "public", "v", TableType.VIEW, "archive", "orders_v", TableType.VIEW)));

    assertMarkdownEquals(
        """
        ## 参照するテーブル

        | No. | 参照先 | 区分 |
        |:---|:---|:---|
        |1|public.orders|table|
        |2|archive.orders_v|view|

        """,
        section);
  }

  @Test
  @DisplayName("referencingViews: テーブルを参照しているビューを、ビューの区分付きで出力する")
  void testReferencingViewsSection() {
    String section =
        TableDefinitionTemplates.referencingViews(
            List.of(
                new ViewReferenceEntity(
                    "public",
                    "orders_mv",
                    TableType.MATERIALIZED_VIEW,
                    "public",
                    "orders",
                    TableType.TABLE)));

    assertMarkdownEquals(
        """
        ## 参照しているビュー

        | No. | 参照元 | 区分 |
        |:---|:---|:---|
        |1|public.orders_mv|materialized_view|

        """,
        section);
  }

  @Test
  @DisplayName("referencedTables・referencingViews: 対象が存在しない場合はセクションごと出力しない")
  void testViewReferenceSectionsOmittedWhenEmpty() {
    assertEquals("", TableDefinitionTemplates.referencedTables(List.of()));
    assertEquals("", TableDefinitionTemplates.referencingViews(List.of()));
  }

  @Test
  @DisplayName("erDiagram: 論理リレーションは破線、物理外部キーは実線で描画する")
  void testErDiagramDistinguishesRelationType() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column = EntityFixtures.column("public", "orders", "order_id", "int", true);
    var physical =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    String section =
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(physical, logical), List.of()),
            DiagramBoxesFixtures.none());

    assertTrue(section.contains("public_customers ||--o{ public_orders : \"fk_orders_customer\""));
    assertTrue(section.contains("public_staff ||..o{ public_orders : \"rel_orders_staff\""));
  }

  @Test
  @DisplayName("erDiagram: 被参照側の論理リレーションも破線で描画する")
  void testErDiagramIncomingLogicalIsDashed() {
    TableEntity table = newTable("public", "orders", "受注", "table", "");
    var column = EntityFixtures.column("public", "orders", "order_id", "int", true);
    var incoming =
        ForeignKeyFixtures.logical("public", "audit_log", "rel_audit_orders", "public", "orders");
    String section =
        TableDefinitionTemplates.erDiagram(
            table,
            List.of(column),
            neighborhood(List.of(), List.of(incoming)),
            DiagramBoxesFixtures.none());

    assertTrue(section.contains("public_orders ||..o{ public_audit_log : \"rel_audit_orders\""));
  }

  @Test
  @DisplayName("viewpoints: 所属する観点が無い場合はセクションごと出力しない（観点を導入しても定義書は変わらない）")
  void testViewpointsEmpty() {
    var base = new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
    assertEquals("", TableDefinitionTemplates.viewpoints(List.of(), base));
  }

  @Test
  @DisplayName("viewpoints: 所属する観点の表示名と、観点ページへの相対リンクを宣言順に出力する")
  void testViewpoints() {
    var base = new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
    assertMarkdownEquals(
        """
        ## 所属する観点

        * [受注管理](../../viewpoint_TEST_DB_order.md) \s
        * [master](../../viewpoint_TEST_DB_master.md) \s

        """,
        TableDefinitionTemplates.viewpoints(
            List.of(
                Viewpoint.of("order", "受注管理", "", List.of("orders")),
                Viewpoint.of("master", "", "", List.of("orders"))),
            base));
  }

  private TableEntity partitionedTable(String partitionKey) {
    return new TableEntity("TEST_DB", "public", "売上", "sales", TableType.TABLE, "", partitionKey);
  }

  private PartitionEntity partition(
      String partitionSchema,
      String name,
      String parentSchema,
      String parent,
      String bound,
      String partitionKey) {
    return new PartitionEntity(
        "public", "sales", partitionSchema, name, parentSchema, parent, bound, partitionKey);
  }

  @Test
  @DisplayName("partitions: パーティション表でないテーブルでは空文字")
  void testPartitionsNonPartitionedTable() {
    assertEquals(
        "",
        TableDefinitionTemplates.partitions(
            newTable("public", "customer", "", "table", ""), List.of()));
  }

  @Test
  @DisplayName("partitions: パーティションが無いパーティション表では、パーティションキーとその旨のみ出力する")
  void testPartitionsWithoutPartitions() {
    assertMarkdownEquals(
        """
        ## パーティション情報

        パーティションキー: `RANGE (sold_on)`

        パーティションはありません。

        """,
        TableDefinitionTemplates.partitions(partitionedTable("RANGE (sold_on)"), List.of()));
  }

  @Test
  @DisplayName("partitions: パーティションを1から採番し、多段パーティションは親の列で入れ子を示す")
  void testPartitionsNested() {
    var p1 =
        partition(
            "public",
            "sales_2026_01",
            "public",
            "sales",
            "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')",
            "");
    var p2 =
        partition(
            "public",
            "sales_2026_03",
            "public",
            "sales",
            "FOR VALUES FROM ('2026-03-01') TO ('2026-04-01')",
            "RANGE (sold_on)");
    var p3 =
        partition(
            "public",
            "sales_2026_03_a",
            "public",
            "sales_2026_03",
            "FOR VALUES FROM ('2026-03-01') TO ('2026-03-16')",
            "");

    assertMarkdownEquals(
        """
        ## パーティション情報

        パーティションキー: `RANGE (sold_on)`

        | No. | パーティション | 親 | パーティション境界 | 下位のパーティションキー |
        |:---|:---|:---|:---|:---|
        |1|sales_2026_01|sales|FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')||
        |2|sales_2026_03|sales|FOR VALUES FROM ('2026-03-01') TO ('2026-04-01')|RANGE (sold_on)|
        |3|sales_2026_03_a|sales_2026_03|FOR VALUES FROM ('2026-03-01') TO ('2026-03-16')||

        """,
        TableDefinitionTemplates.partitions(
            partitionedTable("RANGE (sold_on)"), List.of(p1, p2, p3)));
  }

  @Test
  @DisplayName("partitions: 別スキーマのパーティションはスキーマ修飾で表し、境界に含まれる|は表を崩さないようエスケープする")
  void testPartitionsOtherSchemaAndEscape() {
    var archived =
        partition("archive", "sales_2020", "public", "sales", "FOR VALUES IN ('a|b')", "");

    assertMarkdownEquals(
        """
        ## パーティション情報

        パーティションキー: `LIST (region)`

        | No. | パーティション | 親 | パーティション境界 | 下位のパーティションキー |
        |:---|:---|:---|:---|:---|
        |1|archive.sales_2020|sales|FOR VALUES IN ('a\\|b')||

        """,
        TableDefinitionTemplates.partitions(partitionedTable("LIST (region)"), List.of(archived)));
  }

  /** 自テーブルの関連だけを描く（描画距離1の）ER図の関連 */
  private static DiagramNeighborhood neighborhood(
      List<ForeignKeyEntity> outgoing, List<ForeignKeyEntity> incoming) {
    return new DiagramNeighborhood(
        java.util.stream.Stream.concat(outgoing.stream(), incoming.stream()).toList(), 1, 1);
  }
}
