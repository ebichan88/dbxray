package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;

/** 字句から、カタログに格納された名前（引用符の無い名前の大文字小文字を畳み込み、引用符を外したもの）を求める */
final class SqlNames {

  private SqlNames() {}

  /**
   * 名前の字句から、カタログでの名前を求めるメソッド<br>
   * 引用符の無い名前は、PostgreSQLは小文字へ、Oracleは大文字へ畳み込む（ASCIIの英字だけ）。引用符付きはそのまま（{@code ""}は{@code "}）
   *
   * @param token 単語か引用符付き識別子の字句
   */
  static String identifier(SqlToken token, Dbms dbms) {
    if (token.kind() == SqlTokenKind.QUOTED_IDENTIFIER) {
      return unquote(token.text(), '"');
    }
    return fold(token.text(), dbms);
  }

  /** 引用符の無い名前を、DBの規則で畳み込む */
  static String fold(String name, Dbms dbms) {
    return switch (dbms) {
      case POSTGRESQL -> AsciiCase.toLower(name);
      case ORACLE -> AsciiCase.toUpper(name);
    };
  }

  /**
   * 引用符で囲んだ字句の中身を求めるメソッド（{@code "a""b"}・{@code 'a''b'}の中身は{@code a"b}・{@code a'b}）<br>
   * 引用符の前の接頭辞（{@code U&}・{@code E}・{@code N}等）は読み飛ばし、閉じていない場合は終わりまでを中身とする<br>
   * 引用符を重ねる書き方だけを解釈する。{@code E'...'}の{@code \}による引用符、Oracleの{@code q'[...]'}（区切り文字ごとに閉じが変わる）は
   * 解釈しないため、これらの字句の中身は呼び出し側で扱う
   *
   * @param quote 囲む引用符（識別子は{@code "}、文字列は{@code '}）。中の2つ並びの引用符は1つにする
   */
  static String unquote(String text, char quote) {
    final int open = text.indexOf(quote);
    final boolean closed = text.length() - open >= 2 && text.charAt(text.length() - 1) == quote;
    return text.substring(open + 1, closed ? text.length() - 1 : text.length())
        .replace(String.valueOf(quote).repeat(2), String.valueOf(quote));
  }
}
