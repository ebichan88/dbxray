package com.dbxray.mcp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.UserCorrectableException;
import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.NameFilter;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.RelationEntry;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SchemaSummary;
import com.dbxray.mcp.catalog.SearchScope;
import com.dbxray.mcp.catalog.SequenceEntry;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TriggerEntry;
import com.dbxray.mcp.catalog.TypeEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SnapshotDirectoryReader}のテスト */
class SnapshotDirectoryReaderTest {

  private static final String DATABASE_JSON =
      "{\"formatVersion\":1,\"name\":\"testdb\",\"dbms\":\"PostgreSQL\"}";

  @TempDir Path snapshot;

  private final SnapshotDirectoryReader reader = new SnapshotDirectoryReader();

  @Test
  @DisplayName("DB・スキーマごとのtables.jsonlを読み込み、1行をそのまま保持する")
  void readsTablesOfAllDatabasesAndSchemas() throws IOException {
    final String order =
        "{\"schema\":\"sales\",\"name\":\"orders\",\"type\":\"table\",\"logicalName\":\"受注\","
            + "\"columns\":[{\"name\":\"customer_id\",\"type\":\"integer\",\"remarks\":\"顧客\"}],"
            + "\"foreignKeys\":[{\"name\":\"fk\",\"columns\":[\"customer_id\"],\"referenceSchema\":\"sales\","
            + "\"referenceTable\":\"customer\",\"referenceColumns\":[\"id\"],\"cardinality\":\"ONE_TO_MANY\"}]}";
    write("testdb/database.json", DATABASE_JSON);
    write("testdb/sales/tables.jsonl", order + "\n\n");
    write(
        "testdb/hr/tables.jsonl", "{\"schema\":\"hr\",\"name\":\"employee\",\"type\":\"table\"}\n");
    write("otherdb/database.json", "{\"formatVersion\":1,\"name\":\"otherdb\"}");
    write(
        "otherdb/public/tables.jsonl",
        "{\"schema\":\"public\",\"name\":\"item\",\"type\":\"view\"}\n");

    final SchemaCatalog catalog = reader.read(snapshot);

    assertEquals(
        List.of(
            new ObjectKey("otherdb", "public", "item"),
            new ObjectKey("testdb", "hr", "employee"),
            new ObjectKey("testdb", "sales", "orders")),
        catalog.tables().all().stream().map(TableEntry::key).toList());
    final TableEntry orders = catalog.tables().all().get(2);
    assertEquals("受注", orders.logicalName());
    assertEquals("顧客", orders.columns().get(0).remarks());
    assertEquals(
        new RelationEntry(
            "fk", List.of("customer_id"), "sales", "customer", List.of("id"), "ONE_TO_MANY"),
        orders.foreignKeys().get(0));
    assertEquals(order, orders.json());
  }

  @Test
  @DisplayName("database.jsonのメジャーバージョンを読み込み、項目の無い古いスナップショットでは不明（null）とする")
  void readsMajorVersion() throws IOException {
    write(
        "newdb/database.json",
        "{\"formatVersion\":1,\"name\":\"newdb\",\"dbms\":\"Oracle\",\"majorVersion\":23}");
    write("newdb/s/tables.jsonl", "{\"schema\":\"s\",\"name\":\"t\"}\n");
    write("olddb/database.json", DATABASE_JSON.replace("testdb", "olddb"));
    write("olddb/s/tables.jsonl", "{\"schema\":\"s\",\"name\":\"t\"}\n");

    final List<SchemaSummary> schemas = reader.read(snapshot).schemas();

    assertEquals(23, schemas.get(0).majorVersion());
    assertNull(schemas.get(1).majorVersion());
  }

  @Test
  @DisplayName("ビューのreferencedTablesを、同じDBのテーブルのキーとして読み込む")
  void readsReferencedTablesOfView() throws IOException {
    write("testdb/database.json", DATABASE_JSON);
    write(
        "testdb/sales/tables.jsonl",
        "{\"schema\":\"sales\",\"name\":\"v\",\"type\":\"view\",\"referencedTables\":["
            + "{\"schema\":\"sales\",\"name\":\"orders\",\"type\":\"table\"},"
            + "{\"schema\":\"archive\",\"name\":\"orders\",\"type\":\"table\"}]}\n");

    assertEquals(
        List.of(
            new ObjectKey("testdb", "sales", "orders"),
            new ObjectKey("testdb", "archive", "orders")),
        reader.read(snapshot).tables().all().get(0).referencedTables());
  }

  @Test
  @DisplayName("未知の項目は無視する（cliが項目を追加しても読み込める）")
  void ignoresUnknownFields() throws IOException {
    write(
        "testdb/database.json", "{\"formatVersion\":1,\"name\":\"testdb\",\"generatedBy\":\"x\"}");
    write(
        "testdb/sample/tables.jsonl",
        "{\"schema\":\"sample\",\"name\":\"t\",\"newField\":{\"a\":1},"
            + "\"columns\":[{\"name\":\"c\",\"newColumnField\":true}]}\n");

    final SchemaCatalog catalog = reader.read(snapshot);

    assertEquals("c", catalog.tables().all().get(0).columns().get(0).name());
  }

  @Test
  @DisplayName("種類ごとのファイルが無い場合は0件とし、database.jsonの無いディレクトリは読み飛ばす")
  void skipsDirectoriesWithoutSnapshot() throws IOException {
    write("testdb/database.json", DATABASE_JSON);
    write("testdb/sample/functions.jsonl", "{\"schema\":\"sample\",\"name\":\"f\"}\n");
    write("notadb/sample/tables.jsonl", "{\"schema\":\"sample\",\"name\":\"t\"}\n");

    final SchemaCatalog catalog = reader.read(snapshot);

    assertTrue(catalog.tables().all().isEmpty());
    assertEquals(
        List.of(new ObjectKey("testdb", "sample", "f")),
        catalog.functions().list(SearchScope.ALL, NameFilter.ALL).stream()
            .flatMap(function -> function.overloads().stream())
            .map(FunctionEntry::key)
            .toList());
  }

  @Test
  @DisplayName("関数・シーケンス・ユーザー定義型・トリガーを読み込み、1行をそのまま保持する（関数は定義本体のハッシュも求める）")
  void readsOtherObjects() throws IOException {
    final String function =
        "{\"schema\":\"sample\",\"name\":\"calc\",\"kind\":\"FUNCTION\",\"arguments\":\"p numeric\","
            + "\"result\":\"numeric\",\"language\":\"sql\",\"definition\":\"select 1\"}";
    final String sequence =
        "{\"schema\":\"sample\",\"name\":\"orders_id_seq\",\"incrementBy\":\"1\",\"ownedBy\":\"orders.id\"}";
    final String type =
        "{\"schema\":\"sample\",\"name\":\"status\",\"category\":\"ENUM\",\"definition\":\"A, B\"}";
    write("testdb/database.json", DATABASE_JSON);
    write("testdb/sample/functions.jsonl", function + "\n");
    write("testdb/sample/sequences.jsonl", sequence + "\n");
    write("testdb/sample/types.jsonl", type + "\n");
    write(
        "testdb/sample/tables.jsonl",
        "{\"schema\":\"sample\",\"name\":\"orders\",\"triggers\":[{\"name\":\"trg\",\"timing\":\"AFTER\","
            + "\"events\":[\"INSERT\",\"UPDATE\"],\"orientation\":\"ROW\",\"function\":\"sample.calc\"}]}\n");

    final SchemaCatalog catalog = reader.read(snapshot);

    final FunctionEntry calc =
        catalog.functions().list(SearchScope.ALL, NameFilter.ALL).get(0).overloads().get(0);
    assertEquals(
        List.of("FUNCTION", "p numeric", "numeric", "sql"),
        List.of(calc.kind(), calc.arguments(), calc.result(), calc.language()));
    assertEquals(function, calc.json());
    assertEquals(
        "822ae07d4783158bc1912bb623e5107cc9002d519e1143a9c200ed6ee18b6d0f",
        calc.definitionHash(),
        "定義本体（select 1）のSHA-256");
    final SequenceEntry ordersId = catalog.sequences().list(SearchScope.ALL, NameFilter.ALL).get(0);
    assertEquals("orders.id", ordersId.ownedBy());
    assertEquals(sequence, ordersId.json());
    final TypeEntry status = catalog.types().list(SearchScope.ALL, NameFilter.ALL).get(0);
    assertEquals("ENUM", status.category());
    assertEquals(type, status.json());
    assertEquals(
        new TriggerEntry("trg", "AFTER", List.of("INSERT", "UPDATE"), "ROW", "sample.calc"),
        catalog.tables().all().get(0).triggers().get(0));
  }

  @Test
  @DisplayName("ディレクトリが無い場合は、snapshotディレクトリを指定するよう伝える")
  void failsWhenDirectoryIsMissing() {
    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot.resolve("none")));

    assertTrue(e.getMessage().contains("snapshotディレクトリを指定してください"), e.getMessage());
  }

  @Test
  @DisplayName("database.jsonが1つも無い場合（output.pathそのものを指定した等）は、snapshotディレクトリを指定するよう伝える")
  void failsWhenNoDatabaseFound() throws IOException {
    write("testdb/sample/table/employee.md", "# employee");

    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));

    assertTrue(e.getMessage().contains("database.jsonがありません"), e.getMessage());
  }

  @Test
  @DisplayName("対応していない新しい形式のバージョンの場合は、MCPサーバーの更新を促す")
  void failsOnNewerFormatVersion() throws IOException {
    write("testdb/database.json", "{\"formatVersion\":2,\"name\":\"testdb\"}");

    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));

    assertTrue(e.getMessage().contains("formatVersion=2"), e.getMessage());
  }

  @Test
  @DisplayName("database.jsonにformatVersion・nameが無い場合は失敗にする")
  void failsWhenDatabaseInfoIsIncomplete() throws IOException {
    write("testdb/database.json", "{\"name\":\"testdb\"}");

    assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));
  }

  @Test
  @DisplayName("JSONとして読めない行がある場合は、ファイルと行番号を示して失敗にする")
  void failsOnBrokenLineWithLineNumber() throws IOException {
    write("testdb/database.json", DATABASE_JSON);
    final Path tables =
        write(
            "testdb/sample/tables.jsonl",
            "{\"schema\":\"sample\",\"name\":\"a\"}\n<<<<<<< HEAD\n{\"schema\":\"sample\",\"name\":\"b\"}\n");

    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));

    assertTrue(e.getMessage().contains("file=" + tables + ", line=2"), e.getMessage());
  }

  @Test
  @DisplayName("schema・nameの無い行がある場合は、ファイルと行番号を示して失敗にする")
  void failsOnLineWithoutName() throws IOException {
    write("testdb/database.json", DATABASE_JSON);
    final Path tables = write("testdb/sample/tables.jsonl", "{\"schema\":\"sample\"}\n");

    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));

    assertTrue(e.getMessage().contains("file=" + tables + ", line=1"), e.getMessage());
  }

  @Test
  @DisplayName("関数等のファイルでも、JSONとして読めない行はファイルと行番号を示して失敗にする")
  void failsOnBrokenLineOfOtherObjects() throws IOException {
    write("testdb/database.json", DATABASE_JSON);
    final Path sequences =
        write("testdb/sample/sequences.jsonl", "{\"schema\":\"sample\",\"name\":\"s\"}\n{\n");

    final UserCorrectableException e =
        assertThrows(UserCorrectableException.class, () -> reader.read(snapshot));

    assertTrue(e.getMessage().contains("file=" + sequences + ", line=2"), e.getMessage());
  }

  private Path write(String relativePath, String content) throws IOException {
    final Path file = snapshot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }
}
