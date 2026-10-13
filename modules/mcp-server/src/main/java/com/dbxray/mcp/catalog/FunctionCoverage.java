package com.dbxray.mcp.catalog;

/**
 * 関数の参考情報で、テーブルを使う関数を調べられる範囲<br>
 * 同じDBの関数・プロシージャ（オーバーロードはそれぞれ）を、参考情報の状態で分けて数える。
 * {@code analyzed}・{@code unsupported}・{@code noInsight}・{@code stale}の合計が{@code total}になる
 *
 * @param total 関数・プロシージャの数
 * @param analyzed 参考情報があり、定義本体を解析できたもの
 * @param unsupported 参考情報はあるが、解析しなかったもの（対象外の言語・難読化・定義本体が無い等）
 * @param noInsight 参考情報が無いもの（cliを{@code --preview}を付けずに出力した場合等）
 * @param stale 参考情報はあるが、抽出元の定義本体がスナップショットと異なるもの
 * @param withDynamicSql {@code analyzed}のうち、動的SQLを含むもの（動的SQLの中の参照は含まれない）
 * @param incomplete {@code analyzed}のうち、定義本体を最後まで読めなかったもの
 * @param triggersWithoutFunction 実行する関数名を持たず、本体を直接持つトリガーの数（Oracle）。本体の中の参照は参考情報の対象外
 */
public record FunctionCoverage(
    int total,
    int analyzed,
    int unsupported,
    int noInsight,
    int stale,
    int withDynamicSql,
    int incomplete,
    int triggersWithoutFunction) {}
