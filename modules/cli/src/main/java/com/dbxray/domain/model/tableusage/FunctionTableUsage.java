package com.dbxray.domain.model.tableusage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 1関数・プロシージャ分の利用テーブル（定義本体から機械的に抽出した参考値）
 *
 * @param language 解析しない言語の名前（{@link TableUsageStatus#UNSUPPORTED_LANGUAGE}の場合だけ。それ以外は空文字）
 * @param tables 利用しているテーブル（スキーマが決まったものをスキーマ名・テーブル名の順に、その後に決まらないものをテーブル名の順に並べる）
 * @param dynamicSql 定義本体に含まれる動的SQLの種類（最初に現れた順）
 * @param incomplete 閉じていない文字列・コメント等があり、定義本体の終わりまで字句を読み取れなかったか
 * @param overloadsMerged 同名のサブプログラム（オーバーロード）の本体を区別できず、すべての本体からまとめて抽出したか（Oracle）
 */
public record FunctionTableUsage(
    TableUsageStatus status,
    String language,
    List<TableUsage> tables,
    List<DynamicSqlKind> dynamicSql,
    boolean incomplete,
    boolean overloadsMerged) {

  /** リストは変更不可な複製として保持する */
  public FunctionTableUsage {
    tables = List.copyOf(tables);
    dynamicSql = List.copyOf(dynamicSql);
  }

  /** 抽出を行わない実行の結果 */
  public static FunctionTableUsage notAnalyzed() {
    return notAnalyzable(TableUsageStatus.NOT_ANALYZED);
  }

  /**
   * 解析しなかった結果を生成するメソッド
   *
   * @param status 解析しなかった理由
   * @throws IllegalArgumentException {@link
   *     TableUsageStatus#UNSUPPORTED_LANGUAGE}の場合（言語の名前を持てる{@link #unsupportedLanguage}を使う）
   */
  public static FunctionTableUsage notAnalyzable(TableUsageStatus status) {
    if (status == TableUsageStatus.UNSUPPORTED_LANGUAGE) {
      throw new IllegalArgumentException("Use unsupportedLanguage for UNSUPPORTED_LANGUAGE");
    }
    return new FunctionTableUsage(status, "", List.of(), List.of(), false, false);
  }

  /** 言語がSQL・PL/pgSQL・PL/SQL以外のため解析しなかった結果 */
  public static FunctionTableUsage unsupportedLanguage(String language) {
    return new FunctionTableUsage(
        TableUsageStatus.UNSUPPORTED_LANGUAGE, language, List.of(), List.of(), false, false);
  }

  /** 定義本体を解析した結果 */
  public static FunctionTableUsage analyzed(
      List<TableUsage> tables,
      List<DynamicSqlKind> dynamicSql,
      boolean incomplete,
      boolean overloadsMerged) {
    return new FunctionTableUsage(
        TableUsageStatus.ANALYZED, "", tables, dynamicSql, incomplete, overloadsMerged);
  }

  /**
   * スキーマが決まらない名前の行を、決まり方ごとにまとめるメソッド
   *
   * @return 決まり方（{@link SchemaResolution}の宣言順）をキー、その行（{@link
   *     #tables}の並び順）を値とするマップ。スキーマが決まらない行が無ければ空
   */
  public Map<SchemaResolution, List<TableUsage>> schemaUndeterminedTables() {
    final Map<SchemaResolution, List<TableUsage>> byResolution =
        new EnumMap<>(SchemaResolution.class);
    tables.stream()
        .filter(table -> !table.schemaDetermined())
        .forEach(
            table ->
                byResolution
                    .computeIfAbsent(table.resolution(), resolution -> new ArrayList<>())
                    .add(table));
    return byResolution;
  }

  /**
   * 抽出を行った実行の結果か（定義書の節・参考情報を出力するか）判定するメソッド
   *
   * @return {@link TableUsageStatus#NOT_ANALYZED}以外の場合はtrue
   */
  public boolean isAttempted() {
    return status != TableUsageStatus.NOT_ANALYZED;
  }
}
