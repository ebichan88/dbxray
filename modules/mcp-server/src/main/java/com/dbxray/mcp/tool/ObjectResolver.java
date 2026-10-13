package com.dbxray.mcp.tool;

import com.dbxray.mcp.catalog.Lookup;
import com.dbxray.mcp.catalog.ObjectReference;
import com.dbxray.mcp.catalog.SchemaObject;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** ツールの引数で名前を指定されたオブジェクトの解決 */
final class ObjectResolver {

  private static final String AMBIGUITY_HINT = "schema（DBが異なる場合はdatabase）を指定してください。";

  private ObjectResolver() {}

  /**
   * 引数の名前・{@code schema}・{@code database}で指定されたオブジェクトを1つに解決するメソッド
   *
   * @param nameArgument オブジェクト名を受け取る引数名
   * @param kind エラーのメッセージに使う種類の呼び方（例: テーブル）
   * @param searchTool 似た名前も無い場合に案内する、探すためのツール名
   * @throws InvalidToolArgumentException 見つからない場合、複数に当てはまる場合（候補をメッセージに含める）
   */
  static <E extends SchemaObject> E resolve(
      ToolArguments arguments,
      String nameArgument,
      String kind,
      String searchTool,
      Function<ObjectReference, Lookup<E>> lookup) {
    final ObjectReference reference =
        ObjectReference.of(
            arguments.optionalString("database"),
            arguments.optionalString("schema"),
            arguments.requiredString(nameArgument));
    return resolve(reference, kind, searchTool, lookup);
  }

  /**
   * 名前の指定（{@link ObjectReference}）で、オブジェクトを1つに解決するメソッド<br>
   * 1件の名前を複数回解決する場合（{@code get_table}で複数のテーブルを指定する等）に使う
   *
   * @throws InvalidToolArgumentException 見つからない場合、複数に当てはまる場合（候補をメッセージに含める）
   */
  static <E extends SchemaObject> E resolve(
      ObjectReference reference,
      String kind,
      String searchTool,
      Function<ObjectReference, Lookup<E>> lookup) {
    return resolve(reference, kind, searchTool, AMBIGUITY_HINT, lookup);
  }

  /**
   * 名前の指定（{@link ObjectReference}）で、オブジェクトを1つに解決するメソッド<br>
   * 引数{@code schema}を名前の解決に使わないツールのように、複数に当てはまる場合の案内を変えたいときに使う
   *
   * @param ambiguityHint 複数に当てはまる場合に、絞り込み方として示す文
   * @throws InvalidToolArgumentException 見つからない場合、複数に当てはまる場合（候補をメッセージに含める）
   */
  static <E extends SchemaObject> E resolve(
      ObjectReference reference,
      String kind,
      String searchTool,
      String ambiguityHint,
      Function<ObjectReference, Lookup<E>> lookup) {
    return switch (lookup.apply(reference)) {
      case Lookup.Found<E> found -> found.value();
      case Lookup.Ambiguous<E> ambiguous ->
          throw new InvalidToolArgumentException(
              kind
                  + reference.name()
                  + "が複数あります。"
                  + ambiguityHint
                  + "候補: "
                  + describe(ambiguous.candidates()));
      case Lookup.NotFound<E> notFound ->
          throw new InvalidToolArgumentException(
              kind
                  + reference.name()
                  + "が見つかりません。"
                  + (notFound.suggestions().isEmpty()
                      ? searchTool + "で探してください。"
                      : "名前の似た" + kind + ": " + describe(notFound.suggestions())));
    };
  }

  private static String describe(List<? extends SchemaObject> objects) {
    return objects.stream()
        .map(object -> object.key().database() + ":" + object.key().qualifiedName())
        .collect(Collectors.joining(", "));
  }
}
