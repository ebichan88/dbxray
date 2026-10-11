package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.List;

/**
 * 関数・プロシージャの定義から切り出した、解析する本体
 *
 * @param status 本体を切り出せた場合は{@link TableUsageStatus#ANALYZED}、それ以外は解析しない理由
 * @param language 解析しない言語の名前（{@link TableUsageStatus#UNSUPPORTED_LANGUAGE}の場合だけ。それ以外は空文字）
 * @param segments 本体の字句（空白・コメントを除いたもの）。区別できなかった同名のサブプログラム（Oracle）は本体ごとに分けて持つ
 *     （文の状態が本体をまたがないよう、本体ごとに走査するため）
 * @param incomplete 閉じていない文字列・コメント等があり、定義の終わりまで字句を読み取れなかったか
 * @param overloadsMerged 同名のサブプログラムの本体を区別できず、すべての本体を持つか（Oracle）
 * @param searchPath 関数の属性の{@code SET search_path}に並ぶスキーマ（PostgreSQL。本体の中のSET文・{@code
 *     set_config}は含まない。{@code $user}・{@code pg_catalog}・{@code pg_temp}を除く。指定が無ければ空）
 * @param invokerRights 実行者権限（{@code AUTHID CURRENT_USER}）か（Oracle）
 */
record FunctionBody(
    TableUsageStatus status,
    String language,
    List<List<SqlToken>> segments,
    boolean incomplete,
    boolean overloadsMerged,
    List<String> searchPath,
    boolean invokerRights) {

  /** リストは変更不可な複製として保持する */
  FunctionBody {
    segments = segments.stream().map(List::copyOf).toList();
    searchPath = List.copyOf(searchPath);
  }

  /**
   * 解析しない場合の結果を生成するメソッド
   *
   * @param status 解析しない理由
   * @throws IllegalArgumentException {@link
   *     TableUsageStatus#UNSUPPORTED_LANGUAGE}の場合（言語の名前を持てる{@link #unsupportedLanguage}を使う）
   */
  static FunctionBody notAnalyzable(TableUsageStatus status) {
    if (status == TableUsageStatus.UNSUPPORTED_LANGUAGE) {
      throw new IllegalArgumentException("Use unsupportedLanguage for UNSUPPORTED_LANGUAGE");
    }
    return new FunctionBody(status, "", List.of(), false, false, List.of(), false);
  }

  /** 言語が対象外のため解析しない場合の結果 */
  static FunctionBody unsupportedLanguage(String language) {
    return new FunctionBody(
        TableUsageStatus.UNSUPPORTED_LANGUAGE, language, List.of(), false, false, List.of(), false);
  }

  /** PostgreSQLの本体 */
  static FunctionBody postgres(List<SqlToken> tokens, boolean incomplete, List<String> searchPath) {
    return new FunctionBody(
        TableUsageStatus.ANALYZED, "", List.of(tokens), incomplete, false, searchPath, false);
  }

  /** Oracleの本体 */
  static FunctionBody oracle(
      List<List<SqlToken>> segments,
      boolean incomplete,
      boolean overloadsMerged,
      boolean invokerRights) {
    return new FunctionBody(
        TableUsageStatus.ANALYZED,
        "",
        segments,
        incomplete,
        overloadsMerged,
        List.of(),
        invokerRights);
  }
}
