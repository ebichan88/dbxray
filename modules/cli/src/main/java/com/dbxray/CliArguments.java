package com.dbxray;

import com.dbxray.DbxrayProperties.SettingOverride;
import com.dbxray.config.ConfigFile;
import com.dbxray.shared.exception.UserCorrectableException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * コマンドライン引数の解析を行うクラス<br>
 * {@code --check}・{@code --rm-dist}・{@code --preview}フラグの判定と、設定ファイルのパス（{@code
 * --config}）、DB接続情報・実行時設定 （{@code conf/config.yml}の設定値）の上書き値の解決を担う。
 * 設定ファイルの読み込みと、上書きした値の検証は含まない（{@link DbxrayProperties}・{@code ConnectionSettings}が行う）。
 * 解釈できない引数（書き誤り等）は、意図しないモード・設定で実行されないよう誤りとする（{@link #requireKnownArguments()}）
 */
final class CliArguments {

  /** DB vs ドキュメントの差分検知モードを指定するCLIフラグ（値を持たないブールフラグ） */
  private static final String CHECK_FLAG = "--check";

  /** 書き込み前に出力先ディレクトリを事前に削除するCLIフラグ（値を持たないブールフラグ。{@code --check}指定時は無視される） */
  private static final String RM_DIST_FLAG = "--rm-dist";

  /** プレビューの機能を有効にするCLIフラグ（値を持たないブールフラグ。{@code --check}指定時は効果が無い） */
  private static final String PREVIEW_FLAG = "--preview";

  /** 使い方を表示して終了するCLIフラグ（値を持たないブールフラグ。他の引数より優先する） */
  private static final String HELP_FLAG = "--help";

  /** バージョンを表示して終了するCLIフラグ（値を持たないブールフラグ。{@code --help}と同時に指定した場合は{@code --help}を優先する） */
  private static final String VERSION_FLAG = "--version";

  /** 値を持たないフラグ */
  private static final List<String> FLAGS =
      List.of(CHECK_FLAG, RM_DIST_FLAG, PREVIEW_FLAG, HELP_FLAG, VERSION_FLAG);

  /** 設定ファイルのパスを指定するCLI引数 */
  private static final String CONFIG_ARG = "--config";

  /** DB接続情報の上書きに対応するプロパティキーと、対応するCLI引数名（docs/usage/cli.mdの記載順） */
  private static final List<OverrideArg> CONNECTION_ARGS =
      List.of(
          new OverrideArg("driver", "--db-driver"),
          new OverrideArg("url", "--db-url"),
          new OverrideArg("username", "--db-username"),
          new OverrideArg("password", "--db-password"));

  /** 実行時設定の上書きに対応する項目のパスと、対応するCLI引数名（docs/usage/cli.mdの記載順） */
  private static final List<OverrideArg> SETTING_ARGS =
      DbxrayProperties.SETTINGS.stream()
          .map(setting -> new OverrideArg(setting.path(), setting.cliName()))
          .toList();

  /** {@code --キー=値}形式の引数のキーと値の区切り文字 */
  private static final char KEY_VALUE_SEPARATOR = '=';

  /** 解釈できない引数を報告するときに、値の代わりに示す文字列 */
  private static final String HIDDEN_VALUE = "<hidden>";

  private final boolean check;
  private final boolean rmDist;
  private final boolean preview;
  private final boolean help;
  private final boolean version;
  private final String configPath;
  private final Properties connectionOverrides;
  private final Map<String, SettingOverride> settingOverrides;
  private final List<String> unknownArguments;

  private CliArguments(
      boolean check,
      boolean rmDist,
      boolean preview,
      boolean help,
      boolean version,
      String configPath,
      Properties connectionOverrides,
      Map<String, SettingOverride> settingOverrides,
      List<String> unknownArguments) {
    this.check = check;
    this.rmDist = rmDist;
    this.preview = preview;
    this.help = help;
    this.version = version;
    this.configPath = configPath;
    this.connectionOverrides = connectionOverrides;
    this.settingOverrides = settingOverrides;
    this.unknownArguments = unknownArguments;
  }

  /**
   * 解釈できない引数があっても例外は投げない（実行モードの判定に使えるよう解析は最後まで行い、誤りは {@link #requireKnownArguments()}で報告する）
   *
   * @param args コマンドライン引数（{@code --db-url=...}・{@code --output-path=...}のような{@code --キー=値}形式で
   *     DB接続情報・実行時設定を上書き可能。未指定の場合は設定ファイル（{@code --config}で指定したファイル、未指定なら{@code
   *     conf/config.yml}）の値が使用される）
   */
  static CliArguments parse(String[] args) {
    final List<String> argList = Arrays.asList(args);
    final Map<String, String> cliArgs = parseArgs(args);
    return new CliArguments(
        argList.contains(CHECK_FLAG),
        argList.contains(RM_DIST_FLAG),
        argList.contains(PREVIEW_FLAG),
        argList.contains(HELP_FLAG),
        argList.contains(VERSION_FLAG),
        cliArgs.getOrDefault(CONFIG_ARG, "").strip(),
        resolveConnectionOverrides(cliArgs),
        resolveSettingOverrides(cliArgs),
        argList.stream().filter(arg -> !isKnown(arg)).toList());
  }

  /** {@code --check}（差分検知モード）が指定されたか */
  boolean isCheck() {
    return check;
  }

  /** {@code --rm-dist}（書き込み前の出力先の削除）が指定されたか */
  boolean isRmDist() {
    return rmDist;
  }

  /** {@code --preview}（プレビューの機能の有効化）が指定されたか */
  boolean isPreview() {
    return preview;
  }

  /** {@code --help}（使い方の表示）が指定されたか */
  boolean isHelp() {
    return help;
  }

  /** {@code --version}（バージョンの表示）が指定されたか */
  boolean isVersion() {
    return version;
  }

  /**
   * 使い方の表示に載せる、{@code --キー=値}形式で上書きできる引数名
   *
   * @return DB接続情報・実行時設定の上書きに使える引数名（docs/usage/cli.mdの記載順。{@code --config}は含まない）
   */
  static List<String> overrideArgumentNames() {
    return Stream.concat(CONNECTION_ARGS.stream(), SETTING_ARGS.stream())
        .map(OverrideArg::cliName)
        .toList();
  }

  /**
   * 読み込む設定ファイルのパス
   *
   * @return {@code --config}で指定したパス。未指定（値が空を含む）の場合は{@link ConfigFile#DEFAULT_PATH}
   * @throws UserCorrectableException 指定した値がパスとして解釈できない場合
   */
  Path configPath() {
    if (configPath.isEmpty()) {
      return ConfigFile.DEFAULT_PATH;
    }
    try {
      return Path.of(configPath);
    } catch (InvalidPathException e) {
      throw new UserCorrectableException(
          CONFIG_ARG + " is not a valid path. [" + CONFIG_ARG + "=" + configPath + "]", e);
    }
  }

  /**
   * CLI引数で指定されたDB接続情報の上書き値
   *
   * @return 上書きするDB接続情報（未指定のキーは含まれない）
   */
  Properties connectionOverrides() {
    return connectionOverrides;
  }

  /**
   * CLI引数で指定された実行時設定の上書き値
   *
   * @return 項目のパス（例: {@code output.path}）をキー、上書きする値とその指定元を値とするマップ。
   *     未指定の項目は含まれず、docs/usage/cli.mdの記載順に並ぶ
   */
  Map<String, SettingOverride> settingOverrides() {
    return settingOverrides;
  }

  /**
   * {@code --chek}のような書き誤りを黙って無視すると、差分検知のつもりで通常実行（{@code --rm-dist}なら出力先の削除）が
   * 行われてしまうため、処理を始める前に誤りとして報告する
   *
   * @throws UserCorrectableException 解釈できない引数が指定されている場合（該当する引数をすべて、値を伏せて示す）
   */
  void requireKnownArguments() {
    if (unknownArguments.isEmpty()) {
      return;
    }
    throw new UserCorrectableException(
        "Unknown argument: "
            + unknownArguments.stream().map(CliArguments::masked).collect(Collectors.joining(", "))
            + " (values are not shown; specify a value as --name=value)"
            + " (available arguments: "
            + Stream.of(
                    FLAGS.stream(),
                    Stream.of(CONFIG_ARG + "=<path>"),
                    Stream.concat(CONNECTION_ARGS.stream(), SETTING_ARGS.stream())
                        .map(arg -> arg.cliName() + "=<value>"))
                .flatMap(Function.identity())
                .collect(Collectors.joining(", "))
            + ")");
  }

  /**
   * 誤りの報告は画面とログファイルに残るため、引数の値を伏せる<br>
   * {@code --db-pasword=秘密}のような書き誤りや、{@code --db-password 秘密}のように{@code =}の代わりに空白で区切った
   * 値（名前の無い引数になる）にパスワードが含まれうる
   */
  private static String masked(String arg) {
    if (!arg.startsWith("-")) {
      return HIDDEN_VALUE;
    }
    final int separatorIndex = arg.indexOf(KEY_VALUE_SEPARATOR);
    return separatorIndex < 0 ? arg : arg.substring(0, separatorIndex + 1) + HIDDEN_VALUE;
  }

  private static boolean isKnown(String arg) {
    if (FLAGS.contains(arg)) {
      return true;
    }
    final int separatorIndex = arg.indexOf(KEY_VALUE_SEPARATOR);
    if (separatorIndex < 0) {
      return false;
    }
    final String name = arg.substring(0, separatorIndex);
    return name.equals(CONFIG_ARG)
        || Stream.concat(CONNECTION_ARGS.stream(), SETTING_ARGS.stream())
            .anyMatch(overrideArg -> overrideArg.cliName().equals(name));
  }

  /** 値が空（空白のみを含む）の場合は、指定しなかったものとして設定ファイルの{@code database}の値をそのまま使用する */
  private static Properties resolveConnectionOverrides(Map<String, String> cliArgs) {
    final Properties overrides = new Properties();
    CONNECTION_ARGS.forEach(
        connectionArg -> {
          final String value = cliArgs.get(connectionArg.cliName());
          if (value != null && !value.isBlank()) {
            overrides.setProperty(connectionArg.key(), value);
          }
        });
    return overrides;
  }

  /** 値が空（空白のみを含む）の場合は、指定しなかったものとして設定ファイルの値をそのまま使用する */
  private static Map<String, SettingOverride> resolveSettingOverrides(Map<String, String> cliArgs) {
    final Map<String, SettingOverride> overrides = new LinkedHashMap<>();
    SETTING_ARGS.forEach(
        settingArg -> {
          final String value = cliArgs.get(settingArg.cliName());
          if (value != null && !value.isBlank()) {
            overrides.put(settingArg.key(), new SettingOverride(value, settingArg.cliName()));
          }
        });
    return overrides;
  }

  private static Map<String, String> parseArgs(String[] args) {
    final Map<String, String> result = new HashMap<>();
    for (final String arg : args) {
      final int separatorIndex = arg.indexOf(KEY_VALUE_SEPARATOR);
      if (!arg.startsWith("--") || separatorIndex < 0) {
        continue;
      }
      result.put(arg.substring(0, separatorIndex), arg.substring(separatorIndex + 1));
    }
    return result;
  }

  /** 上書きに対応する設定のキーとCLI引数名の組 */
  private record OverrideArg(String key, String cliName) {}
}
