package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 関数・プロシージャの定義本体を字句（トークン）に分けるクラス<br>
 * 先頭から1文字ずつ読み、今いる場所が文字列・コメント・識別子のどれかを状態として持つ。コメントと文字列は互いの中で効かない （文字列の中の{@code
 * --}はコメントにせず、コメントの中の{@code '}で文字列を始めない）ため、同時に左から読まないと区別できない。
 * 規則はDBごとに異なる（PostgreSQLはブロックコメントを入れ子にでき、ドル引用符を持つ。Oracleは入れ子にできず、{@code q'[...]'}を持つ）。
 * 閉じていない文字列・コメント等は範囲の終わりまでをその要素とし、例外にはしない（コンパイルに失敗した本体もDBに残りうるため）
 */
final class SqlLexer {

  /** ドル引用符のタグの長さの上限。超えるものはドル引用符とみなさない（閉じの区切りを探す手間が「本体の長さ×タグの長さ」になるため） */
  static final int MAX_DOLLAR_TAG_LENGTH = 64;

  private static final String LINE_COMMENT_START = "--";
  private static final String BLOCK_COMMENT_START = "/*";
  private static final String BLOCK_COMMENT_END = "*/";

  private final String source;
  private final int to;
  private final Dbms dbms;
  private final List<SqlToken> tokens = new ArrayList<>();
  private int pos;
  private int line;
  private boolean incomplete;

  /** 直前に読んだドル引用符の中身の範囲（ドル引用符以外を読んだ後は-1） */
  private int contentStart = -1;

  private int contentEnd = -1;

  private SqlLexer(String source, int from, int to, int firstLine, Dbms dbms) {
    this.source = source;
    this.to = to;
    this.dbms = dbms;
    this.pos = from;
    this.line = firstLine;
  }

  /** 文字列全体を字句に分ける */
  static SqlLexResult lex(String source, Dbms dbms) {
    return lex(source, 0, source.length(), 1, dbms);
  }

  /**
   * 文字列の一部の範囲を字句に分けるメソッド（ドル引用符の中身を、元の文字列での位置のまま分け直すため）
   *
   * @param from 範囲の開始位置（含む）
   * @param to 範囲の終了位置（含まない）
   * @param firstLine 範囲の開始位置の行番号
   */
  static SqlLexResult lex(String source, int from, int to, int firstLine, Dbms dbms) {
    return new SqlLexer(source, from, to, firstLine, dbms).run();
  }

  private SqlLexResult run() {
    while (pos < to) {
      final int start = pos;
      final int startLine = line;
      contentStart = -1;
      final SqlTokenKind kind = scanOne();
      if (!kind.isTrivia()) {
        tokens.add(
            new SqlToken(
                kind,
                source,
                start,
                pos,
                startLine,
                contentStart < 0 ? start : contentStart,
                contentStart < 0 ? pos : contentEnd));
      }
      line += lineBreaks(source, start, pos, to);
    }
    return new SqlLexResult(tokens, incomplete);
  }

  /** 現在の位置から字句を1つ読み、位置を字句の終わりまで進める（必ず1文字以上進める） */
  private SqlTokenKind scanOne() {
    final char c = source.charAt(pos);
    if (isWhitespace(c)) {
      while (pos < to && isWhitespace(source.charAt(pos))) {
        pos++;
      }
      return SqlTokenKind.WHITESPACE;
    }
    if (lookingAt(LINE_COMMENT_START)) {
      scanLineComment();
      return SqlTokenKind.COMMENT;
    }
    if (lookingAt(BLOCK_COMMENT_START)) {
      scanBlockComment();
      return SqlTokenKind.COMMENT;
    }
    if (c == '\'') {
      scanQuoted('\'', false);
      return SqlTokenKind.STRING;
    }
    if (c == '"') {
      scanQuoted('"', false);
      return SqlTokenKind.QUOTED_IDENTIFIER;
    }
    final Optional<SqlTokenKind> dialectKind =
        switch (dbms) {
          case POSTGRESQL -> scanPostgresSpecific(c);
          case ORACLE -> scanOracleSpecific(c);
        };
    if (dialectKind.isPresent()) {
      return dialectKind.get();
    }
    if (isIdentifierStart(c)) {
      pos++;
      skipIdentifierParts();
      return SqlTokenKind.WORD;
    }
    if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
      scanNumber();
      return SqlTokenKind.NUMBER;
    }
    return scanSymbol(c);
  }

  /**
   * PostgreSQLに固有の字句（{@code E'...'}・{@code U&'...'}・{@code B'...'}等の接頭辞付きの文字列、ドル引用符、位置パラメータ）を読む
   *
   * @return 読んだ字句の種類。当てはまらない場合は空（位置は進めない）
   */
  private Optional<SqlTokenKind> scanPostgresSpecific(char c) {
    if ((c == 'E' || c == 'e') && peek(1) == '\'') {
      pos++;
      scanQuoted('\'', true);
      return Optional.of(SqlTokenKind.STRING);
    }
    if ((c == 'U' || c == 'u') && peek(1) == '&' && (peek(2) == '\'' || peek(2) == '"')) {
      final char quote = peek(2);
      skip("U&");
      scanQuoted(quote, false);
      return Optional.of(quote == '"' ? SqlTokenKind.QUOTED_IDENTIFIER : SqlTokenKind.STRING);
    }
    if ("BbXxNn".indexOf(c) >= 0 && peek(1) == '\'') {
      pos++;
      scanQuoted('\'', false);
      return Optional.of(SqlTokenKind.STRING);
    }
    if (c == '$') {
      return Optional.of(scanPostgresDollar());
    }
    return Optional.empty();
  }

  /**
   * Oracleに固有の字句（{@code q'[...]'}・{@code N'...'}の文字列、条件付きコンパイル・問い合わせ指令、バインド変数）を読む
   *
   * @return 読んだ字句の種類。当てはまらない場合は空（位置は進めない）
   */
  private Optional<SqlTokenKind> scanOracleSpecific(char c) {
    if ((c == 'Q' || c == 'q') && peek(1) == '\'') {
      final int delimiterPos = pos + "q'".length();
      if (hasAlternativeQuoteDelimiter(delimiterPos)) {
        scanAlternativeQuote(delimiterPos);
        return Optional.of(SqlTokenKind.STRING);
      }
    }
    if ((c == 'N' || c == 'n') && (peek(1) == 'Q' || peek(1) == 'q') && peek(2) == '\'') {
      final int delimiterPos = pos + "nq'".length();
      if (hasAlternativeQuoteDelimiter(delimiterPos)) {
        scanAlternativeQuote(delimiterPos);
        return Optional.of(SqlTokenKind.STRING);
      }
    }
    if ((c == 'N' || c == 'n') && peek(1) == '\'') {
      pos++;
      scanQuoted('\'', false);
      return Optional.of(SqlTokenKind.STRING);
    }
    if (c == '$') {
      final int nameStart = pos + (peek(1) == '$' ? "$$" : "$").length();
      if (nameStart < to && isIdentifierStart(source.charAt(nameStart))) {
        pos = nameStart;
        skipIdentifierParts();
        return Optional.of(SqlTokenKind.DIRECTIVE);
      }
      return Optional.empty();
    }
    if (c == ':' && (isIdentifierStart(peek(1)) || isDigit(peek(1)))) {
      pos++;
      skipIdentifierParts();
      return Optional.of(SqlTokenKind.PARAMETER);
    }
    return Optional.empty();
  }

  /** 識別子の続きの文字を読み飛ばす */
  private void skipIdentifierParts() {
    while (pos < to && isIdentifierPart(source.charAt(pos))) {
      pos++;
    }
  }

  private void scanLineComment() {
    skip(LINE_COMMENT_START);
    while (pos < to && source.charAt(pos) != '\n' && source.charAt(pos) != '\r') {
      pos++;
    }
  }

  /** PostgreSQLは入れ子の深さを数え、Oracleは最初の{@code *}{@code /}で閉じる */
  private void scanBlockComment() {
    skip(BLOCK_COMMENT_START);
    int depth = 1;
    while (pos < to) {
      if (lookingAt(BLOCK_COMMENT_END)) {
        skip(BLOCK_COMMENT_END);
        depth--;
        if (depth == 0) {
          return;
        }
      } else if (nestsBlockComments() && lookingAt(BLOCK_COMMENT_START)) {
        skip(BLOCK_COMMENT_START);
        depth++;
      } else {
        pos++;
      }
    }
    incomplete = true;
  }

  /** ブロックコメントを入れ子にできるか（DBMSを足したときにコンパイルエラーで規則の決め忘れに気付けるよう、{@code switch}で書く） */
  private boolean nestsBlockComments() {
    return switch (dbms) {
      case POSTGRESQL -> true;
      case ORACLE -> false;
    };
  }

  /** {@code #}を識別子に使えるか（Oracleの{@code A#B}） */
  private boolean allowsHashInIdentifier() {
    return switch (dbms) {
      case POSTGRESQL -> false;
      case ORACLE -> true;
    };
  }

  /**
   * 引用符で囲んだ文字列・識別子を読む（位置は開始の引用符）<br>
   * 引用符を2つ重ねたものは中身の引用符とみなす
   *
   * @param backslashEscapes {@code \}の直後の1文字を中身とみなすか（PostgreSQLの{@code E'...'}）
   */
  private void scanQuoted(char quote, boolean backslashEscapes) {
    pos++;
    while (pos < to) {
      final char c = source.charAt(pos);
      if (backslashEscapes && c == '\\') {
        pos = Math.min(pos + 2, to);
      } else if (c == quote && peek(1) == quote) {
        pos += 2;
      } else if (c == quote) {
        pos++;
        return;
      } else {
        pos++;
      }
    }
    incomplete = true;
  }

  /** Oracleの代替引用符（{@code q'[...]'}）の区切り文字があるか（空白・範囲の外は区切りにできない） */
  private boolean hasAlternativeQuoteDelimiter(int delimiterPos) {
    return delimiterPos < to && !isWhitespace(source.charAt(delimiterPos));
  }

  /**
   * Oracleの代替引用符（{@code q'[...]'}）を読む。閉じていなければ範囲の終わりまでを読む
   *
   * @param delimiterPos 区切り文字の位置（{@link #hasAlternativeQuoteDelimiter}で確かめたもの）
   */
  private void scanAlternativeQuote(int delimiterPos) {
    final char close = closingDelimiter(source.charAt(delimiterPos));
    pos = delimiterPos + 1;
    while (pos < to) {
      if (source.charAt(pos) == close && peek(1) == '\'') {
        pos += 2;
        return;
      }
      pos++;
    }
    incomplete = true;
  }

  private static char closingDelimiter(char open) {
    return switch (open) {
      case '[' -> ']';
      case '{' -> '}';
      case '<' -> '>';
      case '(' -> ')';
      default -> open;
    };
  }

  /** PostgreSQLの{@code $}で始まる字句（位置パラメータ・ドル引用符）を読む。どちらでもなければ{@code $}1文字の記号とする */
  private SqlTokenKind scanPostgresDollar() {
    if (isDigit(peek(1))) {
      pos++;
      while (pos < to && isDigit(source.charAt(pos))) {
        pos++;
      }
      return SqlTokenKind.PARAMETER;
    }
    final int tagEnd = dollarTagEnd();
    if (tagEnd < 0) {
      pos++;
      return SqlTokenKind.SYMBOL;
    }
    final int tagLength = tagEnd - pos;
    final int tagStart = pos;
    for (int i = tagEnd; i < to; i++) {
      if (source.charAt(i) == '$'
          && i + tagLength <= to
          && source.regionMatches(i, source, tagStart, tagLength)) {
        pos = i + tagLength;
        contentStart = tagEnd;
        contentEnd = i;
        return SqlTokenKind.DOLLAR_STRING;
      }
    }
    pos = to;
    incomplete = true;
    contentStart = tagEnd;
    contentEnd = to;
    return SqlTokenKind.DOLLAR_STRING;
  }

  /**
   * 現在の位置からドル引用符の開始の区切り（{@code $$}・{@code $tag$}）を読む
   *
   * @return 区切りの直後の位置。区切りでない場合は-1
   */
  private int dollarTagEnd() {
    int i = pos + 1;
    if (i < to && isIdentifierStart(source.charAt(i))) {
      i++;
      while (i < to && i - pos - 1 <= MAX_DOLLAR_TAG_LENGTH && isDollarTagPart(source.charAt(i))) {
        i++;
      }
    }
    if (i < to && source.charAt(i) == '$' && i - pos - 1 <= MAX_DOLLAR_TAG_LENGTH) {
      return i + 1;
    }
    return -1;
  }

  /** {@code 1..10}（PL/SQL・PL/pgSQLの範囲）の{@code ..}は数値に含めない */
  private void scanNumber() {
    while (pos < to && isDigit(source.charAt(pos))) {
      pos++;
    }
    if (pos < to && source.charAt(pos) == '.' && peek(1) != '.') {
      pos++;
      while (pos < to && isDigit(source.charAt(pos))) {
        pos++;
      }
    }
    if (pos < to && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
      final int digitOffset = peek(1) == '+' || peek(1) == '-' ? 2 : 1;
      if (isDigit(peek(digitOffset))) {
        pos += digitOffset;
        while (pos < to && isDigit(source.charAt(pos))) {
          pos++;
        }
      }
    }
  }

  private SqlTokenKind scanSymbol(char c) {
    pos += isTwoCharSymbol(c, peek(1)) ? 2 : 1;
    return SqlTokenKind.SYMBOL;
  }

  /** 1つの字句として扱う2文字の演算子・区切り記号（{@code ::}・{@code :=}・{@code <>}等）か */
  private static boolean isTwoCharSymbol(char first, char second) {
    return switch (first) {
      case ':' -> second == ':' || second == '=';
      case '=' -> second == '>';
      case '.' -> second == '.';
      case '|' -> second == '|';
      case '<' -> second == '<' || second == '=' || second == '>';
      case '>' -> second == '>' || second == '=';
      case '!' -> second == '=';
      default -> false;
    };
  }

  /** 現在の位置から、範囲の終わりまでの間に{@code text}があるか */
  private boolean lookingAt(String text) {
    return pos + text.length() <= to && source.startsWith(text, pos);
  }

  /** 位置を{@code text}の長さだけ進める（{@code text}が何を読み飛ばすかを示す。中身は確かめないので、先に{@link #lookingAt}等で確かめておく） */
  private void skip(String text) {
    pos += text.length();
  }

  /** 範囲の外は{@code \0}として扱う（比べる相手はいずれも{@code \0}以外の文字） */
  private char peek(int offset) {
    final int index = pos + offset;
    return index < to ? source.charAt(index) : '\0';
  }

  /**
   * 範囲の改行の数を数えるメソッド（字句の行番号と同じ規則で、行番号を求める処理が食い違わないようにする）<br>
   * CRLFは1つの改行として数える（CRの直後がLFなら、LFの方で数える）
   *
   * @param limit 先読みの上限（含まない）。範囲の終わりのCRの直後のLFは、上限の外なら読まない
   */
  static int lineBreaks(String source, int start, int end, int limit) {
    int count = 0;
    for (int i = start; i < end; i++) {
      final char c = source.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 >= limit || source.charAt(i + 1) != '\n'))) {
        count++;
      }
    }
    return count;
  }

  /**
   * 空白（区切りとして読み飛ばす文字）か<br>
   * PostgreSQLは全角空白・NBSPを空白として扱わないが、全角空白を含む本体はPL/pgSQLでは実行時まで誤りにならずDBに残りうるため、
   * 区切りとみなして書き手の意図したテーブルを示す。BOMも読み飛ばす
   */
  private static boolean isWhitespace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
  }

  private static boolean isIdentifierStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || isNonAsciiLetter(c);
  }

  private boolean isIdentifierPart(char c) {
    return isIdentifierStart(c) || isDigit(c) || c == '$' || (allowsHashInIdentifier() && c == '#');
  }

  /** ドル引用符のタグは、識別子と異なり{@code $}を含まない */
  private static boolean isDollarTagPart(char c) {
    return isIdentifierStart(c) || isDigit(c);
  }

  private static boolean isNonAsciiLetter(char c) {
    return c >= 0x80 && !isWhitespace(c);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
