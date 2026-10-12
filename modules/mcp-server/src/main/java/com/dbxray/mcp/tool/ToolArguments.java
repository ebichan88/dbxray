package com.dbxray.mcp.tool;

import com.dbxray.mcp.catalog.SearchScope;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ツールに渡された引数の読み取りと検証<br>
 * 型・範囲・未知の引数は、ハンドラを呼ぶ前にMCPのSDKが入力スキーマ（JSON Schema）で検証する。
 * ここではスキーマで表せない検証（空白の除去後の空・件数の上限・選択肢の照合等）だけを行う
 */
final class ToolArguments {

  private final Map<String, Object> values;

  /**
   * @param values ツールに渡された引数。nullの場合は引数なしとみなす
   */
  ToolArguments(Map<String, Object> values) {
    this.values = values == null ? Map.of() : values;
  }

  /**
   * 必須の文字列の引数を読み取るメソッド
   *
   * @return 前後の空白を除いた値
   * @throws InvalidToolArgumentException 未指定・空・文字列でない場合
   */
  String requiredString(String name) {
    final String value = optionalString(name);
    if (value.isEmpty()) {
      throw new InvalidToolArgumentException("引数" + name + "を指定してください。");
    }
    return value;
  }

  /** 引数が指定されているか（値がnullの場合は未指定とみなす）判定するメソッド */
  boolean isPresent(String name) {
    return values.get(name) != null;
  }

  /**
   * 任意の文字列の引数を読み取るメソッド
   *
   * @return 前後の空白を除いた値。未指定（null）の場合は空文字
   * @throws InvalidToolArgumentException 文字列でない場合
   */
  String optionalString(String name) {
    final Object value = values.get(name);
    if (value == null) {
      return "";
    }
    if (!(value instanceof String text)) {
      throw new InvalidToolArgumentException("引数" + name + "には文字列を指定してください。");
    }
    return text.strip();
  }

  /**
   * 任意の整数の引数を読み取るメソッド（範囲は入力スキーマの{@code minimum}・{@code maximum}で検証済み）
   *
   * @return 引数の値。未指定（null）の場合は{@code defaultValue}
   * @throws InvalidToolArgumentException 数値でない場合
   */
  int optionalInt(String name, int defaultValue) {
    final Object value = values.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)) {
      throw new InvalidToolArgumentException("引数" + name + "には整数を指定してください。 [value=" + value + "]");
    }
    return number.intValue();
  }

  /**
   * 任意の真偽値の引数を読み取るメソッド
   *
   * @return 引数の値。未指定（null）の場合は{@code defaultValue}
   * @throws InvalidToolArgumentException 真偽値でない場合
   */
  boolean optionalBoolean(String name, boolean defaultValue) {
    final Object value = values.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean bool)) {
      throw new InvalidToolArgumentException(
          "引数" + name + "にはtrueまたはfalseを指定してください。 [value=" + value + "]");
    }
    return bool;
  }

  /**
   * 任意の文字列のリストの引数を読み取るメソッド<br>
   * JSONの配列のほか、カンマ区切りの文字列も受け付ける（配列を文字列にして渡すMCPクライアントがあるため）。
   * 文字列が届くのは、入力スキーマの型が文字列・配列のどちらも許す引数（{@link ToolSpecifications#stringOrArrayProperty}）だけ
   *
   * @return 前後の空白を除き、空の要素と重複を除いた値（指定順）。未指定（null）の場合は空リスト
   * @throws InvalidToolArgumentException 配列・文字列でない場合、文字列でない要素を含む場合
   */
  List<String> optionalStringList(String name) {
    final Object value = values.get(name);
    if (value == null) {
      return List.of();
    }
    final List<?> elements;
    if (value instanceof List<?> list) {
      elements = list;
    } else if (value instanceof String text) {
      elements = Arrays.asList(text.split(","));
    } else {
      throw new InvalidToolArgumentException("引数" + name + "には文字列の配列を指定してください。");
    }
    final Set<String> result = new LinkedHashSet<>();
    for (final Object element : elements) {
      if (!(element instanceof String text)) {
        throw new InvalidToolArgumentException("引数" + name + "には文字列の配列を指定してください。");
      }
      if (!text.isBlank()) {
        result.add(text.strip());
      }
    }
    return List.copyOf(result);
  }

  /**
   * 必須の文字列のリストの引数を読み取るメソッド（{@link #optionalStringList}と同じく配列・カンマ区切りの文字列・単一の文字列を受け付ける）
   *
   * @param max 受け付ける件数の上限
   * @return 前後の空白を除き、空の要素と重複を除いた値（指定順）
   * @throws InvalidToolArgumentException 未指定・空の場合、{@code max}を超える件数の場合
   */
  List<String> requiredStringList(String name, int max) {
    final List<String> values = optionalStringList(name);
    if (values.isEmpty()) {
      throw new InvalidToolArgumentException("引数" + name + "を指定してください。");
    }
    if (values.size() > max) {
      throw new InvalidToolArgumentException(
          "引数" + name + "は" + max + "件までにしてください。 [count=" + values.size() + "]");
    }
    return values;
  }

  /**
   * 任意の、決まった値のいずれかを取る文字列の引数を読み取るメソッド<br>
   * 値は入力スキーマの{@code enum}でSDKが検証済みのため、完全一致で照合する
   *
   * @param choices 受け付ける値
   * @return {@code choices}のうち当てはまった値。未指定（null・空）の場合は空文字
   * @throws InvalidToolArgumentException {@code choices}のいずれにも当てはまらない場合
   */
  String optionalChoice(String name, List<String> choices) {
    final String value = optionalString(name);
    if (value.isEmpty()) {
      return value;
    }
    return choices.stream()
        .filter(choice -> choice.equals(value))
        .findFirst()
        .orElseThrow(() -> invalidChoice(name, choices, value));
  }

  /**
   * 任意の列挙値の引数を読み取るメソッド（列挙定数名を小文字にした値で受け付ける）<br>
   * 値は入力スキーマの{@code enum}でSDKが検証済みのため、完全一致で照合する
   *
   * @return 引数の値。未指定（null）の場合は{@code defaultValue}
   * @throws InvalidToolArgumentException 列挙定数のいずれにも当てはまらない場合
   */
  <E extends Enum<E>> E optionalEnum(String name, Class<E> type, E defaultValue) {
    final String value = optionalString(name);
    if (value.isEmpty()) {
      return defaultValue;
    }
    for (final E constant : type.getEnumConstants()) {
      if (lowerName(constant).equals(value)) {
        return constant;
      }
    }
    throw invalidChoice(name, lowerNames(type), value);
  }

  /**
   * 引数{@code database}・{@code schema}による絞り込みを読み取るメソッド
   *
   * @throws InvalidToolArgumentException 文字列でない場合
   */
  SearchScope scope() {
    return new SearchScope(optionalString("database"), optionalString("schema"));
  }

  /** 決まった値のいずれでもない引数の誤り */
  static InvalidToolArgumentException invalidChoice(
      String name, List<String> choices, String value) {
    return new InvalidToolArgumentException(
        "引数" + name + "には" + String.join(", ", choices) + "のいずれかを指定してください。 [value=" + value + "]");
  }

  /** ツールの入力スキーマ（enum）にも使う、列挙定数名を小文字にした値の一覧 */
  static <E extends Enum<E>> List<String> lowerNames(Class<E> type) {
    return Arrays.stream(type.getEnumConstants()).map(ToolArguments::lowerName).toList();
  }

  private static String lowerName(Enum<?> constant) {
    return constant.name().toLowerCase(Locale.ROOT);
  }
}
