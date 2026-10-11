package com.dbxray.domain.service.tableusage;

import static com.dbxray.domain.service.tableusage.LexerFixtures.describe;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SqlLexer の字句の分け方（空白・改行・コメント・文字列・識別子・数値・記号）のテスト */
class SqlLexerTest {

  /** 両DBで同じ結果になるケース（説明・入力・期待する字句） */
  static Stream<Arguments> commonCases() {
    return Stream.of(
        // 空白・改行は区切りとして読み飛ばす
        Arguments.of("LF", "select\na", "W:select|W:a"),
        Arguments.of("CRLF", "select\r\na", "W:select|W:a"),
        Arguments.of("CRだけ", "select\ra", "W:select|W:a"),
        Arguments.of("タブ", "select\ta", "W:select|W:a"),
        Arguments.of("改ページ", "select\fa", "W:select|W:a"),
        Arguments.of("全角空白", "select\u3000a", "W:select|W:a"),
        Arguments.of("NBSP", "select\u00A0a", "W:select|W:a"),
        Arguments.of("BOM", "\uFEFFselect", "W:select"),
        Arguments.of("改行・空白の連続", " \r\n\t select \n\n\r\n a \t", "W:select|W:a"),
        Arguments.of("空白なしのカンマ", "a,b", "W:a|Y:,|W:b"),
        Arguments.of("空白なしの修飾と比較", "t.x=1", "W:t|Y:.|W:x|Y:=|N:1"),
        Arguments.of("改行をまたぐ修飾", "sample\n.\nemployee", "W:sample|Y:.|W:employee"),
        Arguments.of("空の入力", "", ""),
        Arguments.of("空白だけ", " \n\t\r\n ", ""),
        // 行コメント
        Arguments.of("行末の行コメント", "a -- c\nb", "W:a|W:b"),
        Arguments.of("末尾の行コメント（改行なし）", "a -- c", "W:a"),
        Arguments.of("CRで終わる行コメント", "a -- c\rb", "W:a|W:b"),
        Arguments.of("CRLFで終わる行コメント", "a -- c\r\nb", "W:a|W:b"),
        Arguments.of("行コメントの中の引用符", "a -- it's\nb", "W:a|W:b"),
        Arguments.of("行コメントの中の二重引用符", "a -- \"x\nb", "W:a|W:b"),
        Arguments.of("行コメントの中のブロックコメントの開始", "a -- /*\nb", "W:a|W:b"),
        Arguments.of("行コメントの中のドル記号", "a -- $$\nb", "W:a|W:b"),
        Arguments.of("単語に続けて書いた行コメント", "employee--x\nb", "W:employee|W:b"),
        Arguments.of("3つ続くハイフン", "a---b\nc", "W:a|W:c"),
        Arguments.of("離れたハイフンはコメントではない", "a - -b", "W:a|Y:-|Y:-|W:b"),
        Arguments.of("行コメントの中のDML", "-- DELETE FROM employee;\nx", "W:x"),
        // ブロックコメント
        Arguments.of("複数行のブロックコメント", "a /* x\n y */ b", "W:a|W:b"),
        Arguments.of("ブロックコメントの中の行コメント", "a /* -- */ b", "W:a|W:b"),
        Arguments.of("ブロックコメントの中の引用符", "a /* it's */ b", "W:a|W:b"),
        Arguments.of("空のブロックコメント", "/**/a", "W:a"),
        Arguments.of("閉じていないブロックコメント", "a /* x", "W:a [incomplete]"),
        Arguments.of("/*/は閉じない", "a/*/b", "W:a [incomplete]"),
        Arguments.of("開始の無い閉じは記号", "a */ b", "W:a|Y:*|Y:/|W:b"),
        Arguments.of("ヒント", "select /*+ INDEX(t) */ a", "W:select|W:a"),
        Arguments.of("名前の部品の間のコメント", "sample/*x*/./*y*/employee", "W:sample|Y:.|W:employee"),
        Arguments.of("ブロックコメントの中のDML", "/*\nUPDATE employee SET x = 1;\n*/ y", "W:y"),
        // 文字列
        Arguments.of("引用符を重ねた文字列", "'it''s' x", "S:'it''s'|W:x"),
        Arguments.of("空の文字列", "''", "S:''"),
        Arguments.of("複数行の文字列", "'a\nb' c", "S:'a\nb'|W:c"),
        Arguments.of(
            "コメント・区切りを含む文字列", "'-- /* ; DELETE FROM t' x", "S:'-- /* ; DELETE FROM t'|W:x"),
        Arguments.of("閉じていない文字列", "'abc", "S:'abc [incomplete]"),
        Arguments.of("文字列の直後の単語", "'a'b", "S:'a'|W:b"),
        // 引用符付き識別子
        Arguments.of("二重引用符を重ねた識別子", "\"a\"\"b\" c", "Q:\"a\"\"b\"|W:c"),
        Arguments.of("空白・ピリオドを含む識別子", "\"my table.x\"", "Q:\"my table.x\""),
        Arguments.of("キーワードの識別子", "\"select\"", "Q:\"select\""),
        Arguments.of("閉じていない識別子", "\"abc", "Q:\"abc [incomplete]"),
        // 識別子・数値・記号
        Arguments.of("日本語の識別子", "社員 給与", "W:社員|W:給与"),
        Arguments.of("下線で始まる識別子", "_x1", "W:_x1"),
        Arguments.of("ドル記号を含む識別子", "a$b$ c", "W:a$b$|W:c"),
        Arguments.of("小数と指数", "1.5e-3 x", "N:1.5e-3|W:x"),
        Arguments.of("小数点で始まる数値", ".5", "N:.5"),
        Arguments.of("範囲の..", "1..10", "N:1|Y:..|N:10"),
        Arguments.of("小数点で終わる数値", "10.", "N:10."),
        Arguments.of("指数の数字が無い", "1e", "N:1|W:e"),
        Arguments.of("指数の符号の後に数字が無い", "1e+", "N:1|W:e|Y:+"),
        Arguments.of("型変換", "a::text", "W:a|Y:::|W:text"),
        Arguments.of("代入", "x := 1", "W:x|Y::=|N:1"),
        Arguments.of("名前付きの引数", "p => 1", "W:p|Y:=>|N:1"),
        Arguments.of("ラベル", "<<lbl>>", "Y:<<|W:lbl|Y:>>"),
        Arguments.of("DBリンク", "t@link", "W:t|Y:@|W:link"),
        Arguments.of("連結", "a||b", "W:a|Y:|||W:b"),
        Arguments.of("比較演算子", "a <> b != c <= d >= e", "W:a|Y:<>|W:b|Y:!=|W:c|Y:<=|W:d|Y:>=|W:e"),
        Arguments.of("ドル記号だけ（PGはタグでなく、Oracleは指令でない）", "$ x", "Y:$|W:x"),
        Arguments.of("制御文字は記号", "a\u0000b", "W:a|Y:\u0000|W:b"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("commonCases")
  @DisplayName("lex: PostgreSQLの規則で字句に分ける（両DBで共通の書き方）")
  void testCommonCasesWithPostgres(String description, String source, String expected) {
    assertEquals(expected, describe(SqlLexer.lex(source, Dbms.POSTGRESQL)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("commonCases")
  @DisplayName("lex: Oracleの規則で字句に分ける（両DBで共通の書き方）")
  void testCommonCasesWithOracle(String description, String source, String expected) {
    assertEquals(expected, describe(SqlLexer.lex(source, Dbms.ORACLE)));
  }

  /** PostgreSQLに固有の書き方 */
  @Nested
  class PostgreSQL {

    static Stream<Arguments> cases() {
      return Stream.of(
          Arguments.of("入れ子のブロックコメント", "a /* /* */ */ b", "W:a|W:b"),
          Arguments.of("入れ子が閉じていないブロックコメント", "a /* /* */ b", "W:a [incomplete]"),
          Arguments.of("E文字列の\\'", "E'it\\'s' x", "S:E'it\\'s'|W:x"),
          Arguments.of("小文字のe文字列", "e'a\\'b' x", "S:e'a\\'b'|W:x"),
          Arguments.of("E文字列の\\\\", "E'\\\\' x", "S:E'\\\\'|W:x"),
          Arguments.of("E文字列の末尾の\\", "E'abc\\", "S:E'abc\\ [incomplete]"),
          Arguments.of("標準の文字列の\\は文字どおり", "'\\' x", "S:'\\'|W:x"),
          Arguments.of("U&文字列", "U&'d\\0061t' x", "S:U&'d\\0061t'|W:x"),
          Arguments.of("U&識別子", "U&\"d\\0061t\" x", "Q:U&\"d\\0061t\"|W:x"),
          Arguments.of("N文字列", "N'x' y", "S:N'x'|W:y"),
          Arguments.of("ビット列", "B'101' y", "S:B'101'|W:y"),
          Arguments.of("16進ビット列", "X'ff' y", "S:X'ff'|W:y"),
          Arguments.of("空のタグのドル引用符", "$$ a 'b $$ c", "D:$$ a 'b $$|W:c"),
          Arguments.of("タグ付きのドル引用符", "$f$ x $f$ y", "D:$f$ x $f$|W:y"),
          Arguments.of("大文字小文字の違うタグでは閉じない", "$F$ x $f$ y", "D:$F$ x $f$ y [incomplete]"),
          Arguments.of("別のタグの入れ子", "$a$ $b$ x $b$ $a$ z", "D:$a$ $b$ x $b$ $a$|W:z"),
          Arguments.of("ドル引用符の中のコメント・引用符", "$$ -- ' /* $$ x", "D:$$ -- ' /* $$|W:x"),
          Arguments.of("位置パラメータ", "$1 + $23", "P:$1|Y:+|P:$23"),
          Arguments.of(
              "延ばした区切り",
              "$functionx$ $function$ $functionx$ y",
              "D:$functionx$ $function$ $functionx$|W:y"),
          Arguments.of("閉じていないドル引用符", "$$ abc", "D:$$ abc [incomplete]"),
          Arguments.of("開始と重なる閉じは閉じではない", "$a$a$", "D:$a$a$ [incomplete]"),
          Arguments.of("日本語のタグ", "$本体$ x $本体$", "D:$本体$ x $本体$"),
          Arguments.of("数字で始まるタグはパラメータ", "$1a$", "P:$1|W:a$"),
          Arguments.of("#は記号", "a#b", "W:a|Y:#|W:b"),
          Arguments.of(":名前は記号と単語", ":name", "Y::|W:name"),
          Arguments.of(
              "Oracleの指令の書き方は空のタグのドル引用符", "$$PLSQL_UNIT x", "D:$$PLSQL_UNIT x [incomplete]"),
          Arguments.of("q'は単語と文字列", "q'[a]' x", "W:q|S:'[a]'|W:x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("lex: PostgreSQLに固有の規則で字句に分ける")
    void testPostgresSpecificCases(String description, String source, String expected) {
      assertEquals(expected, describe(SqlLexer.lex(source, Dbms.POSTGRESQL)));
    }

    @Test
    @DisplayName("lex: 上限の長さのタグはドル引用符、上限を超えるタグはドル引用符とみなさない（$は記号、続きは$を含む識別子）")
    void testDollarTagLengthLimit() {
      final String longest = "a".repeat(SqlLexer.MAX_DOLLAR_TAG_LENGTH);
      assertEquals(
          "D:$" + longest + "$ x $" + longest + "$",
          describe(SqlLexer.lex("$" + longest + "$ x $" + longest + "$", Dbms.POSTGRESQL)));
      final String tooLong = "a".repeat(SqlLexer.MAX_DOLLAR_TAG_LENGTH + 1);
      assertEquals(
          "Y:$|W:" + tooLong + "$|W:x",
          describe(SqlLexer.lex("$" + tooLong + "$ x", Dbms.POSTGRESQL)));
    }
  }

  /** Oracleに固有の書き方 */
  @Nested
  class Oracle {

    static Stream<Arguments> cases() {
      return Stream.of(
          Arguments.of("ブロックコメントは入れ子にできない", "a /* /* */ b", "W:a|W:b"),
          Arguments.of("入れ子のつもりの閉じは記号", "a /* /* */ */ b", "W:a|Y:*|Y:/|W:b"),
          Arguments.of("\\は文字どおり", "'a\\' b", "S:'a\\'|W:b"),
          Arguments.of("Eは接頭辞ではない", "E'a' b", "W:E|S:'a'|W:b"),
          Arguments.of("q'[]'", "q'[it's]' x", "S:q'[it's]'|W:x"),
          Arguments.of("q'{}'", "q'{a'b}' x", "S:q'{a'b}'|W:x"),
          Arguments.of("q'<>'", "q'<a'b>' x", "S:q'<a'b>'|W:x"),
          Arguments.of("q'()'", "q'(a'b)' x", "S:q'(a'b)'|W:x"),
          Arguments.of("任意の区切り", "q'!a'b!' x", "S:q'!a'b!'|W:x"),
          Arguments.of("大文字のQ", "Q'#a'b#' x", "S:Q'#a'b#'|W:x"),
          Arguments.of("nq'", "nq'[a]' x", "S:nq'[a]'|W:x"),
          Arguments.of("Nq'", "Nq'[a]' x", "S:Nq'[a]'|W:x"),
          Arguments.of("中の]で閉じない", "q'[a]b]' x", "S:q'[a]b]'|W:x"),
          Arguments.of("閉じていないq'", "q'[abc", "S:q'[abc [incomplete]"),
          Arguments.of("空白の区切りはq'でない", "q' x' y", "W:q|S:' x'|W:y"),
          Arguments.of("末尾のq'", "q'", "W:q|S:' [incomplete]"),
          Arguments.of("N文字列", "N'x' y", "S:N'x'|W:y"),
          Arguments.of("問い合わせ指令", "$$PLSQL_UNIT || x", "X:$$PLSQL_UNIT|Y:|||W:x"),
          Arguments.of(
              "条件付きコンパイル",
              "$IF a $THEN b $ELSE c $END",
              "X:$IF|W:a|X:$THEN|W:b|X:$ELSE|W:c|X:$END"),
          Arguments.of("名前の無い$$", "$$ x", "Y:$|Y:$|W:x"),
          Arguments.of("$とその他の識別子", "V$SESSION A#B", "W:V$SESSION|W:A#B"),
          Arguments.of("バインド変数", ":name :1", "P::name|P::1"),
          Arguments.of("ドル引用符は無い", "$f$ x $f$", "X:$f$|W:x|X:$f$"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("lex: Oracleに固有の規則で字句に分ける")
    void testOracleSpecificCases(String description, String source, String expected) {
      assertEquals(expected, describe(SqlLexer.lex(source, Dbms.ORACLE)));
    }
  }

  @Test
  @DisplayName("lex: 行番号はLF・CRLF・CRのいずれも1つの改行として数え、複数行の文字列・コメントの後も正しく数える")
  void testLineNumbers() {
    final List<SqlToken> tokens =
        SqlLexer.lex("a\nb\r\nc\rd /* x\ny */ e 'f\r\ng' h", Dbms.POSTGRESQL).tokens();

    assertEquals(
        List.of("a@1", "b@2", "c@3", "d@4", "e@5", "'f\r\ng'@5", "h@6"),
        tokens.stream().map(token -> token.text() + "@" + token.line()).toList());
  }

  @Test
  @DisplayName("lex: 範囲を指定すると、元の文字列での位置・行番号のまま範囲の中だけを分ける")
  void testLexRegion() {
    final String source = "AS $$\nselect a\n$$ tail";
    final int from = source.indexOf("$$") + 2;
    final int to = source.lastIndexOf("$$");

    final SqlLexResult result = SqlLexer.lex(source, from, to, 1, Dbms.POSTGRESQL);

    assertEquals("W:select|W:a", describe(result));
    assertEquals(source.indexOf("select"), result.tokens().get(0).start());
    assertEquals(2, result.tokens().get(0).line());
  }

  @Test
  @DisplayName("lex: 範囲の終わりで閉じていない要素は、範囲の外の文字で閉じない")
  void testLexRegionDoesNotReadBeyondEnd() {
    final String source = "'abc' tail";

    final SqlLexResult result = SqlLexer.lex(source, 0, 3, 1, Dbms.POSTGRESQL);

    assertEquals("S:'ab [incomplete]", describe(result));
  }

  @Test
  @DisplayName("lex: ドル引用符の中身の範囲は、閉じていれば区切りを除き、閉じていなければ終わりまでとする")
  void testDollarContentRange() {
    final String source = "$fn$ body $fn$";
    final SqlToken terminated = SqlLexer.lex(source, Dbms.POSTGRESQL).tokens().get(0);
    assertEquals(" body ", source.substring(terminated.contentStart(), terminated.contentEnd()));

    final String unterminatedSource = "$fn$ body";
    final SqlToken unterminated = SqlLexer.lex(unterminatedSource, Dbms.POSTGRESQL).tokens().get(0);
    assertEquals(
        " body",
        unterminatedSource.substring(unterminated.contentStart(), unterminated.contentEnd()));
  }

  @Test
  @DisplayName("lex: ドル引用符以外の字句の中身の範囲は、字句全体とする")
  void testContentRangeOfOtherTokens() {
    final SqlToken token = SqlLexer.lex("insert", Dbms.POSTGRESQL).tokens().get(0);

    assertEquals(token.start(), token.contentStart());
    assertEquals(token.end(), token.contentEnd());
  }

  @Test
  @DisplayName("lineBreaks: LF・CRLF・CRのいずれも1つの改行として数え、範囲の外のLFを先読みしない")
  void testLineBreaks() {
    final String source = "a\nb\r\nc\rd";

    assertEquals(3, SqlLexer.lineBreaks(source, 0, source.length(), source.length()));
    assertEquals(1, SqlLexer.lineBreaks(source, 0, 3, source.length()));
    // CRの直後のLFが上限の外なら、CRだけで1つ数える
    assertEquals(2, SqlLexer.lineBreaks(source, 0, 4, 4));
  }

  @Test
  @DisplayName("SqlToken.isAnyWord: 引用符の無い単語だけを、ASCIIの大文字にしてキーワードの集合と比べる")
  void testIsAnyWord() {
    assertTrue(word("InSeRt").isAnyWord(Set.of("INSERT", "UPDATE")));
    assertFalse(word("delete").isAnyWord(Set.of("INSERT", "UPDATE")));
    assertFalse(
        new SqlToken(SqlTokenKind.QUOTED_IDENTIFIER, "\"insert\"", 0, 8, 1)
            .isAnyWord(Set.of("INSERT")));
  }

  @Test
  @DisplayName("SqlToken.isWord: ASCIIの英字だけ大文字小文字を区別せず比べ、ASCII以外の文字は畳み込まない")
  void testIsWordComparesOnlyAsciiCaseInsensitively() {
    assertTrue(word("InSeRt").isWord("INSERT"));
    assertFalse(word("ınsert").isWord("INSERT"));
    assertFalse(word("insert").isWord("INSERTS"));
    assertFalse(
        new SqlToken(SqlTokenKind.QUOTED_IDENTIFIER, "\"insert\"", 0, 8, 1).isWord("INSERT"));
  }

  private static SqlToken word(String text) {
    return new SqlToken(SqlTokenKind.WORD, text, 0, text.length(), 1);
  }
}
