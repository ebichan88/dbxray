package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * テーブルを使う関数・プロシージャ1つ分（同名のオーバーロードは1つにまとめる）<br>
 * 参考情報の値で、動的SQL・呼び出している関数の中・出力対象外のテーブルは含まない
 *
 * @param function 関数のキー
 * @param kind 種別（FUNCTION/PROCEDURE）。一致したオーバーロードで種別が異なる場合・未設定の場合は空文字
 * @param overloads テーブルに一致したオーバーロードの引数。引数が無い場合は空文字
 * @param operations 操作（{@code C}・{@code R}・{@code U}・{@code D}の順）。一致したオーバーロードの合算
 * @param possible 一致したすべてのオーバーロードが、スキーマが決まらない名前（候補）でしか一致しなかった場合はtrue。
 *     確定で一致したオーバーロードと候補でだけ一致したオーバーロードが混ざる場合はfalseで、候補のオーバーロードの操作も
 *     {@code operations}に合算されるため、その操作は確定のように見える
 * @param schemaResolution スキーマが決まらない名前が何で決まるか（{@code possible}の場合だけ。それ以外は空文字）
 * @param dynamicSql 一致したオーバーロードに含まれる動的SQLの種類
 * @param via 関数が使っているビュー（自テーブルではなくビューを使っている場合）。自テーブルを使っている場合はnull
 * @param calledByTriggers この関数を実行するトリガー
 */
public record FunctionUse(
    ObjectKey function,
    String kind,
    List<String> overloads,
    List<String> operations,
    boolean possible,
    String schemaResolution,
    List<String> dynamicSql,
    ObjectKey via,
    List<TableTrigger> calledByTriggers) {

  /** 複製して保持する */
  public FunctionUse {
    overloads = List.copyOf(overloads);
    operations = List.copyOf(operations);
    dynamicSql = List.copyOf(dynamicSql);
    calledByTriggers = List.copyOf(calledByTriggers);
  }
}
