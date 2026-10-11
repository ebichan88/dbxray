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
      return unquote(token.text());
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

  /** 閉じていない識別子は、終わりまでを中身とする */
  private static String unquote(String text) {
    final int open = text.indexOf('"');
    final boolean closed = text.length() - open >= 2 && text.endsWith("\"");
    return text.substring(open + 1, closed ? text.length() - 1 : text.length())
        .replace("\"\"", "\"");
  }
}
