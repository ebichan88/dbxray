package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.ToolSpecifications.DATABASE_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.integerProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.objectSchema;
import static com.dbxray.mcp.tool.ToolSpecifications.readOnlyTool;
import static com.dbxray.mcp.tool.ToolSpecifications.stringOrArrayProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.stringProperty;

import com.dbxray.mcp.catalog.ObjectReference;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SearchScope;
import com.dbxray.mcp.catalog.SimilarTable;
import com.dbxray.mcp.catalog.SimilarTableQuery;
import com.dbxray.mcp.catalog.SimilarTables;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TableFilter;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;

/** 構造（カラム名）が似ているテーブルを探すツール（{@code find_similar_tables}） */
final class SimilarTableTools {

  static final String FIND_SIMILAR_TABLES = "find_similar_tables";

  private static final int DEFAULT_MIN_SHARED = 3;
  private static final int MAX_MIN_SHARED = 20;
  private static final int DEFAULT_MIN_SIMILARITY = 50;
  private static final int MIN_MIN_SIMILARITY = 10;
  private static final int MAX_MIN_SIMILARITY = 100;
  private static final int DEFAULT_LIMIT = 10;
  private static final int MAX_LIMIT = 50;

  /** {@code columns}に指定できるカラム名の件数の上限 */
  private static final int MAX_COLUMNS = 100;

  /** 1テーブルについて、基準・相手にだけあるカラムを返す件数の上限。幅の広いテーブルで結果が膨らまないようにする */
  private static final int MAX_LISTED_COLUMNS = 20;

  private final SchemaCatalog catalog;

  SimilarTableTools(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  /** ツールの一覧 */
  List<SyncToolSpecification> specifications() {
    return List.of(
        readOnlyTool(
            FIND_SIMILAR_TABLES,
            "構造の似たテーブル検索",
            "構造（カラム名）が似ているテーブルを探す。tableに既存のテーブルを指定するか、columnsに設計中のカラム名を指定する"
                + "（どちらか一方）。新しいテーブルを作る前に同じ目的の既存テーブルが無いか確かめるときや、"
                + "履歴・バックアップ・複製のテーブルを見つけるときに使う。"
                + "多くのテーブルが持つid・created_atのような汎用のカラムは、類似の根拠にならないよう重みを下げて比べる。"
                + "結果は、類似度（%。100はカラムが同じ場合だけ）、種別（identical: カラムが同じ、"
                + "superset: 相手が基準の全カラムを持ち追加がある、subset: 相手は基準の一部だけ、overlap: それ以外）、"
                + "基準にだけある・相手にだけあるカラム、同名で型の異なるカラム（tableを指定した場合だけ。"
                + "difference: typeは型が異なる、sizeは長さ・精度だけが異なる）を返す。"
                + "基準と外部キー・論理リレーションで直接つながるテーブルにはrelatedToBaseが付く"
                + "（親子は互いのキーを持つため似て見えることがある。履歴・拡張のテーブルは基準を参照していることが多い）。"
                + "構造の類似であり、同じ意味のテーブルかどうかはget_tableの説明・定義を見て判断する。"
                + "対象は区分がtableのテーブル。tableを指定した場合は、同じDBのテーブルから探す",
            objectSchema(
                Map.of(
                    "table",
                    stringProperty(
                        TableTools.TABLE_DESCRIPTION
                            + "。同名のテーブルが複数のスキーマにある場合は、スキーマ名.テーブル名の形で指定する。"
                            + "columnsとどちらか一方を指定する"),
                    "columns",
                    stringOrArrayProperty(
                        "設計中のテーブルのカラム名（物理名。大文字小文字を区別しない。最大" + MAX_COLUMNS + "件）。tableとどちらか一方を指定する"),
                    "schema",
                    stringProperty("探す範囲をスキーマ名で絞り込む場合に指定する（基準のテーブルの特定には使わない）"),
                    "database",
                    DATABASE_PROPERTY,
                    "viewpoint",
                    TableTools.VIEWPOINT_PROPERTY,
                    "minShared",
                    integerProperty(
                        "共通のカラム数の下限（既定" + DEFAULT_MIN_SHARED + "）。小さなテーブルの偶然の一致を除く",
                        1,
                        MAX_MIN_SHARED),
                    "minSimilarity",
                    integerProperty(
                        "類似度（%）の下限（既定" + DEFAULT_MIN_SIMILARITY + "）。見つからないときは小さくする",
                        MIN_MIN_SIMILARITY,
                        MAX_MIN_SIMILARITY),
                    "limit",
                    integerProperty("返す件数の上限（既定" + DEFAULT_LIMIT + "）", 1, MAX_LIMIT)),
                List.of()),
            this::findSimilarTables));
  }

  private CallToolResult findSimilarTables(ToolArguments arguments) {
    if (arguments.isPresent("table") == arguments.isPresent("columns")) {
      throw new InvalidToolArgumentException("引数tableとcolumnsは、どちらか一方を指定してください。");
    }
    final SimilarTableQuery query = query(arguments);
    final SimilarTables found =
        catalog
            .tables()
            .findSimilar(
                query,
                searchFilter(query, arguments),
                arguments.optionalInt("limit", DEFAULT_LIMIT));
    return ToolResults.json(FindSimilarTablesOutput.of(query, found));
  }

  /** 引数{@code table}（既存のテーブル）か{@code columns}（カラム名）のどちらかを基準にした条件 */
  private SimilarTableQuery query(ToolArguments arguments) {
    final int minShared = arguments.optionalInt("minShared", DEFAULT_MIN_SHARED);
    final int minSimilarity = arguments.optionalInt("minSimilarity", DEFAULT_MIN_SIMILARITY);
    if (arguments.isPresent("columns")) {
      return SimilarTableQuery.ofColumns(
          arguments.requiredStringList("columns", MAX_COLUMNS), minShared, minSimilarity);
    }
    return SimilarTableQuery.ofTable(resolveBase(arguments), minShared, minSimilarity);
  }

  /**
   * 基準のテーブルを、引数{@code table}（{@code スキーマ名.テーブル名}も可）と{@code database}で解決する。
   * {@code schema}は探す範囲に使うため、ここでは使わない
   */
  private TableEntry resolveBase(ToolArguments arguments) {
    return ObjectResolver.resolve(
        ObjectReference.of(
            arguments.optionalString("database"), null, arguments.requiredString("table")),
        "テーブル",
        TableTools.SEARCH_TABLES,
        "tableをスキーマ名.テーブル名の形（DBが異なる場合はdatabaseも）で指定してください。",
        catalog.tables()::lookup);
  }

  /** 探す範囲（DB・スキーマ・観点）。既存のテーブルを基準にした場合は、基準と同じDBに限る */
  private TableFilter searchFilter(SimilarTableQuery query, ToolArguments arguments) {
    final SearchScope scope =
        query.base() == null
            ? arguments.scope()
            : new SearchScope(query.base().key().database(), arguments.optionalString("schema"));
    final TableFilter filter = TableFilter.of(scope);
    return ViewpointResolver.resolve(catalog, arguments).map(filter::withViewpoint).orElse(filter);
  }

  /**
   * {@code find_similar_tables}の結果
   *
   * @param base 基準にしたテーブル（{@code スキーマ名.テーブル名}）。columnsを指定した場合はnull
   * @param total 条件を満たしたテーブルの総数（件数の上限で切り捨てる前の数）
   * @param message 条件を満たすテーブルが無い場合に、条件をゆるめる方法を示す。ある場合はnull
   */
  record FindSimilarTablesOutput(String base, int total, List<Table> tables, String message) {

    static FindSimilarTablesOutput of(SimilarTableQuery query, SimilarTables found) {
      return new FindSimilarTablesOutput(
          query.base() == null ? null : query.base().key().qualifiedName(),
          found.total(),
          found.tables().stream().map(Table::of).toList(),
          found.total() == 0 ? messageWhenNothingFound(query) : null);
    }

    private static String messageWhenNothingFound(SimilarTableQuery query) {
      if (query.columns().isEmpty()) {
        return "基準のテーブルにカラムが無いため、比べられません。";
      }
      if (query.hasFewerColumnsThanMinShared()) {
        return "基準のカラムは"
            + query.columns().size()
            + "個で、minShared（"
            + query.minShared()
            + "）に満たないため、条件を満たすテーブルはありません。minSharedを"
            + query.columns().size()
            + "以下にしてください。";
      }
      return "条件を満たすテーブルはありません。minSharedまたはminSimilarityを小さくすると、似ている度合いの低いテーブルも探せます。";
    }

    /**
     * 基準に似ている1テーブル
     *
     * @param name {@code スキーマ名.テーブル名}
     * @param similarity 類似度（%）
     * @param kind {@code identical}・{@code superset}・{@code subset}・{@code overlap}
     * @param sharedColumns 共通のカラムの数
     * @param onlyInBaseOmitted {@code onlyInBase}を上限で切り捨てた場合の、省いたカラムの数。切り捨てていない場合はnull
     * @param onlyInOtherOmitted {@code onlyInOther}を上限で切り捨てた場合の、省いたカラムの数。切り捨てていない場合はnull
     * @param relatedToBase 基準と外部キー・論理リレーションで直接つながる場合はtrue。つながらない場合はnull
     */
    record Table(
        String database,
        String name,
        String logicalName,
        int similarity,
        String kind,
        int sharedColumns,
        List<String> onlyInBase,
        Integer onlyInBaseOmitted,
        List<String> onlyInOther,
        Integer onlyInOtherOmitted,
        List<TypeMismatch> typeMismatches,
        Boolean relatedToBase) {

      static Table of(SimilarTable similar) {
        final TableEntry table = similar.table();
        return new Table(
            table.key().database(),
            table.key().qualifiedName(),
            table.logicalName(),
            similar.similarity(),
            similar.kind().value(),
            similar.sharedColumns(),
            listed(similar.onlyInBase()),
            omitted(similar.onlyInBase()),
            listed(similar.onlyInOther()),
            omitted(similar.onlyInOther()),
            similar.typeMismatches().stream().map(TypeMismatch::of).toList(),
            similar.relatedToBase() ? Boolean.TRUE : null);
      }

      private static List<String> listed(List<String> columns) {
        return columns.subList(0, Math.min(columns.size(), MAX_LISTED_COLUMNS));
      }

      private static Integer omitted(List<String> columns) {
        return columns.size() > MAX_LISTED_COLUMNS ? columns.size() - MAX_LISTED_COLUMNS : null;
      }
    }

    /**
     * 共通のカラムで型が異なるもの
     *
     * @param column 基準でのカラム名
     * @param base 基準の型
     * @param other 相手の型
     * @param difference {@code type}（型が異なる）・{@code size}（長さ・精度等だけが異なる）
     */
    record TypeMismatch(String column, String base, String other, String difference) {

      static TypeMismatch of(SimilarTable.TypeMismatch mismatch) {
        return new TypeMismatch(
            mismatch.column(),
            mismatch.baseType(),
            mismatch.otherType(),
            mismatch.difference().value());
      }
    }
  }
}
