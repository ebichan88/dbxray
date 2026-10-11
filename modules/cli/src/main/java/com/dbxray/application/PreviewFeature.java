package com.dbxray.application;

/**
 * {@code --preview}で有効にする、精度・出力の形を利用者の声で固めている途中の機能<br>
 * プレビューの機能の出力は互換性の範囲に含めず、予告なく変えることがある（CONTRIBUTING.md）。 スナップショット（{@code --check}の比較対象）の内容は変えない
 */
public enum PreviewFeature {
  /** 関数・プロシージャの定義書の「利用しているテーブル」と、その参考情報（{@code functionTableUsages.json}） */
  FUNCTION_TABLE_USAGE("functionTableUsage");

  private final String id;

  PreviewFeature(String id) {
    this.id = id;
  }

  /**
   * ログ・ドキュメントで機能を示す名前を取得するメソッド
   *
   * @return 機能の名前（例: {@code functionTableUsage}）
   */
  public String id() {
    return id;
  }
}
