package com.dbxray.domain.service.writer.objectlist;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
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

/** ObjectListWriter のトリガー・関数/プロシージャ・シーケンス・型一覧および個別定義書き込みに関するテスト */
public class ObjectListWriterTest {

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
  private ObjectListWriter writer;

  private BaseInfoEntity baseInfo() {
    return new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.of(2026, 9, 24));
  }

  private OutputRoot outputRoot() {
    return new OutputRoot(OUT, baseInfo());
  }

  @BeforeEach
  void setUp() {
    fileRepository = new InMemoryFileRepository();
    writer =
        new ObjectListWriter(
            fileRepository,
            new DefaultOutputPathResolver(),
            new PagedSectionWriter(fileRepository, new DefaultOutputPathResolver()));
  }

  @Test
  @DisplayName("writeTriggerList: トリガーが存在する場合、一覧ファイルにヘッダー・基本情報・行・戻る導線が出力される")
  void testWriteTriggerListWritesFile() {
    var trigger =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "f_orders",
            "",
            "");
    writer.writeTriggerList(Triggers.of(List.of(trigger)), outputRoot());

    Path file = OUT.resolve("testdb").resolve("triggerList_testdb.md");
    assertTrue(fileRepository.files.containsKey(file));
    String content = fileRepository.files.get(file);
    assertTrue(content.contains("# トリガー一覧（DB名：testdb）"));
    assertTrue(content.contains("|PostgreSQL 16|testdb|2026/09/24|"));
    assertTrue(content.contains("trg_orders"));
    assertTrue(content.contains("[テーブル一覧へ](./tableList_testdb.md)"));
  }

  @Test
  @DisplayName("writeFunctionList: 関数・プロシージャの一覧ファイルが出力される")
  void testWriteFunctionListWritesFile() {
    var function =
        new FunctionEntity(
            "testdb", "public", "calc_total", 1, 1, "FUNCTION", "()", "int", "plpgsql", "");
    writer.writeFunctionList(Functions.of(List.of(function)), outputRoot());

    Path file = OUT.resolve("testdb").resolve("functionList_testdb.md");
    assertTrue(fileRepository.files.containsKey(file));
    assertTrue(fileRepository.files.get(file).contains("calc_total"));
  }

  @Test
  @DisplayName("writeFunctionList/writeTypeList: 引数・戻り値・型定義に含まれる|は表を崩さないようエスケープする")
  void testWriteListsEscapePipe() {
    var function =
        new FunctionEntity(
            "testdb",
            "public",
            "concat_code",
            1,
            1,
            "FUNCTION",
            "sep text DEFAULT '|'::text",
            "TABLE(code text, label text)",
            "sql",
            "");
    var type = new TypeEntity("testdb", "public", "delimiter", "ENUM", "|, ,, ;");
    writer.writeFunctionList(Functions.of(List.of(function)), outputRoot());
    writer.writeTypeList(Types.of(List.of(type)), outputRoot());

    Path dbDir = OUT.resolve("testdb");
    assertTrue(
        fileRepository
            .files
            .get(dbDir.resolve("functionList_testdb.md"))
            .contains("|sep text DEFAULT '\\|'::text|TABLE(code text, label text)|"));
    assertTrue(
        fileRepository.files.get(dbDir.resolve("typeList_testdb.md")).contains("|ENUM|\\|, ,, ;|"));
  }

  @Test
  @DisplayName("writeFunctionDefinition: スキーマ配下のfunctionディレクトリに個別ファイルを出力する")
  void testWriteFunctionDefinitionWritesIndividualFile() {
    var function =
        new FunctionEntity("testdb", "public", "calc_total", 1, 1, "", "", "", "", "SELECT 1;");
    writer.writeFunctionDefinition(
        FunctionDefinitionContent.withoutTableUsage(function), outputRoot());

    Path expectedDir = OUT.resolve("testdb").resolve("public").resolve("function");
    Path expectedFile = expectedDir.resolve("calc_total.md");
    assertTrue(fileRepository.createdDirectories.contains(expectedDir));
    assertTrue(fileRepository.files.containsKey(expectedFile));
    String content = fileRepository.files.get(expectedFile);
    assertTrue(content.contains("# calc_total"));
    assertTrue(content.contains("SELECT 1;"));
    assertTrue(content.contains("[関数・プロシージャ一覧へ](../../functionList_testdb.md)"));
  }

  @Test
  @DisplayName("writeSequenceDefinition: スキーマ配下のsequenceディレクトリに個別ファイルを出力する")
  void testWriteSequenceDefinitionWritesIndividualFile() {
    var sequence =
        new SequenceEntity(
            "testdb", "public", "seq_orders", "10", "1", "999999999", "20", "1", true, "orders.id");
    writer.writeSequenceDefinition(sequence, outputRoot());

    Path expectedFile =
        OUT.resolve("testdb").resolve("public").resolve("sequence").resolve("seq_orders.md");
    assertTrue(fileRepository.files.containsKey(expectedFile));
    String content = fileRepository.files.get(expectedFile);
    assertTrue(content.contains("# seq_orders"));
    assertTrue(content.contains("|10|1|999999999|20|1|○|orders.id|"));
    assertTrue(content.contains("[シーケンス一覧へ](../../sequenceList_testdb.md)"));
  }

  @Test
  @DisplayName("writeTypeDefinition: スキーマ配下のtypeディレクトリに個別ファイルを出力する")
  void testWriteTypeDefinitionWritesIndividualFile() {
    var type = new TypeEntity("testdb", "public", "order_status", "enum", "PENDING,SHIPPED,DONE");
    writer.writeTypeDefinition(type, outputRoot());

    Path expectedFile =
        OUT.resolve("testdb").resolve("public").resolve("type").resolve("order_status.md");
    assertTrue(fileRepository.files.containsKey(expectedFile));
    String content = fileRepository.files.get(expectedFile);
    assertTrue(content.contains("# order_status"));
    assertTrue(content.contains("|enum|PENDING,SHIPPED,DONE|"));
    assertTrue(content.contains("[ユーザー定義型一覧へ](../../typeList_testdb.md)"));
  }

  @Test
  @DisplayName("writeTriggerList: 行数が多い場合はページ分割され、本体ページにはリンクのみ掲載される")
  void testWriteTriggerListSplitsWhenExceedingMaxPageSize() {
    List<TriggerEntity> triggers =
        IntStream.rangeClosed(1, 3001)
            .mapToObj(
                i -> new TriggerEntity("public", "t" + i, "trg" + i, "", List.of(), "", "", "", ""))
            .toList();
    writer.writeTriggerList(Triggers.of(triggers), outputRoot());

    Path dbDir = OUT.resolve("testdb");
    assertTrue(fileRepository.files.containsKey(dbDir.resolve("triggerList_testdb_1.md")));
    assertTrue(fileRepository.files.containsKey(dbDir.resolve("triggerList_testdb_2.md")));
    String main = fileRepository.files.get(dbDir.resolve("triggerList_testdb.md"));
    assertFalse(main.contains("trg1|"), "本体ページには行そのものは含まれない");
    assertTrue(main.contains("./triggerList_testdb_1.md"));
    assertTrue(main.contains("./triggerList_testdb_2.md"));
  }
}
