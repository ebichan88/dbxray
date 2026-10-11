package com.dbxray.domain.service.writer.viewpoint;

import static com.dbxray.testsupport.MarkdownAssert.assertMarkdownEquals;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ViewpointWriter の観点ページ・観点一覧の出力に関するテスト */
public class ViewpointWriterTest {

  private static final Path OUT = Path.of("output");

  /** 書き込み内容をメモリ上に収集するFileRepositoryのスタブ */
  private static class InMemoryFileRepository implements FileRepository {
    private final Map<Path, String> files = new LinkedHashMap<>();

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
      // 何もしない
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
  private ViewpointWriter writer;

  @BeforeEach
  void setUp() {
    fileRepository = new InMemoryFileRepository();
    final DefaultOutputPathResolver pathResolver = new DefaultOutputPathResolver();
    writer =
        new ViewpointWriter(
            fileRepository, pathResolver, new PagedSectionWriter(fileRepository, pathResolver));
  }

  private OutputRoot outputRoot() {
    return new OutputRoot(
        OUT, new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1)));
  }

  private TableEntity table(String physical, String logical) {
    return new TableEntity("testdb", "sales", logical, physical, TableType.TABLE, "");
  }

  private final TableEntity orders = table("orders", "受注");
  private final TableEntity customer = table("customer", "顧客");
  private final TableEntity product = table("product", "");
  private final ForeignKeyEntity ordersToCustomer =
      ForeignKeyFixtures.physical("sales", "orders", "fk_orders_customer", "sales", "customer");
  private final ForeignKeyEntity ordersToProduct =
      ForeignKeyFixtures.logical("sales", "orders", "rel_orders_product", "sales", "product");

  private void write(List<Viewpoint> viewpoints, int maxNodes) {
    writer.writeViewpoints(
        Viewpoints.of(viewpoints),
        Tables.of(List.of(customer, orders, product)),
        ForeignKeys.of(List.of(ordersToCustomer, ordersToProduct)),
        DiagramBoxesFixtures.none(),
        outputRoot(),
        NodeLimit.of(maxNodes));
  }

  @Test
  @DisplayName("writeViewpoints: 観点ごとのページと観点一覧を出力する")
  void testWritesPagesAndIndex() {
    write(
        List.of(
            Viewpoint.of("order", "受注管理", "受注の流れ", List.of("sales.orders", "sales.customer")),
            Viewpoint.of("master", "", "", List.of("sales.product"))),
        80);

    Path dbDir = OUT.resolve("testdb");
    assertEquals(
        List.of(
            dbDir.resolve("viewpoint_testdb_order.md"),
            dbDir.resolve("viewpoint_testdb_master.md"),
            dbDir.resolve("viewpointList_testdb.md")),
        List.copyOf(fileRepository.files.keySet()));
  }

  @Test
  @DisplayName("writeViewpoints: 観点ページには、説明・所属テーブル同士のER図・所属テーブル・観点外のテーブルとの関連を掲載する")
  void testViewpointPage() {
    write(
        List.of(Viewpoint.of("order", "受注管理", "受注の流れ", List.of("sales.orders", "sales.customer"))),
        80);

    assertMarkdownEquals(
        """
        # 観点：受注管理（DB名：testdb）

        ## 基本情報

        | RDBMS | データベース名 | 作成日 |
        |:---|:---|:---|
        |PostgreSQL 16|testdb|2025/01/01|

        ## 説明

        受注の流れ

        ## ER図

        ```mermaid
        erDiagram
            sales_customer["customer"]
            sales_orders["orders"]
            sales_customer ||--o{ sales_orders : "fk_orders_customer"
        ```

        ## 所属テーブル

        | No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
        |:---|:---|:---|:---|:---|:---|
        | 1 | sales | customer | 顧客 | table | [■](./sales/table/customer.md) |
        | 2 | sales | orders | 受注 | table | [■](./sales/table/orders.md) |

        ## 観点外のテーブルとの関連

        | No. | 参照元 | 外部キー名 | 参照先 |
        |:---|:---|:---|:---|
        | 1 | sales.orders | rel_orders_product | sales.product |

        ___

        [観点一覧へ](./viewpointList_testdb.md) / [テーブル一覧へ](./tableList_testdb.md)
        """,
        fileRepository.files.get(OUT.resolve("testdb").resolve("viewpoint_testdb_order.md")));
  }

  @Test
  @DisplayName("writeViewpoints: ER図のテーブル数が上限を超える場合は、描画を省略して所属テーブル同士の関連を一覧で掲載する")
  void testViewpointPageExceedingMaxNodes() {
    write(List.of(Viewpoint.of("order", "", "", List.of("sales.orders", "sales.customer"))), 1);

    String page =
        fileRepository.files.get(OUT.resolve("testdb").resolve("viewpoint_testdb_order.md"));
    assertFalse(page.contains("```mermaid"));
    assertTrue(page.contains("上限（erDiagramMaxNodes = 1件）を超えるため描画を省略しました。"));
    assertTrue(page.contains("## 外部キー一覧"));
    assertTrue(page.contains("| 1 | sales.orders | fk_orders_customer | sales.customer |"));
  }

  @Test
  @DisplayName("writeViewpoints: 所属テーブルが無い観点も、その旨を示すページを出力する")
  void testViewpointPageWithoutTables() {
    write(List.of(Viewpoint.of("stock", "在庫管理", "", List.of("inventory.*"))), 80);

    String page =
        fileRepository.files.get(OUT.resolve("testdb").resolve("viewpoint_testdb_stock.md"));
    assertTrue(page.contains("所属テーブル同士の関連（外部キー・論理リレーション）はありません。"));
    assertTrue(page.contains("出力対象のテーブルのうち、この観点に所属するものはありません。"));
    assertFalse(page.contains("## 観点外のテーブルとの関連"));
  }

  @Test
  @DisplayName("writeViewpoints: 観点一覧には、観点名・説明・テーブル数・観点ページへのリンクを宣言順に掲載する")
  void testViewpointIndex() {
    write(
        List.of(
            Viewpoint.of("order", "受注管理", "受注の流れ", List.of("sales.orders", "sales.customer")),
            Viewpoint.of("master", "", "", List.of("sales.product"))),
        80);

    assertMarkdownEquals(
        """
        # 観点一覧（DB名：testdb）

        ## 基本情報

        | RDBMS | データベース名 | 作成日 |
        |:---|:---|:---|
        |PostgreSQL 16|testdb|2025/01/01|

        ## 観点情報

        | No. | 観点名 | 説明 | テーブル数 | Link |
        |:---|:---|:---|:---|:---|
        |1|受注管理|受注の流れ|2|[■](./viewpoint_testdb_order.md)|
        |2|master||1|[■](./viewpoint_testdb_master.md)|

        ___

        [テーブル一覧へ](./tableList_testdb.md)
        """,
        fileRepository.files.get(OUT.resolve("testdb").resolve("viewpointList_testdb.md")));
  }
}
