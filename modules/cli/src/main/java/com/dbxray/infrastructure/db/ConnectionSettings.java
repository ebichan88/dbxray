package com.dbxray.infrastructure.db;

import com.dbxray.config.InvalidConfigurationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 検証済みのDB接続情報を表すクラス<br>
 * 設定ファイル（{@code conf/config.yml}）の{@code database}の値に、環境変数のパスワード・CLI引数の値を重ねたもの。
 * 組み立てる時に検証するため、インスタンスがあれば必須の項目がそろい、未知のキーを含まないことが保証される
 */
public final class ConnectionSettings {

  /**
   * パスワードを渡す環境変数<br>
   * パスワードは設定ファイルに書けない。作業ディレクトリのファイルはAIエージェント等のツールから読まれ得るため、秘密をファイルに残さない
   */
  private static final String PASSWORD_ENVIRONMENT_VARIABLE = "DBXRAY_DB_PASSWORD";

  private static final String DRIVER_KEY = "driver";

  private static final String URL_KEY = "url";

  private static final String USERNAME_KEY = "username";

  private static final String PASSWORD_KEY = "password";

  /** 接続情報を上書きするCLI引数の名前の接頭辞（キーと合わせて{@code --db-url}等になる） */
  private static final String CLI_ARGUMENT_PREFIX = "--db-";

  /**
   * JDBC URLに埋め込んだパスワード<br>
   * 接続プロパティ（{@code ?password=}・{@code ;password=}、{@code sslpassword=}等）と、
   * Oracleの{@code jdbc:oracle:thin:ユーザー/パスワード@...}の形
   */
  private static final Pattern PASSWORD_IN_URL =
      Pattern.compile("(?i)password\\s*=|^jdbc:oracle:[a-z0-9]+:[^@/]*/[^@]*@");

  /**
   * DB接続情報のキー（mybatis-config.xmlが参照する。docs/usage/cli.mdの記載順）<br>
   * passwordは環境変数・CLI引数でのみ指定でき、設定ファイルには書けない
   */
  public static final List<String> CONNECTION_KEYS =
      List.of(DRIVER_KEY, URL_KEY, USERNAME_KEY, PASSWORD_KEY);

  /** DB接続情報のうち、既定値が無く指定が必須のキー（username・passwordはDBの認証方式によっては空でよい） */
  private static final List<String> REQUIRED_CONNECTION_KEYS = List.of(DRIVER_KEY, URL_KEY);

  private final Map<String, String> values;

  private ConnectionSettings(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  /**
   * DB接続情報のキーを上書きするCLI引数の名前を返すメソッド
   *
   * @param key {@link #CONNECTION_KEYS}のいずれか
   * @return CLI引数の名前（例: {@code --db-url}）
   */
  public static String cliArgumentName(String key) {
    return CLI_ARGUMENT_PREFIX + key;
  }

  /**
   * DB接続情報を組み立てるメソッド
   *
   * @return 検証済みのDB接続情報
   * @throws InvalidConfigurationException 未知のキーがある場合や、必須の項目が未指定の場合（見つかった誤りをすべて示す）
   */
  public static ConnectionSettings of(Map<String, String> values) {
    requireValid(values);
    return new ConnectionSettings(values);
  }

  /**
   * 設定ファイルの{@code database}の値を、環境変数のパスワード・上書きする値の順に上書きしてDB接続情報を組み立てるメソッド<br>
   * 環境変数の値が空（空白のみを含む）の場合は、指定しなかったものとして扱う
   *
   * @param baseValues 設定ファイルの{@code database}の値（書いていない場合は空）
   * @param overrides 上書きする接続情報（CLI引数由来。未指定のキーは含まない）
   * @param environment 環境変数（{@link #PASSWORD_ENVIRONMENT_VARIABLE}だけを参照する）
   * @return 検証済みのDB接続情報
   * @throws InvalidConfigurationException 未知のキーがある場合（設定ファイルの{@code password}を含む）や、必須の項目が未指定の場合
   */
  public static ConnectionSettings merge(
      Map<String, String> baseValues, Properties overrides, Map<String, String> environment) {
    requireNoPasswordInFile(baseValues);
    final Map<String, String> values = new HashMap<>(baseValues);
    final String environmentPassword = environment.get(PASSWORD_ENVIRONMENT_VARIABLE);
    if (environmentPassword != null && !environmentPassword.isBlank()) {
      values.put(PASSWORD_KEY, environmentPassword);
    }
    overrides.stringPropertyNames().forEach(key -> values.put(key, overrides.getProperty(key)));
    return of(values);
  }

  /**
   * 未知のキーの一覧に紛れさせず、パスワードの渡し方を示して誤りとする<br>
   * 値が空でも誤りとするのは、行が残っているとパスワードを書く場所だと誤解されるため。
   * URLに埋め込んだパスワードも同じく誤りとする（誤りの報告にURLの値は含めない）
   */
  private static void requireNoPasswordInFile(Map<String, String> baseValues) {
    if (baseValues.containsKey(PASSWORD_KEY)) {
      throw new InvalidConfigurationException(
          "database.password cannot be set in the configuration file. Remove the password line, "
              + "and set the password with the "
              + PASSWORD_ENVIRONMENT_VARIABLE
              + " environment variable (or the "
              + cliArgumentName(PASSWORD_KEY)
              + " argument).");
    }
    if (PASSWORD_IN_URL.matcher(baseValues.getOrDefault(URL_KEY, "")).find()) {
      throw new InvalidConfigurationException(
          "database.url in the configuration file must not contain a password. "
              + "Remove the password from the URL, and set it with the "
              + PASSWORD_ENVIRONMENT_VARIABLE
              + " environment variable (or the "
              + cliArgumentName(PASSWORD_KEY)
              + " argument).");
    }
  }

  /** mybatis-config.xmlのプレースホルダ（{@code ${driver}}等）へ渡す形に変換するメソッド */
  Properties toProperties() {
    final Properties properties = new Properties();
    properties.putAll(values);
    return properties;
  }

  /**
   * DB接続情報を検証するメソッド<br>
   * 必須の項目が未指定のまま接続すると、置換されないプレースホルダ（{@code ${driver}}等）で接続を試みて原因の分かりにくい失敗になるため、接続する前に報告する
   *
   * @throws InvalidConfigurationException 未知のキーがある場合や、必須の項目が未指定の場合（見つかった誤りをすべて示す）
   */
  private static void requireValid(Map<String, String> values) {
    final List<String> errors = new ArrayList<>();
    final List<String> unknownKeys =
        values.keySet().stream()
            .filter(key -> !CONNECTION_KEYS.contains(key))
            .map(key -> "database." + key)
            .sorted()
            .toList();
    if (!unknownKeys.isEmpty()) {
      errors.add(
          "Unknown key: "
              + String.join(", ", unknownKeys)
              + " (available keys: "
              + CONNECTION_KEYS.stream()
                  .filter(key -> !key.equals(PASSWORD_KEY))
                  .map(key -> "database." + key)
                  .collect(Collectors.joining(", "))
              + ")");
    }
    REQUIRED_CONNECTION_KEYS.stream()
        .filter(key -> values.getOrDefault(key, "").isBlank())
        .forEach(
            key ->
                errors.add(
                    "database."
                        + key
                        + " is not set. Set it in the configuration file, or use the "
                        + cliArgumentName(key)
                        + " argument."));
    if (!errors.isEmpty()) {
      throw new InvalidConfigurationException(
          "Invalid database connection settings."
              + errors.stream()
                  .map(error -> System.lineSeparator() + "  - " + error)
                  .collect(Collectors.joining()));
    }
  }
}
