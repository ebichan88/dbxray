package com.dbxray.domain.model.tableusage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

  /**
   * リストは変更不可な複製として保持する
   *
   * @throws IllegalArgumentException 状態と合わない値を持つ場合（言語の名前は
   *     {@link TableUsageStatus#UNSUPPORTED_LANGUAGE}だけ、
   *     テーブル・動的SQL・不完全・オーバーロードの印は{@link TableUsageStatus#ANALYZED}だけが持てる）
   */
  public FunctionTableUsage {
    if (!language.isEmpty() && status != TableUsageStatus.UNSUPPORTED_LANGUAGE) {
      throw new IllegalArgumentException("Only UNSUPPORTED_LANGUAGE has a language");
    }
    if (status != TableUsageStatus.ANALYZED
        && (!tables.isEmpty() || !dynamicSql.isEmpty() || incomplete || overloadsMerged)) {
      throw new IllegalArgumentException("Only ANALYZED has tables, dynamic SQL or flags");
    }
    tables = List.copyOf(tables);
    dynamicSql = List.copyOf(dynamicSql);
  }

  /** 抽出を行わない実行の結果 */
  public static FunctionTableUsage notAnalyzed() {
    return new FunctionTableUsage(
        TableUsageStatus.NOT_ANALYZED, "", List.of(), List.of(), false, false);
  }

  /**
   * 解析できなかった結果を生成するメソッド
   *
   * @param status 解析できなかった理由
   * @throws IllegalArgumentException 理由でない状態の場合
   *     （{@link TableUsageStatus#UNSUPPORTED_LANGUAGE}は言語の名前を持てる{@link #unsupportedLanguage}を、
   *     {@link TableUsageStatus#NOT_ANALYZED}は{@link #notAnalyzed}を、
   *     {@link TableUsageStatus#ANALYZED}は{@link #analyzed}を使う）
   */
  public static FunctionTableUsage notAnalyzable(TableUsageStatus status) {
    if (status == TableUsageStatus.UNSUPPORTED_LANGUAGE) {
      throw new IllegalArgumentException("Use unsupportedLanguage for UNSUPPORTED_LANGUAGE");
    }
    if (status == TableUsageStatus.NOT_ANALYZED || status == TableUsageStatus.ANALYZED) {
      throw new IllegalArgumentException(status + " is not a reason for being unable to analyze");
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
   * @return 決まり方（{@link SchemaResolution}の宣言順）をキー、
   *     その行（{@link #tables}の並び順）を値とするマップ。スキーマが決まらない行が無ければ空
   */
  public Map<SchemaResolution, List<TableUsage>> schemaUndeterminedTables() {
    return tables.stream()
        .filter(table -> !table.schemaDetermined())
        .collect(
            Collectors.groupingBy(
                TableUsage::resolution,
                () -> new EnumMap<>(SchemaResolution.class),
                Collectors.toList()));
  }

  /**
   * 抽出を行った実行の結果か判定するメソッド
   *
   * @return {@link TableUsageStatus#NOT_ANALYZED}以外の場合はtrue
   */
  public boolean isAttempted() {
    return status != TableUsageStatus.NOT_ANALYZED;
  }
}
