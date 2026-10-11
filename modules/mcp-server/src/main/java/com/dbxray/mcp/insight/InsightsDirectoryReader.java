package com.dbxray.mcp.insight;

import com.dbxray.mcp.UserCorrectableException;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.ViewpointEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 参考情報（{@code insights}）のディレクトリを読み込むクラス<br>
 * 配置はcliの出力と同じく{@code insights/{DB名}/viewpoints.json}・{@code
 * insights/{DB名}/{スキーマ名}/functionTableUsages.json}。起動引数は{@code --snapshot}のみのため、
 * 参考情報のディレクトリは渡されたスナップショットのディレクトリの**親の兄弟**として自前で求める。 ディレクトリ・DB・ファイルが無ければ0件として扱う（cliの{@code
 * target.objects}で出力されない場合や、 観点を宣言していない場合があるため）。項目の追加に追従できるよう未知の項目は無視する
 */
public final class InsightsDirectoryReader {

  /**
   * 読み込める形式のバージョンの上限（cliの{@code ViewpointsInsight.FORMAT_VERSION}・{@code
   * FunctionTableUsagesInsight.FORMAT_VERSION}）
   */
  static final int SUPPORTED_FORMAT_VERSION = 1;

  private static final String INSIGHTS_DIRECTORY_NAME = "insights";
  private static final String VIEWPOINTS_FILE_NAME = "viewpoints.json";
  private static final String FUNCTION_TABLE_USAGES_FILE_NAME = "functionTableUsages.json";

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 観点の参考情報を読み込むメソッド
   *
   * @param snapshotDirectory {@code --snapshot}に指定されたスナップショットのディレクトリ
   * @return 読み込んだ観点のリスト（参考情報のディレクトリ・DBごとのファイルが無い場合は空）
   * @throws UserCorrectableException 対応していない形式のバージョン、JSONとして読めない内容の場合
   */
  public List<ViewpointEntry> readViewpoints(Path snapshotDirectory) {
    final Path insightsDirectory = resolveInsightsDirectory(snapshotDirectory);
    if (!Files.isDirectory(insightsDirectory)) {
      return List.of();
    }
    final List<ViewpointEntry> viewpoints = new ArrayList<>();
    for (final Path databaseDirectory : subdirectories(insightsDirectory)) {
      final Path file = databaseDirectory.resolve(VIEWPOINTS_FILE_NAME);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      viewpoints.addAll(readViewpointsFile(file, String.valueOf(databaseDirectory.getFileName())));
    }
    return viewpoints;
  }

  /**
   * 関数・プロシージャが利用しているテーブルの参考情報を読み込むメソッド<br>
   * cliで{@code --preview}を付けて出力した場合だけ、関数・プロシージャのあるスキーマごとにファイルがある
   *
   * @param snapshotDirectory {@code --snapshot}に指定されたスナップショットのディレクトリ
   * @return 読み込んだ関数ごとの参考情報（ファイルが無い場合は空）
   * @throws UserCorrectableException 対応していない形式のバージョン、JSONとして読めない内容の場合
   */
  public List<FunctionTableUsageEntry> readFunctionTableUsages(Path snapshotDirectory) {
    final Path insightsDirectory = resolveInsightsDirectory(snapshotDirectory);
    if (!Files.isDirectory(insightsDirectory)) {
      return List.of();
    }
    final List<FunctionTableUsageEntry> entries = new ArrayList<>();
    for (final Path databaseDirectory : subdirectories(insightsDirectory)) {
      for (final Path schemaDirectory : subdirectories(databaseDirectory)) {
        final Path file = schemaDirectory.resolve(FUNCTION_TABLE_USAGES_FILE_NAME);
        if (Files.isRegularFile(file)) {
          entries.addAll(
              readFunctionTableUsagesFile(file, String.valueOf(databaseDirectory.getFileName())));
        }
      }
    }
    return entries;
  }

  /**
   * スナップショットのディレクトリから、参考情報のディレクトリ（{@code snapshot}の親の兄弟）を求める<br>
   * {@code --snapshot}は常にcliの出力先配下の{@code snapshot}ディレクトリそのものを指すため、 その親を出力ベースディレクトリとみなせる
   */
  private static Path resolveInsightsDirectory(Path snapshotDirectory) {
    final Path outputBaseDir = snapshotDirectory.toAbsolutePath().normalize().getParent();
    return outputBaseDir.resolve(INSIGHTS_DIRECTORY_NAME);
  }

  private List<ViewpointEntry> readViewpointsFile(Path file, String database) {
    final ViewpointsFile parsed = parse(file, readString(file), ViewpointsFile.class);
    requireSupportedFormatVersion(parsed.formatVersion(), file);
    if (parsed.viewpoints() == null) {
      return List.of();
    }
    return parsed.viewpoints().stream().map(line -> line.toEntry(database)).toList();
  }

  /** スキーマ名はディレクトリ名（パスに使えない文字を置き換えたもの）ではなく、ファイルの中の値を使う */
  private List<FunctionTableUsageEntry> readFunctionTableUsagesFile(Path file, String database) {
    final FunctionTableUsagesFile parsed =
        parse(file, readString(file), FunctionTableUsagesFile.class);
    requireSupportedFormatVersion(parsed.formatVersion(), file);
    if (parsed.functions() == null) {
      return List.of();
    }
    return parsed.functions().stream()
        .map(line -> line.toEntry(database, parsed.schema()))
        .toList();
  }

  private static void requireSupportedFormatVersion(Integer formatVersion, Path file) {
    if (formatVersion == null) {
      throw new UserCorrectableException(
          "参考情報にformatVersionがありません。cliで出力し直してください。 [file=" + file + "]");
    }
    if (formatVersion > SUPPORTED_FORMAT_VERSION) {
      throw new UserCorrectableException(
          "このMCPサーバーが対応していない新しい形式の参考情報です（formatVersion="
              + formatVersion
              + "、対応しているのは"
              + SUPPORTED_FORMAT_VERSION
              + "まで）。MCPサーバーを参考情報を出力したcliと同じ版に更新してください。 [file="
              + file
              + "]");
    }
  }

  private <T> T parse(Path file, String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new UserCorrectableException(
          "参考情報をJSONとして読み込めません。マージの衝突等でファイルが壊れていないか確認し、必要ならcliで出力し直してください。 [file=" + file + "]", e);
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

  /** {@code viewpoints.json}のうち、読み込みに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ViewpointsFile(Integer formatVersion, List<ViewpointLine> viewpoints) {}

  /** 観点1件 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ViewpointLine(String id, String name, String description, List<MemberLine> tables) {

    ViewpointEntry toEntry(String database) {
      return new ViewpointEntry(
          database,
          id,
          name,
          description,
          tables == null
              ? List.of()
              : tables.stream().map(table -> table.toKey(database)).toList());
    }
  }

  /** 所属テーブル1件 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record MemberLine(String schema, String name) {

    ObjectKey toKey(String database) {
      return new ObjectKey(database, schema, name);
    }
  }

  /** {@code functionTableUsages.json}のうち、読み込みに使う項目 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FunctionTableUsagesFile(
      Integer formatVersion, String schema, List<FunctionTableUsageLine> functions) {}

  /** 関数・プロシージャ1つ分 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FunctionTableUsageLine(
      String name,
      String arguments,
      String definitionHash,
      String status,
      String language,
      List<UsedTableLine> tables,
      List<String> dynamicSql,
      Boolean incomplete,
      Boolean overloadsMerged) {

    FunctionTableUsageEntry toEntry(String database, String schema) {
      return new FunctionTableUsageEntry(
          new ObjectKey(database, schema, name),
          arguments,
          definitionHash,
          status,
          language,
          tables == null ? List.of() : tables.stream().map(UsedTableLine::toTable).toList(),
          dynamicSql,
          Boolean.TRUE.equals(incomplete),
          Boolean.TRUE.equals(overloadsMerged));
    }
  }

  /** 利用しているテーブル1件 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record UsedTableLine(
      String schema,
      String name,
      String schemaResolution,
      List<String> schemaCandidates,
      List<String> operations) {

    UsedTable toTable() {
      return new UsedTable(schema, name, schemaResolution, schemaCandidates, operations);
    }
  }
}
