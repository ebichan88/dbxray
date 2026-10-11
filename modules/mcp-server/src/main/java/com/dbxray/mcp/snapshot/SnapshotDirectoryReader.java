package com.dbxray.mcp.snapshot;

import com.dbxray.mcp.UserCorrectableException;
import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.DatabaseEntry;
import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.RelationEntry;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SequenceEntry;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TriggerEntry;
import com.dbxray.mcp.catalog.TypeEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * スナップショットのディレクトリを読み込み、{@link SchemaCatalog}を組み立てるクラス<br>
 * 配置はcliの出力と同じく{@code {DB名}/database.json}・{@code {DB名}/{スキーマ名}/tables.jsonl}等。
 * 種類ごとのファイルは出力対象から外せる（cliの{@code target.objects}）ため、無いファイルは0件として扱う。
 * 項目の追加に追従できるよう未知の項目は無視し、形式を互換性なく変えた場合は{@code formatVersion}で検知する
 */
public final class SnapshotDirectoryReader {

  /** 読み込める形式のバージョンの上限（cliの{@code DatabaseSnapshot.FORMAT_VERSION}） */
  static final int SUPPORTED_FORMAT_VERSION = 1;

  private static final String DATABASE_FILE_NAME = "database.json";
  private static final String TABLE_FILE_NAME = "tables.jsonl";
  private static final String FUNCTION_FILE_NAME = "functions.jsonl";
  private static final String SEQUENCE_FILE_NAME = "sequences.jsonl";
  private static final String TYPE_FILE_NAME = "types.jsonl";

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * スナップショットのディレクトリを読み込むメソッド
   *
   * @param snapshotDirectory cliの{@code output.path}配下の{@code snapshot}ディレクトリ
   * @throws UserCorrectableException ディレクトリが無い・スナップショットが1つも無い・対応していない形式のバージョン・JSONとして読めない行がある場合
   */
  public SchemaCatalog read(Path snapshotDirectory) {
    if (!Files.isDirectory(snapshotDirectory)) {
      throw new UserCorrectableException(
          "スナップショットのディレクトリが見つかりません。--snapshotにはcliの出力先（output.path）配下のsnapshotディレクトリを指定してください。 [path="
              + snapshotDirectory
              + "]");
    }
    final List<Path> databaseDirectories =
        subdirectories(snapshotDirectory).stream()
            .filter(directory -> Files.isRegularFile(directory.resolve(DATABASE_FILE_NAME)))
            .toList();
    if (databaseDirectories.isEmpty()) {
      throw new UserCorrectableException(
          "スナップショットが見つかりません（{DB名}/"
              + DATABASE_FILE_NAME
              + "がありません）。--snapshotにはcliの出力先（output.path）配下のsnapshotディレクトリを指定してください。 [path="
              + snapshotDirectory
              + "]");
    }
    final List<DatabaseEntry> databases = new ArrayList<>();
    final List<TableEntry> tables = new ArrayList<>();
    final List<FunctionEntry> functions = new ArrayList<>();
    final List<SequenceEntry> sequences = new ArrayList<>();
    final List<TypeEntry> types = new ArrayList<>();
    for (final Path databaseDirectory : databaseDirectories) {
      final DatabaseLine databaseLine = readDatabase(databaseDirectory.resolve(DATABASE_FILE_NAME));
      databases.add(
          new DatabaseEntry(databaseLine.name(), databaseLine.dbms(), databaseLine.majorVersion()));
      final String database = databaseLine.name();
      for (final Path schemaDirectory : subdirectories(databaseDirectory)) {
        tables.addAll(
            readObjects(
                schemaDirectory.resolve(TABLE_FILE_NAME),
                TableLine.class,
                (line, json) -> line.toEntry(database, json)));
        functions.addAll(
            readObjects(
                schemaDirectory.resolve(FUNCTION_FILE_NAME),
                FunctionLine.class,
                (line, json) -> line.toEntry(database, json)));
        sequences.addAll(
            readObjects(
                schemaDirectory.resolve(SEQUENCE_FILE_NAME),
                SequenceLine.class,
                (line, json) -> line.toEntry(database, json)));
        types.addAll(
            readObjects(
                schemaDirectory.resolve(TYPE_FILE_NAME),
                TypeLine.class,
                (line, json) -> line.toEntry(database, json)));
      }
    }
    return SchemaCatalog.of(databases, tables, functions, sequences, types);
  }

  private DatabaseLine readDatabase(Path file) {
    final DatabaseLine database = parse(file, 1, readString(file), DatabaseLine.class);
    if (database.formatVersion() == null || database.name() == null) {
      throw new UserCorrectableException(
          "スナップショットのDB情報にformatVersion・nameがありません。cliでスナップショットを出力し直してください。 [file=" + file + "]");
    }
    if (database.formatVersion() > SUPPORTED_FORMAT_VERSION) {
      throw new UserCorrectableException(
          "このMCPサーバーが対応していない新しい形式のスナップショットです（formatVersion="
              + database.formatVersion()
              + "、対応しているのは"
              + SUPPORTED_FORMAT_VERSION
              + "まで）。MCPサーバーをスナップショットを出力したcliと同じ版に更新してください。 [file="
              + file
              + "]");
    }
    return database;
  }

  /**
   * JSON Linesのファイルを1行1オブジェクトとして読み込む
   *
   * @param toEntry 読み込んだ行と、行の文字列そのものから、カタログに渡す値を作る処理
   * @return ファイルの行の順。ファイルが無い場合は空
   */
  private <L extends NamedLine, E> List<E> readObjects(
      Path file, Class<L> lineType, BiFunction<L, String, E> toEntry) {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    final List<String> lines = readLines(file);
    final List<E> entries = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      final L parsed = parse(file, i + 1, line, lineType);
      if (parsed.schema() == null || parsed.name() == null) {
        throw new UserCorrectableException(
            "スナップショットの行にschema・nameがありません。cliで出力し直してください。 [file="
                + file
                + ", line="
                + (i + 1)
                + "]");
      }
      entries.add(toEntry.apply(parsed, line));
    }
    return entries;
  }

  /** JSONを読み込む。読めない場合は、利用者が該当箇所を探せるようファイル名と行番号を示す */
  private <T> T parse(Path file, int lineNumber, String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new UserCorrectableException(
          "スナップショットをJSONとして読み込めません。マージの衝突等でファイルが壊れていないか確認し、必要ならcliで出力し直してください。 [file="
              + file
              + ", line="
              + lineNumber
              + "]",
          e);
    }
  }

  private static List<Path> subdirectories(Path directory) {
    try (Stream<Path> children = Files.list(directory)) {
      return children.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code database.json}のうち、読み込みに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DatabaseLine(Integer formatVersion, String name, String dbms, Integer majorVersion) {}

  /** スキーマに属するオブジェクトの1行。schema・nameは必須 */
  interface NamedLine {
    String schema();

    String name();
  }

  /** {@code tables.jsonl}の1行のうち、検索・関連のたどり・逆引きに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TableLine(
      String schema,
      String name,
      String logicalName,
      String type,
      String description,
      String remarks,
      List<ColumnLine> columns,
      List<RelationLine> foreignKeys,
      List<RelationLine> logicalRelations,
      List<TriggerLine> triggers,
      List<ViewReferenceLine> referencedTables)
      implements NamedLine {

    TableEntry toEntry(String database, String json) {
      return new TableEntry(
          new ObjectKey(database, schema, name),
          logicalName,
          type,
          description,
          remarks,
          columns == null ? null : columns.stream().map(ColumnLine::toEntry).toList(),
          relations(foreignKeys),
          relations(logicalRelations),
          triggers == null ? null : triggers.stream().map(TriggerLine::toEntry).toList(),
          referencedTables == null
              ? null
              : referencedTables.stream().map(line -> line.toKey(database)).toList(),
          json);
    }

    private static List<RelationEntry> relations(List<RelationLine> lines) {
      return lines == null ? null : lines.stream().map(RelationLine::toEntry).toList();
    }
  }

  /** ビューが参照するテーブルのうち、参照しているビューの逆引きに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ViewReferenceLine(String schema, String name) {

    ObjectKey toKey(String database) {
      return new ObjectKey(database, schema, name);
    }
  }

  /** カラムのうち、検索・逆引きに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ColumnLine(
      String name,
      String logicalName,
      String type,
      boolean primaryKey,
      boolean notNull,
      String defaultValue,
      String remarks) {

    ColumnEntry toEntry() {
      return new ColumnEntry(name, logicalName, type, primaryKey, notNull, defaultValue, remarks);
    }
  }

  /** 外部キー・論理リレーション */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RelationLine(
      String name,
      List<String> columns,
      String referenceSchema,
      String referenceTable,
      List<String> referenceColumns,
      String cardinality) {

    RelationEntry toEntry() {
      return new RelationEntry(
          name, columns, referenceSchema, referenceTable, referenceColumns, cardinality);
    }
  }

  /** トリガーのうち、一覧・関数からの逆引きに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TriggerLine(
      String name, String timing, List<String> events, String orientation, String function) {

    TriggerEntry toEntry() {
      return new TriggerEntry(name, timing, events, orientation, function);
    }
  }

  /** {@code functions.jsonl}の1行 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FunctionLine(
      String schema,
      String name,
      String kind,
      String arguments,
      String result,
      String language,
      String definition)
      implements NamedLine {

    FunctionEntry toEntry(String database, String json) {
      return new FunctionEntry(
          new ObjectKey(database, schema, name),
          kind,
          arguments,
          result,
          language,
          json,
          definitionHash(definition));
    }
  }

  /** 定義本体（UTF-8）のSHA-256を16進数の小文字で求める（cliの参考情報の{@code definitionHash}と同じ求め方）。定義本体が無い場合は空文字 */
  private static String definitionHash(String definition) {
    if (definition == null) {
      return "";
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(definition.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256はJava SEの実装が必ず備えるアルゴリズムのため、起こらない
      throw new IllegalStateException(e);
    }
  }

  /** {@code sequences.jsonl}の1行のうち、一覧・逆引きに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SequenceLine(String schema, String name, String ownedBy) implements NamedLine {

    SequenceEntry toEntry(String database, String json) {
      return new SequenceEntry(new ObjectKey(database, schema, name), ownedBy, json);
    }
  }

  /** {@code types.jsonl}の1行のうち、一覧に使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TypeLine(String schema, String name, String category) implements NamedLine {

    TypeEntry toEntry(String database, String json) {
      return new TypeEntry(new ObjectKey(database, schema, name), category, json);
    }
  }
}
