package com.dbxray.domain.model.tableusage;

/** 関数・プロシージャの利用テーブルの抽出の状態 */
public enum TableUsageStatus {
  /** 抽出を行わない実行（{@code --preview}を指定しない実行・差分検知） */
  NOT_ANALYZED,
  /** 定義本体を解析した（利用しているテーブルが無い場合を含む） */
  ANALYZED,
  /** 言語がSQL・PL/pgSQL・PL/SQL以外のため解析しない（plpython3u・C・Javaの呼び出し仕様等） */
  UNSUPPORTED_LANGUAGE,
  /** 定義本体を取得できなかった（権限が無くOracleのALL_SOURCEが見えない等） */
  NO_DEFINITION,
  /** 定義本体がwrap（難読化）されている（Oracle） */
  WRAPPED,
  /** パッケージ本体からサブプログラムの本体を見つけられなかった（Oracle） */
  SUBPROGRAM_NOT_FOUND
}
