package com.dbxray.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 設定ファイル（{@code conf/config.yml}）を読み込んだ内容を表すクラス<br>
 * ファイルの読み込みとYAMLとしての解析だけを担い、最上位のキーと値の組を保持する。
 * どのキーを書けるか・既定値・値の形式といった設定項目の仕様と検証は、読み込んだ値を使う側が持つ
 * （{@code database}以外は{@code DbxrayProperties}、{@code database}は{@code ConnectionSettings}）
 */
public final class ConfigFile {

  /** {@code --config}で指定しない場合に読み込む設定ファイル（実行したディレクトリからの相対パス） */
  public static final Path DEFAULT_PATH = Path.of("conf", "config.yml");

  private final Path path;
  private final Map<String, Object> root;

  private ConfigFile(Path path, Map<String, Object> root) {
    this.path = path;
    this.root = root;
  }

  /**
   * 設定ファイルを読み込むメソッド<br>
   * ファイルが無い場合に既定値で続行しないのは、実行するディレクトリを誤った場合に、既定の出力先（{@code ./output}）へ黙って出力しないようにするため
   *
   * @throws InvalidConfigurationException ファイルが存在しない場合や、YAMLとして解釈できない場合、最上位がキーと値の組でない場合
   */
  public static ConfigFile load(Path path) {
    if (!Files.isRegularFile(path)) {
      throw new InvalidConfigurationException(
          "Configuration file does not exist. Run from the directory containing "
              + DEFAULT_PATH
              + ", or specify the file with the --config argument. [file="
              + path.toAbsolutePath().normalize()
              + "]");
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return of(path, parseYaml(reader, path));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to read the configuration file. [file=" + path + "]", e);
    }
  }

  /**
   * 文字列として与えたYAMLから組み立てるメソッド
   *
   * @param path 誤りの報告に示す設定ファイルのパス
   * @throws InvalidConfigurationException YAMLとして解釈できない場合や、最上位がキーと値の組でない場合
   */
  public static ConfigFile parse(Path path, String yaml) {
    return of(path, parseYaml(new StringReader(yaml), path));
  }

  /**
   * @param path 誤りの報告に示す設定ファイルのパス
   * @param content YAMLを解析した結果（空のファイルは{@code null}）
   * @throws InvalidConfigurationException 最上位がキーと値の組でない場合
   */
  private static ConfigFile of(Path path, Object content) {
    if (content == null) {
      return new ConfigFile(path, Map.of());
    }
    if (!(content instanceof Map<?, ?> map)) {
      throw new InvalidConfigurationException(
          "The configuration file must be a mapping of keys and values. [file=" + path + "]");
    }
    return new ConfigFile(path, stringKeys(map));
  }

  /** 誤りの報告に示す設定ファイルのパス */
  public Path path() {
    return path;
  }

  /**
   * 最上位のキーと値の組
   *
   * @return キーと値の組（書いた順。値はYAMLを解析した結果のまま。キーだけを書いた場合の値は{@code null}）
   */
  public Map<String, Object> root() {
    return root;
  }

  /**
   * 最上位のキーの値を、キーと値の組として取り出すメソッド
   *
   * @return キーと値の組（書いた順）。キーの省略・値が空の場合は空
   * @throws InvalidConfigurationException 値がキーと値の組でない場合
   */
  public Map<String, Object> section(String key) {
    final Object value = root.get(key);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new InvalidConfigurationException(
          key + " in " + path + " must be a mapping of keys and values.");
    }
    return stringKeys(map);
  }

  /**
   * 最上位のキーの値を、値が1つずつの項目の組として取り出すメソッド
   *
   * @return キーと文字列にした値の組（書いた順）。キーだけを書いた項目の値は空文字。キーの省略・値が空の場合は空
   * @throws InvalidConfigurationException 値がキーと値の組でない場合や、項目の値がリスト・キーと値の組の場合
   */
  public Map<String, String> scalarSection(String key) {
    final Map<String, String> values = new LinkedHashMap<>();
    section(key)
        .forEach(
            (itemKey, value) -> {
              if (value instanceof Map || value instanceof Iterable) {
                throw new InvalidConfigurationException(
                    key + "." + itemKey + " in " + path + " must be a single value.");
              }
              values.put(itemKey, value == null ? "" : String.valueOf(value));
            });
    return Collections.unmodifiableMap(values);
  }

  /**
   * YAMLとして解釈できないのは利用者が手で書いたファイルの誤りのため、どのファイルを直せばよいかを添えて伝える（解析の失敗箇所は原因の例外のメッセージが示す）
   *
   * @throws InvalidConfigurationException YAMLとして解釈できない場合（構文誤り・UTF-8以外の文字コード等）
   */
  private static Object parseYaml(Reader reader, Path path) {
    try {
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
    } catch (YAMLException e) {
      // 原因の例外は渡さない。メッセージが誤りの行（パスワード等を含みうる）を引用するため
      throw new InvalidConfigurationException(
          "Failed to parse the configuration file. Check that it is valid YAML saved in UTF-8. [file="
              + path
              + ", error="
              + YamlSyntaxErrors.describe(e)
              + "]");
    }
  }

  /** YAMLでは{@code 1:}のように文字列以外もキーになるため、未知のキーとして報告できるよう文字列にそろえる */
  private static Map<String, Object> stringKeys(Map<?, ?> map) {
    final Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return Collections.unmodifiableMap(result);
  }
}
