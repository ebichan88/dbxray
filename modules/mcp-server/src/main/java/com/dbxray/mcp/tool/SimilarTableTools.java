package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.ToolSpecifications.DATABASE_PROPERTY;
import static com.dbxray.mcp.tool.ToolSpecifications.integerProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.objectSchema;
import static com.dbxray.mcp.tool.ToolSpecifications.readOnlyTool;
import static com.dbxray.mcp.tool.ToolSpecifications.stringOrArrayProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.stringProperty;

import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.SearchScope;
import com.dbxray.mcp.catalog.SimilarTable;
import com.dbxray.mcp.catalog.SimilarTableQuery;
import com.dbxray.mcp.catalog.SimilarTables;
import com.dbxray.mcp.catalog.TableEntry;
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
                + "全テーブルが持つid・created_atのような汎用のカラムが、類似の根拠にならないよう重みを下げて比べる。"
                + "結果は、類似度（%）、種別（identical: カラムが同じ、superset: 相手が基準の全カラムを持ち追加がある、"
                + "subset: 相手は基準の一部だけ、overlap: それ以外）、基準にだけある・相手にだけあるカラム、"
                + "同名で型の異なるカラム（tableを指定した場合だけ）を返す。"
                + "構造の類似であり、同じ意味のテーブルかどうかはget_tableの説明・定義を見て判断する。"
                + "対象は区分がtableのテーブルで、基準と外部キー・論理リレーションで直接つながるテーブルは含めない"
                + "（親子は互いのキーを持つため似て見える）。tableを指定した場合は、同じDBのテーブルから探す",
            objectSchema(
                Map.of(
                    "table",
                    stringProperty(TableTools.TABLE_DESCRIPTION + "。columnsとどちらか一方を指定する"),
                    "columns",
                    stringOrArrayProperty(
                        "設計中のテーブルのカラム名（物理名。大文字小文字を区別しない。最大"
                            + MAX_COLUMNS
                            + "件）。tableとどちらか一方を指定する"),
                    "schema",
                    stringProperty(
                        "tableを指定した場合: 同名のテーブルが複数のスキーマにあるときに、基準のスキーマを指定する"
                            + "（探す範囲は同じDBの全スキーマ）。columnsを指定した場合: 探す範囲をスキーマで絞り込む"),
                    "database",
                    DATABASE_PROPERTY,
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
    final int minShared = arguments.optionalInt("minShared", DEFAULT_MIN_SHARED);
    final int minSimilarity = arguments.optionalInt("minSimilarity", DEFAULT_MIN_SIMILARITY);
    final int limit = arguments.optionalInt("limit", DEFAULT_LIMIT);
    if (arguments.isPresent("columns")) {
      final SimilarTables found =
          catalog
              .tables()
              .findSimilar(
                  SimilarTableQuery.ofColumns(
                      arguments.requiredStringList("columns", MAX_COLUMNS), minShared, minSimilarity),
                  arguments.scope(),
                  limit);
      return ToolResults.json(FindSimilarTablesOutput.of(null, found));
    }
    final TableEntry base =
        ObjectResolver.resolve(
            arguments, "table", "テーブル", TableTools.SEARCH_TABLES, catalog.tables()::lookup);
    final SimilarTables found =
        catalog
            .tables()
            .findSimilar(
                SimilarTableQuery.ofTable(base, minShared, minSimilarity),
                new SearchScope(base.key().database(), ""),
                limit);
    return ToolResults.json(FindSimilarTablesOutput.of(base.key().qualifiedName(), found));
  }

  /**
   * {@code find_similar_tables}の結果
   *
   * @param base 基準にしたテーブル（{@code スキーマ名.テーブル名}）。columnsを指定した場合はnull
   * @param total 条件を満たしたテーブルの総数（件数の上限で切り捨てる前の数）
   * @param message 条件を満たすテーブルが無い場合に、条件をゆるめる方法を示す。ある場合はnull
   */
  record FindSimilarTablesOutput(String base, int total, List<Table> tables, String message) {

    static FindSimilarTablesOutput of(String base, SimilarTables found) {
      return new FindSimilarTablesOutput(
          base,
          found.total(),
          found.tables().stream().map(Table::of).toList(),
          found.total() == 0
              ? "条件を満たすテーブルはありません。minSharedまたはminSimilarityを小さくすると、似ている度合いの低いテーブルも探せます。"
              : null);
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
        List<TypeMismatch> typeMismatches) {

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
            similar.typeMismatches().stream().map(TypeMismatch::of).toList());
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
     */
    record TypeMismatch(String column, String base, String other) {

      static TypeMismatch of(SimilarTable.TypeMismatch mismatch) {
        return new TypeMismatch(mismatch.column(), mismatch.baseType(), mismatch.otherType());
      }
    }
  }
}
