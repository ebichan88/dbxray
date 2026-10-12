package com.dbxray.domain.model.tableusage;

/** 関数・プロシージャの利用テーブルの抽出の状態 */
public enum TableUsageStatus {
  /** 抽出を行わない実行（{@code --preview}を指定しない実行・差分検知） */
  NOT_ANALYZED("not_analyzed"),
  /** 定義本体を解析した（利用しているテーブルが無い場合を含む） */
  ANALYZED("analyzed"),
  /** 言語がSQL・PL/pgSQL・PL/SQL以外のため解析しない（plpython3u・C・Javaの呼び出し仕様等） */
  UNSUPPORTED_LANGUAGE("unsupported_language"),
  /** 解析する定義本体が無い（権限が無くOracleのALL_SOURCEが見えない・本体の無い定義等） */
  NO_DEFINITION("no_definition"),
  /** 定義本体がwrap（難読化）されている（Oracle） */
  WRAPPED("wrapped"),
  /** パッケージ本体からサブプログラムの本体を見つけられなかった（Oracle） */
  SUBPROGRAM_NOT_FOUND("subprogram_not_found");

  private final String id;

  TableUsageStatus(String id) {
    this.id = id;
  }

  /**
   * 参考情報（JSON）で状態を示す名前を取得するメソッド<br>
   * MCPサーバーが読む値のため、定数の名前を変えても変えない（変えると形式の互換が壊れる）
   *
   * @return {@code analyzed}・{@code unsupported_language}等
   */
  public String id() {
    return id;
  }
}
