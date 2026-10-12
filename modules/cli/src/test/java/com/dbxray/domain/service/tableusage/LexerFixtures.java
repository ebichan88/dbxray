package com.dbxray.domain.service.tableusage;

import java.util.stream.Collectors;

/** 字句の解析結果を、テストで比べやすい短い記法の文字列にする部品 */
final class LexerFixtures {

  private LexerFixtures() {}

  /**
   * 字句の解析結果を、{@code 種類:文字列}を{@code |}でつないだ記法にするメソッド<br>
   * 種類は1文字（W=単語、Q=引用符付き識別子、S=文字列、D=ドル引用符、N=数値、P=パラメータ、X=指令、Y=記号）。 閉じていない要素があれば末尾に{@code
   * [incomplete]}を付ける
   */
  static String describe(SqlLexResult result) {
    final String tokens =
        result.tokens().stream()
            .map(token -> code(token.kind()) + ":" + token.text())
            .collect(Collectors.joining("|"));
    return result.incomplete() ? tokens + " [incomplete]" : tokens;
  }

  private static String code(SqlTokenKind kind) {
    return switch (kind) {
      case WORD -> "W";
      case QUOTED_IDENTIFIER -> "Q";
      case STRING -> "S";
      case DOLLAR_STRING -> "D";
      case NUMBER -> "N";
      case PARAMETER -> "P";
      case DIRECTIVE -> "X";
      case SYMBOL -> "Y";
      case WHITESPACE -> "_";
      case COMMENT -> "C";
    };
  }
}
