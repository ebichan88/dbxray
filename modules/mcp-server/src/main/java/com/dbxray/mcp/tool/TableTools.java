package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.Page.withPageProperties;
import static com.dbxray.mcp.tool.ToolSpecifications.DATABASE_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.SCHEMA_FILTER_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.booleanProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.enumArrayProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.enumProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.integerProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.namedObjectProperties;
import static com.dbxray.mcp.tool.ToolSpecifications.objectSchema;
import static com.dbxray.mcp.tool.ToolSpecifications.readOnlyTool;
import static com.dbxray.mcp.tool.ToolSpecifications.stringArrayProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.stringOrArrayProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.stringProperty;

import com.dbxray.mcp.catalog.ColumnHit;
import com.dbxray.mcp.catalog.ColumnQuery;
import com.dbxray.mcp.catalog.MatchMode;
import com.dbxray.mcp.catalog.ObjectReference;
import com.dbxray.mcp.catalog.RelationCounts;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SearchQuery;
import com.dbxray.mcp.catalog.SearchResult;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TableFilter;
import com.dbxray.mcp.catalog.TableHit;
import com.dbxray.mcp.catalog.TableOrder;
import com.dbxray.mcp.catalog.TableType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * テーブルを探す・定義を返すツール（{@code search_tables}・{@code list_tables}・{@code get_table}・
 * {@code find_columns}）
 */
final class TableTools {

  static final String SEARCH_TABLES = "search_tables";
  static final String LIST_TABLES = "list_tables";
  static final String GET_TABLE = "get_table";
  static final String FIND_COLUMNS = "find_columns";

  /** {@code get_table}等でテーブルを指定する引数の説明 */
  static final String TABLE_DESCRIPTION = "テーブル名（大文字小文字を区別しない）。スキーマ名.テーブル名の形でもよい";

  /** {@code list_tables}・{@code search_tables}で観点に絞り込む引数のプロパティ */
  private static final Map<String, Object> VIEWPOINT_PROPERTY =
      stringProperty("指定した観点（list_viewpointsのid）の所属テーブルだけに絞り込む場合に指定する");

  private static final int DEFAULT_SEARCH_LIMIT = 20;
  private static final int MAX_SEARCH_LIMIT = 100;
  private static final int DEFAULT_COLUMN_LIMIT = 50;
  private static final int MAX_COLUMN_LIMIT = 500;

  /** {@code get_table}の{@code table}に配列で指定できる件数の上限 */
  private static final int MAX_TABLES = 10;

  private final SchemaCatalog catalog;

  TableTools(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  /** ツールの一覧（{@code search_tables}・{@code list_tables}・{@code get_table}・{@code find_columns}の順） */
  List<SyncToolSpecification> specifications() {
    return List.of(
        readOnlyTool(
            SEARCH_TABLES,
            "テーブル検索",
            "テーブルをキーワードで検索する。テーブル名・論理名・説明・カラム名・カラムの論理名を部分一致で探し、"
                + "一致の強い順に返す（空白区切りの複数語はすべてを含むものだけ）。テーブル名が分からないときに最初に使う",
            objectSchema(
                Map.of(
                    "query",
                    stringProperty("検索語（例: 従業員、user_id、受注 明細）"),
                    "schema",
                    SCHEMA_FILTER_PROPERTY,
                    "database",
                    DATABASE_PROPERTY,
                    "limit",
                    integerProperty("返す件数の上限（既定" + DEFAULT_SEARCH_LIMIT + "）", 1, MAX_SEARCH_LIMIT),
                    "viewpoint",
                    VIEWPOINT_PROPERTY),
                List.of("query")),
            this::searchTables),
        readOnlyTool(
            LIST_TABLES,
            "テーブル一覧",
            "テーブル（ビューを含む）の名前・論理名・区分と関連の数を一覧で返す。"
                + "関連の数は、外部キー・論理リレーションで自テーブルを参照しているテーブルの数（incoming）、"
                + "自テーブルが参照しているテーブルの数（outgoing）、参照元を"
                + RelationCounts.IMPACT_DEPTH
                + "段までたどって届くテーブルの数（impact。変更の影響範囲）。"
                + "スキーマにどんなテーブルがあるか眺めるときに使い、どこから読むか迷う場合はorderByで"
                + "関連の多い（中心となる）テーブルから並べる。関連の無いテーブルも0として一覧に含める。"
                + "キーワードで探す場合はsearch_tablesを使う",
            objectSchema(
                withPageProperties(
                    Map.of(
                        "schema",
                        SCHEMA_FILTER_PROPERTY,
                        "database",
                        DATABASE_PROPERTY,
                        "type",
                        enumProperty("区分で絞り込む場合に指定する", TableType.allValues()),
                        "includeDescription",
                        booleanProperty("テーブルの説明も返す（既定false）"),
                        "viewpoint",
                        VIEWPOINT_PROPERTY,
                        "orderBy",
                        enumProperty(
                            "並べ方。name: DB名・スキーマ名・テーブル名の順（既定）、incoming・outgoing・impact: "
                                + "その数の多い順（同数は名前の順）",
                            ToolArguments.lowerNames(TableOrder.class))),
                    Page.DEFAULT_LIMIT,
                    Page.MAX_LIMIT),
                List.of()),
            this::listTables),
        readOnlyTool(
            GET_TABLE,
            "テーブル定義取得",
            "テーブル（ビューを含む）の定義を返す。カラム（型・PK・NOT NULL・デフォルト値・論理名・備考）、"
                + "インデックス、制約、外部キー、論理リレーション、トリガー、説明・備考を含む。"
                + "ビューの場合は参照するテーブル（referencedTables）、テーブルを参照しているビューがある場合はそのビュー（referencedByViews）も返す。"
                + "テーブルが観点（list_viewpoints）に所属する場合は、所属する観点（viewpoints。id・表示名）も返す。"
                + "必要な項目だけをsections・columnsで指定すると結果が小さくなる。"
                + "複数のテーブルをまとめて取得する場合はtableに配列を指定する（最大"
                + MAX_TABLES
                + "件。columnsは1件指定したときだけ使える）",
            objectSchema(
                namedObjectProperties(
                    "table",
                    stringOrArrayProperty(TABLE_DESCRIPTION + "。配列で複数指定できる（最大" + MAX_TABLES + "件）"),
                    Map.of(
                        "sections",
                        enumArrayProperty(
                            "返す項目（未指定の場合はすべて）。テーブル名・論理名・区分・説明・備考・所属する観点は常に返す",
                            TableSection.fieldNames()),
                        "columns",
                        stringArrayProperty(
                            "返すカラムの名前（大文字小文字を区別しない）。指定するとcolumnsの項目はそのカラムだけになる。"
                                + "tableを複数指定した場合は使えない"))),
                List.of("table")),
            this::getTable),
        readOnlyTool(
            FIND_COLUMNS,
            "カラム逆引き",
            "カラム名（物理名・論理名）から、そのカラムを持つテーブルを逆引きする。"
                + "型・PK・NOT NULLと、外部キー・論理リレーションの参照先も返す。"
                + "同じ意味のカラムがどのテーブルにあるか、型が揃っているかを調べるときに使う",
            objectSchema(
                withPageProperties(
                    Map.of(
                        "column",
                        stringProperty("カラムの物理名または論理名（例: employee_id、従業員ID）"),
                        "match",
                        enumProperty(
                            "exact: 完全一致（既定）、partial: 部分一致",
                            ToolArguments.lowerNames(MatchMode.class)),
                        "schema",
                        SCHEMA_FILTER_PROPERTY,
                        "database",
                        DATABASE_PROPERTY),
                    DEFAULT_COLUMN_LIMIT,
                    MAX_COLUMN_LIMIT),
                List.of("column")),
            this::findColumns));
  }

  private CallToolResult searchTables(ToolArguments arguments) {
    final SearchQuery query = SearchQuery.of(arguments.requiredString("query"));
    final int limit = arguments.optionalInt("limit", DEFAULT_SEARCH_LIMIT);
    final SearchResult result = catalog.tables().search(query, tableFilter(arguments), limit);
    return ToolResults.json(
        new SearchTablesOutput(
            result.total(), result.hits().stream().map(SearchTablesOutput.Hit::of).toList()));
  }

  private CallToolResult listTables(ToolArguments arguments) {
    final TableFilter scoped = tableFilter(arguments);
    final TableFilter filter =
        TableType.of(arguments.optionalChoice("type", TableType.allValues()))
            .map(scoped::withType)
            .orElse(scoped);
    final boolean includeDescription = arguments.optionalBoolean("includeDescription", false);
    final TableOrder order = arguments.optionalEnum("orderBy", TableOrder.class, TableOrder.NAME);
    final Page page = Page.read(arguments, Page.DEFAULT_LIMIT);
    final List<TableEntry> tables = catalog.tables().list(filter, order);
    return ToolResults.json(
        new ListTablesOutput(
            tables.size(),
            page.nextOffset(tables.size()),
            page.apply(tables).stream()
                .map(
                    table ->
                        ListTablesOutput.Table.of(
                            table, catalog.relations().counts(table), includeDescription))
                .toList()));
  }

  /**
   * 引数{@code database}・{@code schema}・{@code viewpoint}から、テーブルの絞り込みを組み立てるメソッド
   *
   * @throws InvalidToolArgumentException 指定した観点が見つからない場合
   */
  private TableFilter tableFilter(ToolArguments arguments) {
    final TableFilter filter = TableFilter.of(arguments.scope());
    return ViewpointResolver.resolve(catalog, arguments).map(filter::withViewpoint).orElse(filter);
  }

  private CallToolResult getTable(ToolArguments arguments) {
    final List<String> names = arguments.requiredStringList("table", MAX_TABLES);
    final Set<TableSection> sections = sections(arguments);
    final List<String> columns = arguments.optionalStringList("columns");
    if (names.size() > 1 && !columns.isEmpty()) {
      throw new InvalidToolArgumentException("引数columnsは、tableを1件指定した場合だけ使えます。");
    }
    final TableOutputBuilder builder = new TableOutputBuilder(catalog, sections, columns);
    final List<ObjectNode> outputs =
        resolveTables(arguments, names).stream().map(builder::build).toList();
    return outputs.size() == 1
        ? ToolResults.json(outputs.get(0))
        : ToolResults.json(new GetTableOutput(outputs));
  }

  /**
   * 指定された名前をすべて解決するメソッド
   *
   * @throws InvalidToolArgumentException 1件でも解決できない名前があれば、その名前すべての失敗（候補を含む）をまとめて返す
   */
  private List<TableEntry> resolveTables(ToolArguments arguments, List<String> names) {
    final String database = arguments.optionalString("database");
    final String schema = arguments.optionalString("schema");
    final List<TableEntry> tables = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    for (final String name : names) {
      try {
        tables.add(
            ObjectResolver.resolve(
                ObjectReference.of(database, schema, name),
                "テーブル",
                SEARCH_TABLES,
                catalog.tables()::lookup));
      } catch (InvalidToolArgumentException e) {
        errors.add(e.getMessage());
      }
    }
    if (!errors.isEmpty()) {
      throw new InvalidToolArgumentException(String.join("\n", errors));
    }
    return tables;
  }

  private CallToolResult findColumns(ToolArguments arguments) {
    final ColumnQuery query =
        ColumnQuery.of(
            arguments.requiredString("column"),
            arguments.optionalEnum("match", MatchMode.class, MatchMode.EXACT));
    final Page page = Page.read(arguments, DEFAULT_COLUMN_LIMIT);
    final List<ColumnHit> hits = catalog.tables().findColumns(query, arguments.scope());
    return ToolResults.json(
        new FindColumnsOutput(
            hits.size(),
            page.nextOffset(hits.size()),
            page.apply(hits).stream().map(FindColumnsOutput.Column::of).toList()));
  }

  private static Set<TableSection> sections(ToolArguments arguments) {
    final Set<TableSection> sections = EnumSet.noneOf(TableSection.class);
    for (final String name : arguments.optionalStringList("sections")) {
      sections.add(TableSection.of("sections", name));
    }
    return sections;
  }

  /** {@code get_table}で{@code table}を複数指定した場合の結果（1件の場合はスナップショットの1行をそのまま返す） */
  record GetTableOutput(List<ObjectNode> tables) {}

  /** {@code search_tables}の結果 */
  record SearchTablesOutput(int total, List<Hit> tables) {

    /** 一致した1テーブル（定義の全体は{@code get_table}で取得する前提で、概要だけを返す） */
    record Hit(
        String database,
        String schema,
        String name,
        String logicalName,
        String type,
        String description,
        List<String> matchedIn) {

      static Hit of(TableHit hit) {
        final TableEntry table = hit.table();
        return new Hit(
            table.key().database(),
            table.key().schema(),
            table.key().name(),
            table.logicalName(),
            table.type(),
            table.description(),
            hit.matchedIn());
      }
    }
  }

  /**
   * {@code list_tables}の結果
   *
   * @param nextOffset 続きを取得するときに指定する{@code offset}。続きが無い場合はnull
   */
  record ListTablesOutput(int total, Integer nextOffset, List<Table> tables) {

    /**
     * 1テーブルの概要
     *
     * @see RelationCounts incoming・outgoing・impactの数え方
     */
    record Table(
        String database,
        String schema,
        String name,
        String logicalName,
        String type,
        String description,
        int incoming,
        int outgoing,
        int impact) {

      static Table of(TableEntry table, RelationCounts counts, boolean includeDescription) {
        return new Table(
            table.key().database(),
            table.key().schema(),
            table.key().name(),
            table.logicalName(),
            table.type(),
            includeDescription ? table.description() : null,
            counts.incoming(),
            counts.outgoing(),
            counts.impact());
      }
    }
  }

  /**
   * {@code find_columns}の結果
   *
   * @param nextOffset 続きを取得するときに指定する{@code offset}。続きが無い場合はnull
   */
  record FindColumnsOutput(int total, Integer nextOffset, List<Column> columns) {

    /** 当てはまった1カラム */
    record Column(
        String database,
        String schema,
        String table,
        String tableLogicalName,
        String column,
        String logicalName,
        String type,
        boolean primaryKey,
        boolean notNull,
        String defaultValue,
        List<Reference> references) {

      static Column of(ColumnHit hit) {
        final TableEntry table = hit.table();
        return new Column(
            table.key().database(),
            table.key().schema(),
            table.key().name(),
            table.logicalName(),
            hit.column().name(),
            hit.column().logicalName(),
            hit.column().type(),
            hit.column().primaryKey(),
            hit.column().notNull(),
            hit.column().defaultValue(),
            hit.references().stream().map(Reference::of).toList());
      }
    }

    /**
     * カラムが参照している先
     *
     * @param table 参照先のテーブル（{@code スキーマ名.テーブル名}）
     * @param kind {@code foreignKey}または{@code logicalRelation}
     */
    record Reference(String table, String column, String kind) {

      static Reference of(ColumnHit.ColumnReference reference) {
        return new Reference(
            reference.table().qualifiedName(),
            reference.column(),
            RelationTools.kindName(reference.kind()));
      }
    }
  }
}
