package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.Page.withPageProperties;
import static com.dbxray.mcp.tool.ToolSpecifications.DATABASE_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.SCHEMA_FILTER_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.booleanProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.namedObjectProperties;
import static com.dbxray.mcp.tool.ToolSpecifications.objectSchema;
import static com.dbxray.mcp.tool.ToolSpecifications.readOnlyTool;
import static com.dbxray.mcp.tool.ToolSpecifications.stringProperty;

import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.FunctionOverloads;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.NameFilter;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableTrigger;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 関数・プロシージャを調べるツール（{@code list_functions}・{@code get_function}）<br>
 * 定義本体はAIのコンテキストを圧迫するため、既定では返さない（{@code get_function}の{@code includeDefinition}で返す）
 */
final class FunctionTools {

  static final String LIST_FUNCTIONS = "list_functions";
  static final String GET_FUNCTION = "get_function";

  /** 定義本体の項目名（スナップショットの項目名） */
  private static final String DEFINITION_FIELD = "definition";

  /** 利用しているテーブルの項目名 */
  private static final String TABLE_USAGE_FIELD = "tableUsage";

  /** Oracleのパッケージ内サブプログラムは数千行になり得るため、AIのコンテキストを圧迫しないよう切り詰める文字数 */
  private static final int MAX_DEFINITION_LENGTH = 4_000;

  private final SchemaCatalog catalog;

  FunctionTools(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  /** ツールの一覧 */
  List<SyncToolSpecification> specifications() {
    return List.of(
        readOnlyTool(
            LIST_FUNCTIONS,
            "関数一覧",
            "関数・プロシージャの名前・種別・引数・戻り値・言語を、DB名・スキーマ名・名前の順に一覧で返す（定義本体は返さない）。" + "同名の関数（オーバーロード）はそれぞれ返す",
            objectSchema(
                withPageProperties(
                    Map.of(
                        "query", stringProperty("名前の一部で絞り込む場合に指定する（大文字小文字を区別しない）"),
                        "schema", SCHEMA_FILTER_PROPERTY,
                        "database", DATABASE_PROPERTY),
                    Page.DEFAULT_LIMIT,
                    Page.MAX_LIMIT),
                List.of()),
            this::listFunctions),
        readOnlyTool(
            GET_FUNCTION,
            "関数定義取得",
            "関数・プロシージャのシグネチャ（種別・引数・戻り値・言語）を返す。同名の関数（オーバーロード）はまとめて返す。"
                + "定義本体は既定では返さない。includeDefinitionを指定すると返す"
                + "（オーバーロードの本体がすべて同じ場合は1つにまとめ、長い場合は切り詰める）。"
                + "cliを--previewで実行した参考情報がある場合は、オーバーロードごとにtableUsage（利用しているテーブルと操作C・R・U・D、動的SQLの種類）を返す。"
                + "tableUsageは定義本体から機械的に抽出した参考値で、cliの出力対象のテーブル（ビューを含む）と一致したものだけを返す。"
                + "出力対象外のテーブル、呼び出している関数の中と動的SQLの中の参照は含まないため、tablesが空でもテーブルに触れないとは限らない。"
                + "スキーマが決まらない名前はtableにスキーマを付けず、schemaResolutionに何で決まるか"
                + "（search_path: 実行時のsearch_path、current_user: 実行者権限のため実行するユーザー、"
                + "synonym: 所有者のスキーマに無くシノニム経由の可能性）を、"
                + "schemaCandidatesに同じ名前のテーブルがあるスキーマを返す。候補が1つでもそのスキーマに決めつけないこと。"
                + "解析しなかった場合はstatus（unsupported_language等）だけを返す",
            objectSchema(
                namedObjectProperties(
                    "function",
                    "関数・プロシージャ名（大文字小文字を区別しない）。スキーマ名.関数名の形でもよい",
                    Map.of("includeDefinition", booleanProperty("定義本体も返す（既定false）"))),
                List.of("function")),
            this::getFunction));
  }

  private CallToolResult listFunctions(ToolArguments arguments) {
    final NameFilter filter = NameFilter.of(arguments.optionalString("query"));
    final Page page = Page.read(arguments, Page.DEFAULT_LIMIT);
    final List<FunctionEntry> functions =
        catalog.functions().list(arguments.scope(), filter).stream()
            .flatMap(function -> function.overloads().stream())
            .toList();
    return ToolResults.json(
        new ListFunctionsOutput(
            functions.size(),
            page.nextOffset(functions.size()),
            page.apply(functions).stream().map(ListFunctionsOutput.Function::of).toList()));
  }

  private CallToolResult getFunction(ToolArguments arguments) {
    final boolean includeDefinition = arguments.optionalBoolean("includeDefinition", false);
    final FunctionOverloads function =
        ObjectResolver.resolve(
            arguments, "function", "関数", LIST_FUNCTIONS, catalog.functions()::lookup);
    final List<ObjectNode> overloads =
        function.overloads().stream()
            .map(
                entry -> {
                  final ObjectNode signature = signature(entry, includeDefinition);
                  catalog
                      .functionTableUsages()
                      .find(entry)
                      .ifPresent(usage -> signature.set(TABLE_USAGE_FIELD, tableUsage(usage)));
                  return signature;
                })
            .toList();
    final Optional<String> sharedDefinition =
        includeDefinition ? sharedDefinition(overloads) : Optional.empty();
    if (sharedDefinition.isPresent()) {
      // 全オーバーロードで同じ本体は重複して持たせず、結果の上位のdefinitionに1つだけ置く
      overloads.forEach(node -> node.remove(DEFINITION_FIELD));
    } else {
      overloads.forEach(FunctionTools::truncateDefinitionOf);
    }
    return ToolResults.json(
        new GetFunctionOutput(
            function.key().database(),
            function.key().schema(),
            function.key().name(),
            sharedDefinition.map(FunctionTools::truncateDefinition).orElse(null),
            overloads,
            catalog.tables().triggersCalling(function).stream().map(CallingTrigger::of).toList()));
  }

  /** スナップショットの1行から、関数を識別する項目（呼び出し側で返す）を除いたもの */
  private static ObjectNode signature(FunctionEntry function, boolean includeDefinition) {
    final ObjectNode signature = ToolResults.readObject(function.json());
    signature.remove(List.of("schema", "name"));
    if (!includeDefinition) {
      signature.remove(DEFINITION_FIELD);
    }
    return signature;
  }

  /**
   * 利用しているテーブルの参考情報を、AIが読みやすい形にするメソッド<br>
   * テーブルは{@code スキーマ名.テーブル名}、操作は{@code CRU}のように1つの文字列にまとめる。 解析した結果は、利用しているテーブルが無い場合も{@code
   * tables}を空の配列で返す（参考情報が無い場合と区別するため）
   *
   * @return 解析しなかった場合は{@code status}（と{@code language}）だけ
   */
  private static ObjectNode tableUsage(FunctionTableUsageEntry usage) {
    final ObjectNode node = JsonNodeFactory.instance.objectNode();
    if (!usage.status().equals(FunctionTableUsageEntry.ANALYZED)) {
      node.put("status", usage.status());
      if (!usage.language().isEmpty()) {
        node.put("language", usage.language());
      }
      return node;
    }
    final ArrayNode tables = node.putArray("tables");
    for (final UsedTable table : usage.tables()) {
      final ObjectNode tableNode = tables.addObject();
      if (table.schema().isEmpty()) {
        tableNode.put("table", table.name());
        if (!table.schemaResolution().isEmpty()) {
          tableNode.put("schemaResolution", table.schemaResolution());
        }
        final ArrayNode candidates = tableNode.putArray("schemaCandidates");
        table.schemaCandidates().forEach(candidates::add);
      } else {
        tableNode.put("table", table.schema() + "." + table.name());
      }
      tableNode.put("operations", String.join("", table.operations()));
    }
    if (!usage.dynamicSql().isEmpty()) {
      final ArrayNode dynamicSql = node.putArray("dynamicSql");
      usage.dynamicSql().forEach(dynamicSql::add);
    }
    if (usage.incomplete()) {
      node.put("incomplete", true);
    }
    if (usage.overloadsMerged()) {
      node.put("overloadsMerged", true);
    }
    return node;
  }

  /**
   * 全オーバーロードの定義本体が同じ場合（Oracleのパッケージ内サブプログラム等）に、その本体を求めるメソッド
   *
   * @return 本体が異なる場合・いずれも本体を持たない場合は空
   */
  private static Optional<String> sharedDefinition(List<ObjectNode> overloads) {
    final Set<String> definitions =
        overloads.stream()
            .map(node -> node.path(DEFINITION_FIELD).asText(""))
            .filter(text -> !text.isEmpty())
            .collect(Collectors.toSet());
    return definitions.size() == 1 ? Optional.of(definitions.iterator().next()) : Optional.empty();
  }

  /** オーバーロードの定義本体が長い場合に切り詰める（本体を持たない場合は何もしない） */
  private static void truncateDefinitionOf(ObjectNode overload) {
    final String definition = overload.path(DEFINITION_FIELD).asText(null);
    if (definition != null) {
      overload.put(DEFINITION_FIELD, truncateDefinition(definition));
    }
  }

  private static String truncateDefinition(String definition) {
    if (definition.length() <= MAX_DEFINITION_LENGTH) {
      return definition;
    }
    return definition.substring(0, MAX_DEFINITION_LENGTH)
        + "\n...(切り詰め。全"
        + definition.length()
        + "文字中"
        + MAX_DEFINITION_LENGTH
        + "文字を表示)";
  }

  /**
   * {@code list_functions}の結果
   *
   * @param nextOffset 続きを取得するときに指定する{@code offset}。続きが無い場合はnull
   */
  record ListFunctionsOutput(int total, Integer nextOffset, List<Function> functions) {

    /** 1関数・プロシージャのシグネチャ */
    record Function(
        String database,
        String schema,
        String name,
        String kind,
        String arguments,
        String result,
        String language) {

      static Function of(FunctionEntry function) {
        return new Function(
            function.key().database(),
            function.key().schema(),
            function.key().name(),
            function.kind(),
            function.arguments(),
            function.result(),
            function.language());
      }
    }
  }

  /**
   * {@code get_function}の結果
   *
   * @param definition {@code includeDefinition}指定時、全オーバーロードの定義本体が同じ場合にまとめた本体。 本体が異なる場合・{@code
   *     includeDefinition}未指定の場合はnull（その場合、本体は{@code overloads}側に残る）
   * @param overloads オーバーロードごとのシグネチャ（スナップショットの1行から名前を除いたもの。cliが項目を追加すれば、そのまま返る）。
   *     参考情報がある場合は、利用しているテーブル（{@code tableUsage}）を加える
   * @param calledByTriggers この関数を実行するトリガー
   */
  record GetFunctionOutput(
      String database,
      String schema,
      String name,
      String definition,
      List<ObjectNode> overloads,
      List<CallingTrigger> calledByTriggers) {}

  /**
   * 関数を実行するトリガー
   *
   * @param table トリガーを持つテーブル（{@code スキーマ名.テーブル名}）
   */
  record CallingTrigger(String table, String trigger, String timing, List<String> events) {

    static CallingTrigger of(TableTrigger found) {
      return new CallingTrigger(
          found.table().key().qualifiedName(),
          found.trigger().name(),
          found.trigger().timing(),
          found.trigger().events());
    }
  }
}
