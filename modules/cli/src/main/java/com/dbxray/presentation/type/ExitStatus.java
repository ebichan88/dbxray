package com.dbxray.presentation.type;

/**
 * 処理の終了状態と、プロセスの終了コードを対応付ける列挙型<br>
 * {@code diff}コマンドと同じく、差分の有無と処理の失敗を終了コードで区別できるようにする（CIのジョブで「差分あり」と「比較自体の失敗」を見分けられるようにするため）。
 *
 * <ul>
 *   <li>0: 成功（{@code --check}で差分なしを含む）
 *   <li>1: 検査して見つかった（{@code --check}で差分あり）。将来ほかのモードで同種の結果が増えても1を共通で使う
 *   <li>2以上: 失敗。POSIXの{@code diff}・{@code cmp}と同じく、1つの値ではなく範囲で定め、利用者には2以上かで判定してもらう。
 *       失敗の種類を分ける場合は3以上の値を追加する（既存の判定を壊さないため）
 * </ul>
 *
 * なお、JVMが起動できない場合や{@code main()}に入る前の失敗は、このツールが捕捉する前にJavaの仕様で1になる
 */
public enum ExitStatus {
  /** 処理成功（通常実行の完了、{@code --check}で差分なし） */
  SUCCESS(0),
  /** {@code --check}で差分が見つかった */
  DIFFERENCE_FOUND(1),
  /** 処理失敗（利用者が直せる誤り・想定外の失敗のいずれも）。失敗は2以上と定めており、現在はこの値のみを返す */
  FAILURE(2);

  private final int code;

  ExitStatus(int code) {
    this.code = code;
  }

  /**
   * プロセスの終了コードを返却する。
   *
   * @return プロセスの終了コード
   */
  public int code() {
    return code;
  }
}
