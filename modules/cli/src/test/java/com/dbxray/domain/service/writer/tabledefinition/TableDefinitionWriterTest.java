package com.dbxray.domain.service.writer.tabledefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramNeighborhood;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.EntityFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableDefinitionWriter のテーブル一覧・テーブル定義書き込みに関するテスト */
public class TableDefinitionWriterTest {

  private static final Path OUT = Path.of("output");

  /** 書き込み内容・ディレクトリ作成呼び出しをメモリ上に収集するFileRepositoryのスタブ */
  private static class InMemoryFileRepository implements FileRepository {
    private final Map<Path, String> files = new LinkedHashMap<>();
    private final List<Path> createdDirectories = new ArrayList<>();

    @Override
    public void writeFile(Path filePath, List<String> contents) {
      files.put(filePath, String.join("", contents));
    }

    @Override
    public void appendFile(Path filePath, List<String> contents) {
      files.merge(filePath, String.join("", contents), String::concat);
    }

    @Override
    public void createDirectory(Path filePath) {
      createdDirectories.add(filePath);
    }

    @Override
    public boolean exists(Path path) {
      return false;
    }

    @Override
    public boolean isDirectory(Path path) {
      return false;
    }

    @Override
    public List<Path> listFiles(Path directory) {
      return List.of();
    }

    @Override
    public List<String> readFile(Path filePath) {
      return List.of();
    }

    @Override
    public Path createTempDirectory(String prefix) {
      return Path.of(prefix);
    }

    @Override
    public void deleteDirectory(Path directory) {
      // 何もしない
    }

    @Override
    public void deleteFile(Path filePath) {
      // 何もしない
    }
  }

  private InMemoryFileRepository fileRepository;
  private TableDefinitionWriter writer;

  private BaseInfoEntity baseInfo() {
    return new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 24));
  }

  private OutputRoot outputRoot() {
    return new OutputRoot(OUT, baseInfo());
  }

  private TableEntity table(String physical) {
    return new TableEntity("testdb", "public", "受注", physical, TableType.TABLE, "");
  }

  @BeforeEach
  void setUp() {
    fileRepository = new InMemoryFileRepository();
    writer =
        new TableDefinitionWriter(
            fileRepository,
            new DefaultOutputPathResolver(),
            new PagedSectionWriter(fileRepository, new DefaultOutputPathResolver()));
  }

  @Test
  @DisplayName("writeTableDefinitionList: ヘッダー・基本情報・関連ドキュメント・テーブル一覧行が出力される")
  void testWriteTableDefinitionListWritesAllSections() {
    writer.writeTableDefinitionList(
        Tables.of(List.of(table("orders"))), outputRoot(), List.of(ListDocumentType.ER_DIAGRAM));

    Path file = OUT.resolve("testdb").resolve("tableList_testdb.md");
    assertTrue(fileRepository.files.containsKey(file));
    String content = fileRepository.files.get(file);
    assertTrue(content.contains("# テーブル一覧（DB名：testdb）"));
    assertTrue(content.contains("|PostgreSQL 16|testdb|2026/09/24|"));
    assertTrue(content.contains("## 関連ドキュメント"));
    assertTrue(content.contains("[ER図一覧](./erDiagramList_testdb.md)"));
    assertTrue(content.contains("|public|受注|orders|table|"));
    assertTrue(fileRepository.createdDirectories.contains(OUT.resolve("testdb")));
  }

  @Test
  @DisplayName("writeTableDefinitionList: 関連ドキュメントが空の場合はセクション自体が出力されない")
  void testWriteTableDefinitionListOmitsRelatedDocumentsWhenEmpty() {
    writer.writeTableDefinitionList(Tables.of(List.of(table("orders"))), outputRoot(), List.of());

    String content = fileRepository.files.get(OUT.resolve("testdb").resolve("tableList_testdb.md"));
    assertFalse(content.contains("## 関連ドキュメント"));
  }

  @Test
  @DisplayName("writeTableDefinitionList: 行数が多い場合はページ分割され、本体ページにはリンクのみ掲載される")
  void testWriteTableDefinitionListSplitsWhenExceedingMaxPageSize() {
    List<TableEntity> tables =
        IntStream.rangeClosed(1, 3001).mapToObj(i -> table("t" + i)).toList();

    writer.writeTableDefinitionList(Tables.of(tables), outputRoot(), List.of());

    Path dbDir = OUT.resolve("testdb");
    assertTrue(fileRepository.files.containsKey(dbDir.resolve("tableList_testdb_1.md")));
    assertTrue(fileRepository.files.containsKey(dbDir.resolve("tableList_testdb_2.md")));
    String main = fileRepository.files.get(dbDir.resolve("tableList_testdb.md"));
    assertFalse(main.contains("|t1|"), "本体ページには行そのものは含まれない");
    assertTrue(main.contains("./tableList_testdb_1.md"));
    assertTrue(main.contains("./tableList_testdb_2.md"));
  }

  @Test
  @DisplayName("writeTableDefinition: 解決されたパスに、カラム・インデックス・制約・外部キー・トリガー・ER図の全セクションを出力する")
  void testWriteTableDefinitionWritesAllSections() {
    TableEntity table = table("orders");
    var column =
        new ColumnEntity("public", "orders", "受注ID", "order_id", "int", "", true, true, "");
    var index =
        new IndexEntity(
            "public", "orders", "idx_orders_1", "btree", false, false, "CREATE INDEX ...", "");
    var constraint =
        new ConstraintEntity(
            "public", "orders", "pk_orders", "PRIMARY KEY", "PRIMARY KEY (order_id)", "");
    var outgoingFk =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var incomingFk =
        ForeignKeyFixtures.physical("public", "items", "fk_items_orders", "public", "orders");
    var trigger =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "public.f_orders",
            "...",
            "");

    var annotation = new TableAnnotation("受注を管理するテーブル", "個人情報を含む", Map.of("order_id", "受注の主キー"));
    var content =
        new TableDefinitionContent(
            baseInfo(),
            table,
            List.of(column),
            List.of(index),
            List.of(constraint),
            List.of(outgoingFk),
            List.of(),
            List.of(incomingFk),
            List.of(),
            List.of(),
            List.of(trigger),
            List.of(),
            annotation,
            List.of());

    writer.writeTableDefinition(
        content,
        new DiagramNeighborhood(
            java.util.stream.Stream.concat(
                    content.foreignKeys().stream(), content.incomingRelations().stream())
                .toList(),
            1,
            1),
        DiagramBoxesFixtures.none(),
        OUT);

    Path expectedFile =
        OUT.resolve("testdb").resolve("public").resolve("table").resolve("orders.md");
    assertTrue(
        fileRepository.createdDirectories.contains(
            OUT.resolve("testdb").resolve("public").resolve("table")));
    assertTrue(fileRepository.files.containsKey(expectedFile));
    String fileContent = fileRepository.files.get(expectedFile);

    assertTrue(fileContent.contains("# orders（受注）"));
    assertTrue(fileContent.contains("## カラム情報"));
    assertTrue(fileContent.contains("order_id"));
    assertTrue(fileContent.contains("## インデックス情報"));
    assertTrue(fileContent.contains("idx_orders_1"));
    assertTrue(fileContent.contains("## 制約情報"));
    assertTrue(fileContent.contains("pk_orders"));
    assertTrue(fileContent.contains("## 外部キー情報"));
    assertTrue(fileContent.contains("fk_orders_customer"));
    assertTrue(fileContent.contains("## トリガー情報"));
    assertTrue(fileContent.contains("trg_orders"));
    assertTrue(fileContent.contains("## ER図"));
    assertTrue(fileContent.contains("```mermaid"));
    assertTrue(
        fileContent.contains("public_customers ||--o{ public_orders : \"fk_orders_customer\""));
    assertTrue(fileContent.contains("public_orders ||--o{ public_items : \"fk_items_orders\""));
    assertTrue(fileContent.contains("[テーブル一覧へ](../../tableList_testdb.md)"));
    // サイドカー由来の付帯情報（テーブル説明・テーブル備考・カラム備考）がマージされる
    assertTrue(fileContent.contains("受注を管理するテーブル"));
    assertTrue(fileContent.contains("|public|受注|orders|table|個人情報を含む|"));
    assertTrue(fileContent.contains("|1|受注ID|order_id|int||○|○||受注の主キー|"));
  }

  @Test
  @DisplayName("writeTableDefinition: パーティション表の定義書にだけパーティション情報をカラム情報の後に出力する")
  void testWriteTableDefinitionPartitionSection() {
    var partitioned =
        new TableEntity("testdb", "public", "売上", "sales", TableType.TABLE, "", "RANGE (sold_on)");
    var ordinary = new TableEntity("testdb", "public", "", "customer", TableType.TABLE, "");
    var partition =
        new PartitionEntity(
            "public",
            "sales",
            "public",
            "sales_2026_01",
            "public",
            "sales",
            "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')",
            "");
    var column = EntityFixtures.column("public", "sales", "sold_on", "date", false);

    writer.writeTableDefinition(
        new TableDefinitionContent(
            baseInfo(),
            partitioned,
            List.of(column),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(partition),
            TableAnnotation.EMPTY,
            List.of()),
        new DiagramNeighborhood(List.of(), 1, 1),
        DiagramBoxesFixtures.none(),
        OUT);
    writer.writeTableDefinition(
        new TableDefinitionContent(
            baseInfo(),
            ordinary,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            TableAnnotation.EMPTY,
            List.of()),
        new DiagramNeighborhood(List.of(), 1, 1),
        DiagramBoxesFixtures.none(),
        OUT);

    String salesContent = definitionOf("sales");
    assertTrue(salesContent.contains("## パーティション情報"));
    assertTrue(salesContent.contains("|1|sales_2026_01|sales|FOR VALUES FROM"));
    assertTrue(
        salesContent.indexOf("## カラム情報") < salesContent.indexOf("## パーティション情報")
            && salesContent.indexOf("## パーティション情報") < salesContent.indexOf("## インデックス情報"));
    assertFalse(definitionOf("customer").contains("## パーティション情報"));
  }

  private String definitionOf(String table) {
    return fileRepository.files.get(
        OUT.resolve("testdb").resolve("public").resolve("table").resolve(table + ".md"));
  }
}
