package com.dbxray.domain.service.tableusage;

/** 字句（トークン）の種類 */
enum SqlTokenKind {
  /** 引用符の無い識別子・キーワード */
  WORD,
  /** 二重引用符で囲んだ識別子（{@code "..."}） */
  QUOTED_IDENTIFIER,
  /** 文字列リテラル（{@code '...'}・{@code E'...'}・Oracleの{@code q'[...]'}等） */
  STRING,
  /** PostgreSQLのドル引用符で囲んだ文字列（{@code $tag$...$tag$}） */
  DOLLAR_STRING,
  NUMBER,
  /** 位置パラメータ・バインド変数（{@code $1}・{@code :name}） */
  PARAMETER,
  /** Oracleの条件付きコンパイル・問い合わせ指令（{@code $IF}・{@code $$PLSQL_UNIT}） */
  DIRECTIVE,
  /** 演算子・区切り記号 */
  SYMBOL,
  /** 空白の並び（字句の解析結果には含めない） */
  WHITESPACE,
  /** 行コメント・ブロックコメント（字句の解析結果には含めない） */
  COMMENT;

  /** 空白・コメント（意味を持たず、字句の解析結果から除くもの）か */
  boolean isTrivia() {
    return this == WHITESPACE || this == COMMENT;
  }
}
