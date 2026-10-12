package com.dbxray.infrastructure.db.repository.dto;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQLの取得結果（DTO）の値を、ドメインのエンティティが持つ形へ変換する共通処理を集約したクラス<br>
 * 値が無いことの表現はDBによって異なる（PostgreSQLは空文字、Oracleは空文字がNULLとして返る）ため、エンティティへは空文字に揃えて渡す。
 * また、SQLが区切り文字で連結して返す値（外部キーの列名・トリガーの対象イベント）はリストへ分解する。
 * 表示のための組み立て（連結・空欄の描画等）はテンプレートが担うため、ここでは行わない
 */
final class DtoValues {

  private DtoValues() {}

  /**
   * 任意項目の値を、値が無い場合は空文字となるよう正規化するメソッド
   *
   * @param value SQLから取得した値
   * @return null・空白のみの場合は空文字。それ以外は元の文字列
   */
  static String text(String value) {
    return (value == null || value.isBlank()) ? "" : value;
  }

  /**
   * 区切り文字で連結された値をリストへ分解するメソッド
   *
   * @param value 区切り文字で連結された値
   * @return 分解した値のリスト（前後の空白は除去し、空要素は含めない）。値が無い場合は空リスト
   */
  static List<String> split(String value, String separator) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(Pattern.quote(separator)))
        .map(String::strip)
        .filter(element -> !element.isEmpty())
        .toList();
  }
}
