package com.dbxray.domain.service.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.SchemaResolution;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.infrastructure.snapshot.JacksonSnapshotSerializer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** InsightWriter の参考情報（insights）書き込みに関するテスト */
class InsightWriterTest {

  private static final Path OUT = Path.of("output");
  private static final Path INSIGHTS_DIR = OUT.resolve("insights").resolve("testdb");
  private static final BaseInfoEntity BASE_INFO =
      new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 25));
  private static final OutputRoot ROOT = new OutputRoot(OUT, BASE_INFO);

  /** 書き込み内容・ディレクトリ作成呼び出しをメモリ上に収集するFileRepositoryのスタブ */
  private static class InMemoryFileRepository implements FileRepository {
    private final Map<Path, String> files = new LinkedHashMap<>();
    private final List<Path> createdDirectories = new ArrayList<>();
    private final List<Path> deletedFiles = new ArrayList<>();

    /** 前回の実行が出力済みのファイル（{@link #exists}が真を返す） */
    private final Set<Path> existingFiles = new HashSet<>();

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
      return existingFiles.contains(path);
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
      deletedFiles.add(filePath);
    }
  }

  private InMemoryFileRepository fileRepository;
  private InsightWriter writer;

  @BeforeEach
  void setUp() {
    fileRepository = new InMemoryFileRepository();
    writer =
        new InsightWriter(
            fileRepository, new DefaultOutputPathResolver(), new JacksonSnapshotSerializer());
  }

  private static ViewpointContent content(
      String id, String name, String description, String... tables) {
    final List<TableEntity> members =
        List.of(tables).stream()
            .map(name2 -> new TableEntity("testdb", "public", "", name2, TableType.TABLE, "", ""))
            .toList();
    return new ViewpointContent(
        Viewpoint.of(id, name, description, List.of("public.*")),
        members,
        ForeignKeyGroup.of(List.of()),
        List.of());
  }

  @Test
  @DisplayName("writeViewpoints: 観点の形式バージョン・所属テーブルを1ファイルへ出力する")
  void testWriteViewpointsWritesOneFile() {
    writer.writeViewpoints(
        List.of(content("order", "受注管理", "受注から出荷までを扱う", "orders", "shipment")), ROOT);

    final Path file = INSIGHTS_DIR.resolve("viewpoints.json");
    assertEquals(
        "{\"formatVersion\":1,\"viewpoints\":[{\"id\":\"order\",\"name\":\"受注管理\","
            + "\"description\":\"受注から出荷までを扱う\","
            + "\"tables\":[{\"schema\":\"public\",\"name\":\"orders\"},"
            + "{\"schema\":\"public\",\"name\":\"shipment\"}]}]}\n",
        fileRepository.files.get(file));
    assertTrue(fileRepository.createdDirectories.contains(INSIGHTS_DIR));
  }

  @Test
  @DisplayName("writeViewpoints: 複数の観点を宣言順に1ファイルへまとめる")
  void testWriteViewpointsIncludesAllInOrder() {
    writer.writeViewpoints(
        List.of(content("order", "受注管理", "", "orders"), content("inventory", "在庫管理", "", "stock")),
        ROOT);

    final String written = fileRepository.files.get(INSIGHTS_DIR.resolve("viewpoints.json"));
    assertTrue(written.indexOf("\"id\":\"order\"") < written.indexOf("\"id\":\"inventory\""));
  }

  @Test
  @DisplayName("writeViewpoints: 観点が0件の場合は、ファイル自体を出力しない")
  void testWriteViewpointsEmptyWritesNothing() {
    writer.writeViewpoints(List.of(), ROOT);

    assertTrue(fileRepository.files.isEmpty());
  }

  @Test
  @DisplayName(
      "writeFunctionTableUsages: スキーマごとのファイルへ、関数の識別（名前・引数）・定義本体のSHA-256（空白だけの本体は除く）と抽出結果を、値の無い項目を除いて出力する")
  void testWriteFunctionTableUsages() {
    final TableEntity employee =
        new TableEntity("testdb", "sample", "", "employee", TableType.TABLE, "", "");
    final TableEntity otherEmployee =
        new TableEntity("testdb", "other", "", "employee", TableType.TABLE, "", "");
    writer.writeFunctionTableUsages(
        "sample",
        List.of(
            functionContent(
                "raise_salary",
                "p_id integer",
                "BEGIN NULL; END;",
                FunctionTableUsage.analyzed(
                    List.of(
                        new TableUsage(
                            List.of(employee),
                            SchemaResolution.DETERMINED,
                            Set.of(CrudOperation.UPDATE, CrudOperation.READ)),
                        new TableUsage(
                            List.of(employee, otherEmployee),
                            SchemaResolution.SEARCH_PATH,
                            Set.of(CrudOperation.DELETE))),
                    List.of(DynamicSqlKind.EXECUTE),
                    false,
                    false)),
            functionContent(
                "broken", "", "  ", FunctionTableUsage.analyzed(List.of(), List.of(), true, true)),
            functionContent("py", "", "", FunctionTableUsage.unsupportedLanguage("plpython3u"))),
        ROOT);

    assertEquals(
        "{\"formatVersion\":1,\"database\":\"testdb\",\"schema\":\"sample\",\"functions\":["
            + "{\"name\":\"raise_salary\",\"arguments\":\"p_id integer\","
            + "\"definitionHash\":\"a99f4c7a268ec9a9036157c2e7466dc7483aca400f7407ea3db9a1ed97784fe3\","
            + "\"status\":\"analyzed\","
            + "\"tables\":[{\"schema\":\"sample\",\"name\":\"employee\",\"operations\":[\"R\",\"U\"]},"
            + "{\"name\":\"employee\",\"schemaResolution\":\"search_path\",\"schemaCandidates\":[\"sample\",\"other\"],\"operations\":[\"D\"]}],"
            + "\"dynamicSql\":[\"EXECUTE\"]},"
            + "{\"name\":\"broken\",\"status\":\"analyzed\",\"incomplete\":true,\"overloadsMerged\":true},"
            + "{\"name\":\"py\",\"status\":\"unsupported_language\",\"language\":\"plpython3u\"}]}\n",
        fileRepository.files.get(
            INSIGHTS_DIR.resolve("sample").resolve("functionTableUsages.json")));
    assertTrue(fileRepository.createdDirectories.contains(INSIGHTS_DIR.resolve("sample")));
  }

  @Test
  @DisplayName("writeFunctionTableUsages: DB名はファイルの中に実際の名前で持つ（ディレクトリ名はパスに使えない文字を置き換えたもの）")
  void testWriteFunctionTableUsagesKeepsDatabaseName() {
    writer.writeFunctionTableUsages(
        "sample",
        List.of(functionContent("py", "", "", FunctionTableUsage.unsupportedLanguage("c"))),
        new OutputRoot(
            OUT, new BaseInfoEntity("my:db", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 25))));

    assertEquals(
        "{\"formatVersion\":1,\"database\":\"my:db\",\"schema\":\"sample\",\"functions\":["
            + "{\"name\":\"py\",\"status\":\"unsupported_language\",\"language\":\"c\"}]}\n",
        fileRepository.files.get(
            OUT.resolve("insights")
                .resolve("my~3Adb")
                .resolve("sample")
                .resolve("functionTableUsages.json")));
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 同じ定義本体が離れて現れても、関数ごとに自分の定義本体のSHA-256を出力する")
  void testWriteFunctionTableUsagesHashesEachDefinition() {
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(List.of(), List.of(), false, false);
    writer.writeFunctionTableUsages(
        "sample",
        List.of(
            functionContent("a", "", "A", usage),
            functionContent("a2", "", "A", usage),
            functionContent("b", "", "B", usage),
            functionContent("a3", "", "A", usage)),
        ROOT);

    final String hashA = "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd";
    final String hashB = "df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c";
    assertEquals(
        "{\"formatVersion\":1,\"database\":\"testdb\",\"schema\":\"sample\",\"functions\":["
            + "{\"name\":\"a\",\"definitionHash\":\""
            + hashA
            + "\",\"status\":\"analyzed\"},"
            + "{\"name\":\"a2\",\"definitionHash\":\""
            + hashA
            + "\",\"status\":\"analyzed\"},"
            + "{\"name\":\"b\",\"definitionHash\":\""
            + hashB
            + "\",\"status\":\"analyzed\"},"
            + "{\"name\":\"a3\",\"definitionHash\":\""
            + hashA
            + "\",\"status\":\"analyzed\"}]}\n",
        fileRepository.files.get(
            INSIGHTS_DIR.resolve("sample").resolve("functionTableUsages.json")));
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 抽出を行わない実行では、ファイルを出力せず、以前のプレビューの実行が出力したファイルを削除する")
  void testWriteFunctionTableUsagesNotAnalyzedDeletesPreviousFile() {
    final Path file = INSIGHTS_DIR.resolve("sample").resolve("functionTableUsages.json");
    fileRepository.existingFiles.add(file);

    writer.writeFunctionTableUsages(
        "sample", List.of(functionContent("f", "", "", FunctionTableUsage.notAnalyzed())), ROOT);

    assertTrue(fileRepository.files.isEmpty());
    assertEquals(List.of(file), fileRepository.deletedFiles);
    assertTrue(fileRepository.createdDirectories.isEmpty());
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 抽出を行わない実行で、前回のファイルが無ければ何も削除しない")
  void testWriteFunctionTableUsagesNotAnalyzedWithoutPreviousFile() {
    writer.writeFunctionTableUsages(
        "sample", List.of(functionContent("f", "", "", FunctionTableUsage.notAnalyzed())), ROOT);

    assertTrue(fileRepository.deletedFiles.isEmpty());
    assertTrue(fileRepository.files.isEmpty());
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 抽出を行ったものと行っていないものが混ざる場合は、抽出を行ったものだけを出力する")
  void testWriteFunctionTableUsagesWritesOnlyAnalyzed() {
    writer.writeFunctionTableUsages(
        "sample",
        List.of(
            functionContent("skipped", "", "", FunctionTableUsage.notAnalyzed()),
            functionContent("py", "", "", FunctionTableUsage.unsupportedLanguage("c"))),
        ROOT);

    assertEquals(
        "{\"formatVersion\":1,\"database\":\"testdb\",\"schema\":\"sample\",\"functions\":["
            + "{\"name\":\"py\",\"status\":\"unsupported_language\",\"language\":\"c\"}]}\n",
        fileRepository.files.get(
            INSIGHTS_DIR.resolve("sample").resolve("functionTableUsages.json")));
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 状態・動的SQLの種類は、定数の名前ではなく固定の値で出力する（MCPサーバーが読む値）")
  void testWriteFunctionTableUsagesPinsStatusAndDynamicSqlValues() {
    writer.writeFunctionTableUsages(
        "sample",
        List.of(
            functionContent(
                "a", "", "", FunctionTableUsage.notAnalyzable(TableUsageStatus.NO_DEFINITION)),
            functionContent(
                "b", "", "", FunctionTableUsage.notAnalyzable(TableUsageStatus.WRAPPED)),
            functionContent(
                "c",
                "",
                "",
                FunctionTableUsage.notAnalyzable(TableUsageStatus.SUBPROGRAM_NOT_FOUND)),
            functionContent(
                "d",
                "",
                "",
                FunctionTableUsage.analyzed(
                    List.of(), List.of(DynamicSqlKind.values()), false, false))),
        ROOT);

    assertEquals(
        "{\"formatVersion\":1,\"database\":\"testdb\",\"schema\":\"sample\",\"functions\":["
            + "{\"name\":\"a\",\"status\":\"no_definition\"},"
            + "{\"name\":\"b\",\"status\":\"wrapped\"},"
            + "{\"name\":\"c\",\"status\":\"subprogram_not_found\"},"
            + "{\"name\":\"d\",\"status\":\"analyzed\","
            + "\"dynamicSql\":[\"EXECUTE\",\"EXECUTE IMMEDIATE\",\"DBMS_SQL\","
            + "\"OPEN FOR\",\"dblink\"]}]}\n",
        fileRepository.files.get(
            INSIGHTS_DIR.resolve("sample").resolve("functionTableUsages.json")));
  }

  @Test
  @DisplayName("writeFunctionTableUsages: 抽出を行った場合は、ファイルを削除しない")
  void testWriteFunctionTableUsagesAnalyzedKeepsFile() {
    writer.writeFunctionTableUsages(
        "sample",
        List.of(functionContent("f", "", "", FunctionTableUsage.unsupportedLanguage("c"))),
        ROOT);

    assertTrue(fileRepository.deletedFiles.isEmpty());
  }

  private static FunctionDefinitionContent functionContent(
      String name, String arguments, String definition, FunctionTableUsage usage) {
    return new FunctionDefinitionContent(
        new FunctionEntity(
            "testdb", "sample", name, 1, 1, "FUNCTION", arguments, "", "sql", definition),
        usage);
  }
}
