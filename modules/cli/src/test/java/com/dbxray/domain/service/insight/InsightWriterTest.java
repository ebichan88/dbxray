package com.dbxray.domain.service.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.infrastructure.snapshot.JacksonSnapshotSerializer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            .map(name2 -> new TableEntity("testdb", "public", "", name2, TableType.TABLE, ""))
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
}
