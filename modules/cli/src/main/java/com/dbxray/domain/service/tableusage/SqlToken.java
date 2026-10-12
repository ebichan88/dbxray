package com.dbxray.domain.service.tableusage;

import java.util.Set;

/**
 * 定義本体の字句（トークン）1つ
 *
 * @param source 字句を切り出した元の文字列（全字句で同じ文字列を指す。字句ごとに部分文字列を持つと、字句の数だけメモリを使うため）
 * @param start 元の文字列での開始位置（含む）
 * @param end 元の文字列での終了位置（含まない）
 * @param line 開始位置の行番号（1始まり。LF・CRLF・CRのいずれも1つの改行として数える）
 * @param contentStart ドル引用符の中身の開始位置（開始の区切り{@code $tag$}の直後）。ドル引用符以外は{@code start}
 * @param contentEnd ドル引用符の中身の終了位置（含まない。閉じの区切りの直前で、閉じていなければ{@code end}）。ドル引用符以外は{@code end}
 */
record SqlToken(
    SqlTokenKind kind,
    String source,
    int start,
    int end,
    int line,
    int contentStart,
    int contentEnd) {

  /** ドル引用符以外の字句（中身の範囲は字句全体） */
  SqlToken(SqlTokenKind kind, String source, int start, int end, int line) {
    this(kind, source, start, end, line, start, end);
  }

  /** 元の文字列のうちこの字句の部分（引用符・区切りを含む） */
  String text() {
    return source.substring(start, end);
  }

  /** 引用符の無い単語で、ASCIIの英字の大文字小文字を区別せずにキーワードと一致するか */
  boolean isWord(String keyword) {
    return kind == SqlTokenKind.WORD
        && AsciiCase.regionEqualsIgnoreCase(source, start, end, keyword);
  }

  /**
   * 引用符の無い単語で、ASCIIの大文字にするとキーワードの集合のいずれかと一致するか
   *
   * @param upperCaseKeywords 大文字で書いたキーワード
   */
  boolean isAnyWord(Set<String> upperCaseKeywords) {
    return kind == SqlTokenKind.WORD
        && upperCaseKeywords.contains(AsciiCase.toUpper(source, start, end));
  }

  /** 指定の演算子・区切り記号か */
  boolean isSymbol(String symbol) {
    return kind == SqlTokenKind.SYMBOL
        && end - start == symbol.length()
        && source.startsWith(symbol, start);
  }

  /** テーブル名等の名前の部品になりうる字句（引用符の無い単語か、引用符付きの識別子）か */
  boolean isName() {
    return kind == SqlTokenKind.WORD || kind == SqlTokenKind.QUOTED_IDENTIFIER;
  }

  /** 元の文字列全体を含めないよう、字句の部分だけを表示する（テストの失敗メッセージ・ログに巨大な本体を出さないため） */
  @Override
  public String toString() {
    return String.format(
        "SqlToken[kind=%s, text=%s, start=%d, end=%d, line=%d]", kind, text(), start, end, line);
  }
}
