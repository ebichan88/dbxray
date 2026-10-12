package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 参考情報の{@code functionTableUsages.json}の関数・プロシージャ1つ分（cliの{@code --preview}で出力する、定義本体から機械的に抽出した参考値）
 *
 * @param key 関数のキー（オーバーロードは同じキーになり、{@code arguments}で区別する）
 * @param arguments 引数（スナップショットの{@code arguments}と同じ表記）。引数が無い場合は空文字
 * @param definitionHash 抽出した定義本体のSHA-256（16進数の小文字）。定義本体が無かった場合は空文字
 * @param status 解析の状態（{@code analyzed}・{@code unsupported_language}等）
 * @param language 解析しなかった言語の名前（{@code unsupported_language}の場合だけ。それ以外は空文字）
 * @param tables 利用しているテーブル
 * @param dynamicSql 定義本体に含まれる動的SQLの種類
 * @param incomplete 定義本体を最後まで読めなかったか
 * @param overloadsMerged 同名のサブプログラムの本体をまとめて抽出したか（Oracle）
 */
public record FunctionTableUsageEntry(
    ObjectKey key,
    String arguments,
    String definitionHash,
    String status,
    String language,
    List<UsedTable> tables,
    List<String> dynamicSql,
    boolean incomplete,
    boolean overloadsMerged) {

  /** 解析した状態の値 */
  public static final String ANALYZED = "analyzed";

  /** 未設定の項目（null）を空文字・空のリストへ揃え、リストは複製して変更できないようにする */
  public FunctionTableUsageEntry {
    arguments = TextValues.orEmpty(arguments);
    definitionHash = TextValues.orEmpty(definitionHash);
    status = TextValues.orEmpty(status);
    language = TextValues.orEmpty(language);
    tables = tables == null ? List.of() : List.copyOf(tables);
    dynamicSql = dynamicSql == null ? List.of() : List.copyOf(dynamicSql);
  }

  /**
   * 利用しているテーブル1件
   *
   * @param schema スキーマ名。定義からスキーマが決まらない名前は空文字
   * @param schemaResolution スキーマが決まらない名前が何で決まるか（{@code search_path}・{@code current_user}・{@code
   *     synonym}）。スキーマが決まった場合は空文字
   * @param schemaCandidates スキーマが決まらない名前の、同じ名前の出力対象のテーブルがあるスキーマ
   * @param operations 操作（{@code C}・{@code R}・{@code U}・{@code D}の順）
   */
  public record UsedTable(
      String schema,
      String name,
      String schemaResolution,
      List<String> schemaCandidates,
      List<String> operations) {

    /** 未設定の項目（null）を空文字・空のリストへ揃え、リストは複製して変更できないようにする */
    public UsedTable {
      schema = TextValues.orEmpty(schema);
      name = TextValues.orEmpty(name);
      schemaResolution = TextValues.orEmpty(schemaResolution);
      schemaCandidates = schemaCandidates == null ? List.of() : List.copyOf(schemaCandidates);
      operations = operations == null ? List.of() : List.copyOf(operations);
    }
  }
}
