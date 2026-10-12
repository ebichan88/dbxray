package com.dbxray.mcp.catalog;

/**
 * スナップショットの{@code functions.jsonl}の1行（1関数・プロシージャ）
 *
 * @param key 同名の関数（オーバーロード）は同じキーになる
 * @param kind 種別（FUNCTION/PROCEDURE）。未設定の場合は空文字
 * @param arguments 引数（デフォルト値を含む）。引数が無い場合は空文字
 * @param result 戻り値の型。プロシージャの場合は空文字
 * @param language 記述言語（plpgsql/sql等）。未設定の場合は空文字
 * @param json スナップショットの1行そのもの（定義本体を含む。AIのコンテキストを圧迫するため、ツールの結果では除く）
 * @param definitionHash 定義本体のSHA-256（16進数の小文字。参考情報が同じ定義本体から抽出したものか照らすため）
 */
public record FunctionEntry(
    ObjectKey key,
    String kind,
    String arguments,
    String result,
    String language,
    String json,
    String definitionHash) {

  /** 未設定の項目（null）を空文字へ揃える */
  public FunctionEntry {
    kind = TextValues.orEmpty(kind);
    arguments = TextValues.orEmpty(arguments);
    result = TextValues.orEmpty(result);
    language = TextValues.orEmpty(language);
    definitionHash = TextValues.orEmpty(definitionHash);
  }
}
