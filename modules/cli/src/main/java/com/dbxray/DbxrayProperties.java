package com.dbxray;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.PreviewFeature;
import com.dbxray.application.TargetSelection;
import com.dbxray.config.ConfigFile;
import com.dbxray.config.InvalidConfigurationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 設定ファイル（{@code conf/config.yml}）のうち、DB接続情報（{@code database}）以外の設定項目の仕様を持ち、設定値を検証・変換するクラス<br>
 * 設定項目の仕様（キー・既定値・値の形式。docs/usage/cli.mdの「config.ymlの記載内容」）をこのクラスに集める。 ファイルの読み込みは{@link
 * ConfigFile}に委ね、このクラスは読み込んだ値を、CLI引数による上書き値 （{@link CliArguments}が解決する）で上書きしたうえで仕様に照らして検証し、型へ変換する。
 *
 * <ul>
 *   <li>キーの省略と値が空は、同じ「未指定」として扱い既定値を用いる
 *   <li>CLI引数で上書きした値も、設定ファイルに書いた値と同じ仕様で検証する
 *   <li>未知のキー（キー名の書き誤り等）・形式の合わない値（リストを書く項目に1つの値を書いた等）・整数として解釈できない値・ 出力対象の条件として解釈できない値は誤りとする
 *   <li>見つかった誤りは、1件ずつではなくまとめて{@link InvalidConfigurationException}で報告する
 * </ul>
 *
 * 設定値は生の値のまま後続へ渡さず、ここで前後の空白を除去し、型へ変換・検証する （出力対象の条件の変換・検証は{@link TargetSelection#of}に委ねる）
 */
final class DbxrayProperties {

  /** DB接続情報のセクション（仕様と検証は{@code ConnectionSettings}が持つ） */
  static final String DATABASE_SECTION = "database";

  private static final Setting SCHEMAS = new Setting("target", "schemas", "--schema", Kind.LIST);
  private static final Setting TABLES = new Setting("target", "tables", "--table", Kind.LIST);
  private static final Setting OBJECTS =
      new Setting("target", "objects", "--output-objects", Kind.LIST);
  private static final Setting OUTPUT_PATH =
      new Setting("output", "path", "--output-path", Kind.TEXT);
  private static final Setting CHUNK_SIZE =
      new Setting("output", "chunkSize", "--chunk-size", Kind.INTEGER);
  private static final Setting ER_DIAGRAM_MAX_NODES =
      new Setting("output", "erDiagramMaxNodes", "--er-diagram-max-nodes", Kind.INTEGER);
  private static final Setting ER_DIAGRAM_DISTANCE =
      new Setting("output", "erDiagramDistance", "--er-diagram-distance", Kind.INTEGER);
  private static final Setting ANNOTATIONS =
      new Setting(null, "annotations", "--annotation-path", Kind.TEXT);

  /**
   * 設定ファイルに書ける項目（docs/usage/cli.mdの記載順）<br>
   * CLI引数による上書きも、この一覧から引数名を引く（{@link CliArguments}）ため、項目を追加すれば上書きにも自動で対応する
   */
  static final List<Setting> SETTINGS =
      List.of(
          SCHEMAS,
          TABLES,
          OBJECTS,
          OUTPUT_PATH,
          CHUNK_SIZE,
          ER_DIAGRAM_MAX_NODES,
          ER_DIAGRAM_DISTANCE,
          ANNOTATIONS);

  /** chunkSize未指定時の既定値（1スキーマあたりこの件数ごとに詳細情報を取得・出力する） */
  private static final int DEFAULT_CHUNK_SIZE = 3000;

  /** erDiagramMaxNodes未指定時の既定値（スキーマ別ER図1枚に描画するテーブル数の上限） */
  private static final int DEFAULT_ER_DIAGRAM_MAX_NODES = 80;

  /** erDiagramDistance未指定時の既定値（テーブル定義書のER図に、自テーブルの関連だけを描く） */
  private static final int DEFAULT_ER_DIAGRAM_DISTANCE = 1;

  /** erDiagramDistanceの上限。関連の多いDBでは段数を増やすと図がすぐに大きくなり、読めなくなるため */
  private static final int MAX_ER_DIAGRAM_DISTANCE = 3;

  /** CLI引数でリストの項目を上書きする場合の区切り文字 */
  private static final String LIST_SEPARATOR = ",";

  private final TargetSelection targetSelection;
  private final String sidecarPath;
  private final String outputPath;
  private final int chunkSize;
  private final int erDiagramMaxNodes;
  private final int erDiagramDistance;

  private DbxrayProperties(
      TargetSelection targetSelection,
      String sidecarPath,
      String outputPath,
      int chunkSize,
      int erDiagramMaxNodes,
      int erDiagramDistance) {
    this.targetSelection = targetSelection;
    this.sidecarPath = sidecarPath;
    this.outputPath = outputPath;
    this.chunkSize = chunkSize;
    this.erDiagramMaxNodes = erDiagramMaxNodes;
    this.erDiagramDistance = erDiagramDistance;
  }

  /**
   * CLI引数による上書きをせず、設定ファイルの値だけから組み立てる
   *
   * @throws InvalidConfigurationException 設定に誤りがある場合（見つかった誤りをすべて示す）
   */
  static DbxrayProperties of(ConfigFile configFile) {
    return of(configFile, Map.of());
  }

  /**
   * 誤りの報告には、どの値を上書きしたか（指定元のCLI引数名）を添える。誤った値が設定ファイルではなく
   * CLI引数から来ている場合に、設定ファイルだけを見直して原因が見つからない、とならないようにするため
   *
   * @param overrides 項目のパス（{@link Setting#path()}）をキー、上書きする値とその指定元を値とするマップ（未指定の項目は含まない）
   * @throws InvalidConfigurationException 設定に誤りがある場合（見つかった誤りをすべて示す）
   */
  static DbxrayProperties of(ConfigFile configFile, Map<String, SettingOverride> overrides) {
    final List<String> errors = new ArrayList<>();
    final Map<String, Object> values = fileValues(configFile, errors);
    SETTINGS.forEach(
        setting -> {
          final SettingOverride override = overrides.get(setting.path());
          if (override != null) {
            values.put(setting.path(), setting.kind().fromArgument(override.value()));
          }
        });
    final int chunkSize = integer(values, CHUNK_SIZE, DEFAULT_CHUNK_SIZE, errors);
    final int erDiagramMaxNodes =
        integer(values, ER_DIAGRAM_MAX_NODES, DEFAULT_ER_DIAGRAM_MAX_NODES, errors);
    final int erDiagramDistance =
        integerInRange(
            values,
            ER_DIAGRAM_DISTANCE,
            DEFAULT_ER_DIAGRAM_DISTANCE,
            MAX_ER_DIAGRAM_DISTANCE,
            errors);
    final List<String> schemas = list(values, SCHEMAS, errors);
    final List<String> tables = list(values, TABLES, errors);
    final List<String> objects = list(values, OBJECTS, errors);
    final String sidecarPath = text(values, ANNOTATIONS, errors);
    final String outputPath = text(values, OUTPUT_PATH, errors);
    TargetSelection targetSelection = null;
    IllegalArgumentException targetSelectionError = null;
    try {
      targetSelection = TargetSelection.of(schemas, tables, objects);
    } catch (IllegalArgumentException e) {
      // 出力対象の条件の誤りは、1行に1件ずつ示される
      targetSelectionError = e;
      errors.addAll(e.getMessage().lines().toList());
    }
    if (!errors.isEmpty()) {
      throw new InvalidConfigurationException(
          "Invalid configuration in "
              + configFile.path()
              + overriddenBy(overrides)
              + "."
              + errors.stream()
                  .map(error -> System.lineSeparator() + "  - " + error)
                  .collect(Collectors.joining()),
          targetSelectionError);
    }
    return new DbxrayProperties(
        targetSelection, sidecarPath, outputPath, chunkSize, erDiagramMaxNodes, erDiagramDistance);
  }

  /**
   * 通常実行のユースケースへの入力に変換する
   *
   * @param rmDist trueの場合、書き込みを開始する前に出力先ディレクトリを再帰的に削除する（{@code --rm-dist}）
   * @param preview trueの場合、プレビューの機能をすべて有効にする（{@code --preview}）
   */
  ExportSchemaRequest toExportSchemaRequest(boolean rmDist, boolean preview) {
    return new ExportSchemaRequest(
        targetSelection,
        sidecarPath,
        outputPath,
        chunkSize,
        erDiagramMaxNodes,
        erDiagramDistance,
        rmDist,
        preview ? EnumSet.allOf(PreviewFeature.class) : Set.of());
  }

  /** 通常実行と異なり、Markdownの描画・ER図の生成を行わないため{@code erDiagramMaxNodes}・{@code erDiagramDistance}は含めない */
  CheckDocumentDiffRequest toCheckDocumentDiffRequest() {
    return new CheckDocumentDiffRequest(targetSelection, sidecarPath, outputPath, chunkSize);
  }

  /**
   * 設定ファイルの値を、項目のパスごとに取り出す。{@link #DATABASE_SECTION}は読み飛ばす
   *
   * @return 項目のパスをキー、YAMLを解析した結果のままの値を値とするマップ（書いていない項目は含まない。変更可能）
   */
  private static Map<String, Object> fileValues(ConfigFile configFile, List<String> errors) {
    final Map<String, Object> values = new HashMap<>();
    final List<String> unknownKeys = new ArrayList<>();
    configFile
        .root()
        .forEach(
            (key, value) -> {
              if (key.equals(DATABASE_SECTION)) {
                return;
              }
              if (SETTINGS.stream().anyMatch(setting -> setting.path().equals(key))) {
                values.put(key, value);
              } else if (SETTINGS.stream().anyMatch(setting -> key.equals(setting.section()))) {
                sectionValues(key, value, values, unknownKeys, errors);
              } else {
                unknownKeys.add(key);
              }
            });
    if (!unknownKeys.isEmpty()) {
      errors.add(
          "Unknown key: "
              + String.join(", ", unknownKeys)
              + " (available keys: "
              + Stream.concat(Stream.of(DATABASE_SECTION), SETTINGS.stream().map(Setting::path))
                  .collect(Collectors.joining(", "))
              + ")");
    }
    return values;
  }

  /** セクションの値がキーと値の組でない場合は、セクションの項目をすべて未指定として扱い、誤りを記録する */
  private static void sectionValues(
      String section,
      Object sectionValue,
      Map<String, Object> values,
      List<String> unknownKeys,
      List<String> errors) {
    if (sectionValue == null) {
      return;
    }
    if (!(sectionValue instanceof Map<?, ?> map)) {
      errors.add(section + " must be a mapping of keys and values.");
      return;
    }
    map.forEach(
        (key, value) -> {
          final String path = section + "." + key;
          if (SETTINGS.stream().anyMatch(setting -> setting.path().equals(path))) {
            values.put(path, value);
          } else {
            unknownKeys.add(path);
          }
        });
  }

  /**
   * @return 上書きした値がある場合は{@code " (overridden by --output-path, --table)"}の形式の文字列、無い場合は空文字
   */
  private static String overriddenBy(Map<String, SettingOverride> overrides) {
    if (overrides.isEmpty()) {
      return "";
    }
    return overrides.values().stream()
        .map(SettingOverride::source)
        .collect(Collectors.joining(", ", " (overridden by ", ")"));
  }

  /**
   * @return 設定値（前後の空白を除去したもの）。未指定の場合と、1つの値でない場合（誤りを記録済み）は空文字
   */
  private static String text(Map<String, Object> values, Setting setting, List<String> errors) {
    final Object value = values.get(setting.path());
    if (value == null) {
      return "";
    }
    if (value instanceof Map || value instanceof Iterable) {
      errors.add(setting.path() + " must be a single value.");
      return "";
    }
    return String.valueOf(value).strip();
  }

  /**
   * {@code schemas: public, sample}のように1つの値として書くと、カンマを含む1つの名前として扱われ、
   * 対象から黙って外れてしまうため、リストで書かれていない場合は誤りとする。 各要素は前後の空白を除去する
   *
   * @return 空要素を除いたリスト。未指定の場合と、リストでない場合（誤りを記録済み）は空リスト
   */
  private static List<String> list(
      Map<String, Object> values, Setting setting, List<String> errors) {
    final Object value = values.get(setting.path());
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> elements)) {
      errors.add(setting.path() + " must be a list (e.g. [a, b]): " + value);
      return List.of();
    }
    if (elements.stream().anyMatch(element -> element instanceof Map || element instanceof List)) {
      errors.add(setting.path() + " must be a list of single values.");
      return List.of();
    }
    return elements.stream()
        .filter(Objects::nonNull)
        .map(element -> String.valueOf(element).strip())
        .filter(element -> !element.isEmpty())
        .toList();
  }

  /**
   * 整数として解釈できない場合は、既定値へ黙って置き換えず、誤りとして記録する
   *
   * @return 設定値。未指定の場合と、解釈できない場合（誤りを記録済み）は既定値
   */
  private static int integer(
      Map<String, Object> values, Setting setting, int defaultValue, List<String> errors) {
    if (values.get(setting.path()) instanceof Integer value) {
      return value;
    }
    final String value = text(values, setting, errors);
    if (value.isEmpty()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      errors.add(setting.path() + " must be an integer: " + value);
      return defaultValue;
    }
  }

  /**
   * 1以上{@code max}以下の整数として解釈できない場合は、既定値へ黙って置き換えず、誤りとして記録する
   *
   * @return 設定値。未指定の場合と、解釈できない・範囲外の場合（誤りを記録済み）は既定値
   */
  private static int integerInRange(
      Map<String, Object> values, Setting setting, int defaultValue, int max, List<String> errors) {
    final int errorCount = errors.size();
    final int value = integer(values, setting, defaultValue, errors);
    if (errors.size() > errorCount) {
      return defaultValue;
    }
    if (value < 1 || value > max) {
      errors.add(setting.path() + " must be between 1 and " + max + ".");
      return defaultValue;
    }
    return value;
  }

  /**
   * 設定項目1つ分の仕様
   *
   * @param section 項目を書くセクション（最上位に書く項目は{@code null}）
   * @param key セクション内のキー
   * @param cliName 上書きするCLI引数名
   */
  record Setting(String section, String key, String cliName, Kind kind) {

    /** 誤りの報告や上書き値の対応付けに使う、項目の位置（例: {@code output.path}・{@code annotations}） */
    String path() {
      return section == null ? key : section + "." + key;
    }
  }

  /** 設定項目の値の形式 */
  enum Kind {
    TEXT,
    INTEGER,
    /** 値のリスト（CLI引数ではカンマ区切りで指定する） */
    LIST;

    /**
     * CLI引数で指定した値を、設定ファイルに書いた値と同じ形にする
     *
     * @return リストの項目はカンマで区切ったリスト、それ以外は指定した値のまま
     */
    Object fromArgument(String value) {
      return this == LIST ? Arrays.asList(value.split(LIST_SEPARATOR)) : value;
    }
  }

  /**
   * CLI引数による、設定値1項目分の上書き
   *
   * @param source 値の指定元のCLI引数名（例: {@code --output-path}）
   */
  record SettingOverride(String value, String source) {}
}
