package com.dbxray.infrastructure.file.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.shared.exception.UserCorrectableException;
import com.dbxray.testsupport.CapturedLogs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SidecarYamlRepository のサイドカーYAML読み込みに関するテスト */
public class SidecarYamlRepositoryTest {

  private final SidecarYamlRepository repository = new SidecarYamlRepository();

  private TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "");
  }

  private Path writeYaml(Path dir, String content) throws IOException {
    Path file = dir.resolve("annotations.yml");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  @DisplayName("load: 説明（複数行）・テーブル備考・カラム備考を読み込む")
  void testLoadFull(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                tables:
                  public.users:
                    description: |
                      ユーザー基本情報。
                      認証と紐づく。
                    remarks: 個人情報を含む
                    columns:
                      email: ログインID兼用
                      status: 0=無効 1=有効
                """);

    Annotations annotations = repository.load(file.toString()).annotations();
    TableAnnotation users = annotations.belongingTo(table("public", "users"));

    assertEquals("ユーザー基本情報。\n認証と紐づく。\n", users.description().replace("\r\n", "\n"));
    assertEquals("個人情報を含む", users.remarks());
    assertEquals("ログインID兼用", users.columnRemark("email"));
    assertEquals("0=無効 1=有効", users.columnRemark("status"));
  }

  @Test
  @DisplayName("load: パスが空・null・空白の場合は空のAnnotationsを返す")
  void testLoadBlankPath() {
    assertTrue(repository.load(null).annotations().isEmpty());
    assertTrue(repository.load("").annotations().isEmpty());
    assertTrue(repository.load("   ").annotations().isEmpty());
  }

  @Test
  @DisplayName("load: 指定したファイルが存在しない場合は、付帯情報なしで続行せず、利用者が直せる誤りを投げる")
  void testLoadMissingFile(@TempDir Path dir) {
    UserCorrectableException e =
        assertThrows(
            UserCorrectableException.class,
            () -> repository.load(dir.resolve("not_exist.yml").toString()));
    assertTrue(e.getMessage().contains("not_exist.yml"));
  }

  @Test
  @DisplayName("load: 指定したパスがファイルでない（ディレクトリ等）場合は、利用者が直せる誤りを投げる")
  void testLoadDirectory(@TempDir Path dir) {
    assertThrows(UserCorrectableException.class, () -> repository.load(dir.toString()));
  }

  @Test
  @DisplayName("load: 'schema.table'形式でないキーは読み飛ばす")
  void testLoadIgnoresInvalidKey(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                tables:
                  invalidkey:
                    remarks: 無視される
                  public.orders:
                    remarks: 有効
                """);

    Annotations annotations = repository.load(file.toString()).annotations();

    assertEquals(1, annotations.tableKeys().size());
    assertEquals("有効", annotations.belongingTo(table("public", "orders")).remarks());
  }

  @Test
  @DisplayName("load: tablesキーが存在しない場合は空のAnnotationsを返す")
  void testLoadNoTablesKey(@TempDir Path dir) throws IOException {
    Path file = writeYaml(dir, "other: value\n");
    assertTrue(repository.load(file.toString()).annotations().isEmpty());
  }

  @Test
  @DisplayName("load: スキーマ名にドットが無いテーブル名も、最初のドットで分割して解釈する")
  void testLoadSplitsOnFirstDot(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                tables:
                  public.my.table:
                    remarks: ドット入りテーブル名
                """);

    Annotations annotations = repository.load(file.toString()).annotations();

    assertEquals("ドット入りテーブル名", annotations.belongingTo(table("public", "my.table")).remarks());
  }

  @Test
  @DisplayName("load: relations の全項目を指定した論理リレーションを読み込む")
  void testLoadRelationFull(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                    cardinality: 0..1対多
                """);

    List<ForeignKeyEntity> relations = repository.load(file.toString()).logicalRelations();

    assertEquals(1, relations.size());
    ForeignKeyEntity relation = relations.get(0);
    assertEquals("public", relation.schemaName());
    assertEquals("logs", relation.tableName());
    assertEquals("user_id", relation.foreignKeyName());
    assertEquals(List.of("user_id"), relation.columnNames());
    assertEquals("public.users", relation.referenceQualifiedName());
    assertEquals(List.of("id"), relation.referenceColumnNames());
    assertEquals(Cardinality.OPTIONAL_ONE_TO_MANY, relation.cardinality());
    assertEquals(RelationType.LOGICAL, relation.relationType());
    assertTrue(relation.isLogical());
  }

  @Test
  @DisplayName("load: 複合キーの論理リレーションはカラムをカンマ区切りで連結する")
  void testLoadRelationCompositeKey(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.shipment
                    columns: [warehouse_code, zone_code]
                    parentTable: public.warehouse_zone
                    parentColumns: [warehouse_code, zone_code]
                """);

    ForeignKeyEntity relation = repository.load(file.toString()).logicalRelations().get(0);

    assertEquals(List.of("warehouse_code", "zone_code"), relation.columnNames());
    assertEquals(List.of("warehouse_code", "zone_code"), relation.referenceColumnNames());
  }

  @Test
  @DisplayName("load: 単一カラムはリストでなくスカラーでも指定できる")
  void testLoadRelationScalarColumns(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: user_id
                    parentTable: public.users
                    parentColumns: id
                """);

    ForeignKeyEntity relation = repository.load(file.toString()).logicalRelations().get(0);

    assertEquals(List.of("user_id"), relation.columnNames());
    assertEquals(List.of("id"), relation.referenceColumnNames());
  }

  @Test
  @DisplayName("load: 関連名はカラム名から自動生成する")
  void testLoadRelationGeneratesName(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                """);

    assertEquals(
        "user_id", repository.load(file.toString()).logicalRelations().get(0).foreignKeyName());
  }

  @Test
  @DisplayName("load: name キーを指定しても未知のキーとして読み飛ばし、関連名はカラム名から自動生成する")
  void testLoadRelationIgnoresNameKey(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                    name: rel_logs_users
                """);

    assertEquals(
        "user_id", repository.load(file.toString()).logicalRelations().get(0).foreignKeyName());
  }

  @Test
  @DisplayName("load: cardinality 省略時・未知の値の場合は既定の1対多とする")
  void testLoadRelationCardinalityFallback(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                  - table: public.events
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                    cardinality: 多対多
                """);

    List<ForeignKeyEntity> relations = repository.load(file.toString()).logicalRelations();

    assertEquals(Cardinality.ONE_TO_MANY, relations.get(0).cardinality());
    assertEquals(Cardinality.ONE_TO_MANY, relations.get(1).cardinality());
  }

  @Test
  @DisplayName("load: 必須項目が欠けている relations の要素は読み飛ばす")
  void testLoadRelationIgnoresIncomplete(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    parentTable: public.users
                    parentColumns: [id]
                  - table: public.logs
                    columns: [user_id]
                    parentColumns: [id]
                  - table: invalidkey
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                  - table: public.events
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                """);

    List<ForeignKeyEntity> relations = repository.load(file.toString()).logicalRelations();

    assertEquals(1, relations.size());
    assertEquals("events", relations.get(0).tableName());
  }

  @Test
  @DisplayName("load: relations キーが無い・リストでない場合は空のリストを返す")
  void testLoadNoRelations(@TempDir Path dir) throws IOException {
    Path noKey = writeYaml(dir, "tables:\n  public.users:\n    remarks: 備考\n");
    assertTrue(repository.load(noKey.toString()).logicalRelations().isEmpty());

    Path notList = dir.resolve("not_list.yml");
    Files.writeString(notList, "relations:\n  table: public.logs\n", StandardCharsets.UTF_8);
    assertTrue(repository.load(notList.toString()).logicalRelations().isEmpty());
  }

  @Test
  @DisplayName("load: tables と relations は同一ファイルに共存できる")
  void testLoadTablesAndRelationsTogether(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                tables:
                  public.logs:
                    remarks: 監査ログ
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                """);

    var sidecar = repository.load(file.toString());

    assertEquals("監査ログ", sidecar.annotations().belongingTo(table("public", "logs")).remarks());
    assertEquals(1, sidecar.logicalRelations().size());
  }

  @Test
  @DisplayName("load: relations のみを記述したサイドカーも有効に読み込める")
  void testLoadRelationsOnly(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                relations:
                  - table: public.logs
                    columns: [user_id]
                    parentTable: public.users
                    parentColumns: [id]
                """);

    var sidecar = repository.load(file.toString());

    assertTrue(sidecar.annotations().isEmpty());
    assertEquals(1, sidecar.logicalRelations().size());
  }

  @Test
  @DisplayName("load: YAMLとして解釈できない場合は、ファイルのパスと失敗箇所の行を添えた利用者が直せる誤りを投げ、ファイルの内容は含めない")
  void testLoadRejectsInvalidYaml(@TempDir Path dir) throws IOException {
    Path file = writeYaml(dir, "tables:\n  public.users:\n    description: [unclosed-text\n");

    UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> repository.load(file.toString()));

    assertTrue(e.getMessage().contains(file.toString()));
    assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    assertFalse(e.getMessage().contains("unclosed-text"), e.getMessage());
    assertNull(e.getCause());
  }

  @Test
  @DisplayName("load: UTF-8として読めないファイルも、利用者が直せる誤りとして投げる")
  void testLoadRejectsNonUtf8File(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("annotations.yml");
    // Shift_JISで保存した「説明」（UTF-8としては不正なバイト列）
    Files.write(file, "tables:\n  public.users:\n    description: 説明\n".getBytes("Shift_JIS"));

    assertThrows(UserCorrectableException.class, () -> repository.load(file.toString()));
  }

  @Test
  @DisplayName("load: 未知のキー（キー名の書き誤り等）は読み飛ばし、書けるキーの内容は読み込む")
  void testLoadIgnoresUnknownKeys(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
            tabels: {}
            tables:
              public.users:
                descripton: 書き誤り
                remarks: テーブル備考
            relations:
              - table: public.logs
                columns: [user_id]
                parentTable: public.users
                parentColumns: [id]
                nmae: rel_logs_users
            """);

    var sidecar = repository.load(file.toString());

    TableAnnotation users = sidecar.annotations().belongingTo(table("public", "users"));
    assertEquals("", users.description());
    assertEquals("テーブル備考", users.remarks());
    assertEquals(1, sidecar.logicalRelations().size());
    // 書き誤った関連名（nmae）は読み飛ばし、関連名は自動生成される
    assertEquals("user_id", sidecar.logicalRelations().get(0).foreignKeyName());
  }

  @Test
  @DisplayName("load: トップレベルがマップでない場合は、内容を読み飛ばして空のSidecarを返す")
  void testLoadIgnoresNonMappingRoot(@TempDir Path dir) throws IOException {
    Path file = writeYaml(dir, "- public.users\n");

    var sidecar = repository.load(file.toString());

    assertTrue(sidecar.annotations().isEmpty());
    assertTrue(sidecar.logicalRelations().isEmpty());
  }

  @Test
  @DisplayName("load: マップでないテーブルの記述は読み飛ばし、マップでないcolumnsはカラム備考のみ読み飛ばす")
  void testLoadIgnoresNonMappingEntries(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
            tables:
              public.logs: 説明のつもり
              public.users:
                remarks: テーブル備考
                columns: [email]
            """);

    var sidecar = repository.load(file.toString());

    assertEquals(Set.of(TableKey.of("public", "users")), sidecar.annotations().tableKeys());
    TableAnnotation users = sidecar.annotations().belongingTo(table("public", "users"));
    assertEquals("テーブル備考", users.remarks());
    assertEquals("", users.columnRemark("email"));
  }

  @Test
  @DisplayName("load: viewpoints の観点を宣言順に読み込み、所属テーブルはtable=と同じ記法で指定できる")
  void testLoadViewpoints(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                viewpoints:
                  - id: order
                    name: 受注管理
                    description: |
                      受注から出荷指示まで。
                      請求は含まない。
                    tables:
                      - public.order*
                      - public.customer
                      - "!public.order_bk"
                  - id: master
                    tables: public.product
                """);

    Viewpoints viewpoints = repository.load(file.toString()).viewpoints();

    assertEquals(
        List.of("order", "master"), viewpoints.asList().stream().map(Viewpoint::id).toList());
    Viewpoint order = viewpoints.asList().get(0);
    assertEquals("受注管理", order.name());
    assertEquals("受注から出荷指示まで。\n請求は含まない。", order.description());
    assertTrue(order.contains(table("public", "order_detail")));
    assertTrue(order.contains(table("public", "customer")));
    assertFalse(order.contains(table("public", "order_bk")));
    Viewpoint master = viewpoints.asList().get(1);
    assertEquals("master", master.name());
    assertTrue(master.contains(table("public", "product")));
  }

  @Test
  @DisplayName("load: 観点として成り立たない定義（識別子の誤り・所属テーブルの指定漏れ）と識別子が既出の定義は読み飛ばし、他の定義は読み込む")
  void testLoadViewpointsSkipsInvalidEntries(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
                viewpoints:
                  - id: 受注
                    tables: [public.orders]
                  - id: no_tables
                  - id: exclude_only
                    tables: ["!public.orders"]
                  - id: order
                    tables: [public.orders]
                    unknown: ignored
                  - id: order
                    tables: [public.customer]
                  - not a mapping
                """);

    Viewpoints viewpoints = repository.load(file.toString()).viewpoints();

    assertEquals(List.of("order"), viewpoints.asList().stream().map(Viewpoint::id).toList());
    assertTrue(viewpoints.asList().getFirst().contains(table("public", "orders")));
  }

  @Test
  @DisplayName("load: viewpoints がリストでない場合・未指定の場合は観点なしとする")
  void testLoadViewpointsNotAList(@TempDir Path dir) throws IOException {
    Path notAList = writeYaml(dir, "viewpoints:\n  order: [public.orders]\n");
    assertTrue(repository.load(notAList.toString()).viewpoints().isEmpty());

    Path absent = writeYaml(dir, "tables: {}\n");
    assertTrue(repository.load(absent.toString()).viewpoints().isEmpty());
  }

  @Test
  @DisplayName("load: 必須項目の欠けた論理リレーションは、何件目の定義か・欠けた項目を1件の警告にまとめ、形式の誤りとしては報告しない")
  void testLoadWarnsMissingRelationKeysOnce(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
            relations:
              - table: public.logs
                columns: [user_id]
                parentTable: public.users
                parentColumns: [id]
              - columns: [user_id]
                parentColumns: [id]
            """);

    try (CapturedLogs logs = CapturedLogs.of(SidecarYamlRepository.class)) {
      assertEquals(1, repository.load(file.toString()).logicalRelations().size());

      assertEquals(
          List.of(
              "Ignoring relation missing required keys. [entry=relations #2, missing=table, parentTable, annotations="
                  + file
                  + "]"),
          logs.messages(Level.WARN));
    }
  }

  @Test
  @DisplayName("load: 論理リレーションのテーブル名の形式の誤りは、何件目の定義のどの項目かを添えて警告する")
  void testLoadWarnsInvalidRelationTableWithLocation(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
            relations:
              - table: logs
                columns: [user_id]
                parentTable: public.users
                parentColumns: [id]
                form: x
            """);

    try (CapturedLogs logs = CapturedLogs.of(SidecarYamlRepository.class)) {
      assertTrue(repository.load(file.toString()).logicalRelations().isEmpty());

      assertEquals(
          List.of(
              "Ignoring unknown key in relations #1. [key=form, annotations=" + file + "]",
              "Ignoring table name not in 'schema.table' format. "
                  + "[table=logs, location=relations #1 table, annotations="
                  + file
                  + "]"),
          logs.messages(Level.WARN));
    }
  }

  @Test
  @DisplayName("load: 観点として成り立たない定義は、何件目の定義かを添えて警告する")
  void testLoadWarnsInvalidViewpointWithEntry(@TempDir Path dir) throws IOException {
    Path file =
        writeYaml(
            dir,
            """
            viewpoints:
              - name: 識別子なし
                tables: [public.users]
              - just-a-string
            """);

    try (CapturedLogs logs = CapturedLogs.of(SidecarYamlRepository.class)) {
      assertTrue(repository.load(file.toString()).viewpoints().isEmpty());

      List<String> warnings = logs.messages(Level.WARN);
      assertEquals(2, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("[entry=viewpoints #1, id=,"), warnings.get(0));
      assertEquals(
          "Ignoring viewpoint because it is not a mapping. [entry=viewpoints #2, annotations="
              + file
              + "]",
          warnings.get(1));
    }
  }
}
