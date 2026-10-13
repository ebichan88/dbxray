package com.dbxray.shared.exception;

/**
 * 利用者が設定・入力・実行環境を見直せば解消する誤りを表す例外クラス<br>
 * 設定の誤り、サイドカーYAMLの構文誤り、DBに接続できない等、このツールの不具合ではない失敗を表す。
 * 検知した箇所で、何を直せばよいかが分かるメッセージを付けて投げる（元の例外がある場合は原因として渡す）。<br>
 * エントリーポイントの境界は、この例外かそれ以外（想定外の失敗）かだけで表示・ログ出力を切り替えるため、呼び出し側は個々の失敗の種類を知らなくてよい。
 *
 * <ul>
 *   <li>投げるのは、入口（CLI引数・設定・出力先の検証）と、利用者の入力・実行環境に触れるインフラ（DBへの接続、サイドカーYAMLの読み込み）だけとする
 *   <li>ドメイン層・アプリケーション層では投げない（利用者が直せる入力の誤りは、ユースケースを呼ぶ前に入口で検証する）
 * </ul>
 *
 * 入口とインフラの双方から投げるため、どの層からも依存できるレイヤーの外（{@code shared.exception}）に置く。
 * ドメインの概念ではないため、ドメイン層には置かない
 */
public class UserCorrectableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 誤りの内容と、何を直せばよいか（利用者向けのメッセージ）
   */
  public UserCorrectableException(String message) {
    super(message);
  }

  /**
   * @param message 誤りの内容と、何を直せばよいか（利用者向けのメッセージ）
   * @param cause 誤りの原因となった例外
   */
  public UserCorrectableException(String message, Throwable cause) {
    super(message, cause);
  }
}
