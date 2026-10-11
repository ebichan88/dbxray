package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SqlLexer がどんな入力でも例外を投げず、入力を漏れなく重なりなく字句に分け、入力の長さに比例する時間で終えることのテスト<br>
 * 定義本体はDB由来の信頼できない入力のため、想定した書き方のケースに加えて、ランダムな入力・壊れた入力・悪意のある入力で確かめる
 */
class SqlLexerPropertyTest {

  /** ランダムな入力に使う文字。字句の境目になる文字に偏らせる */
  private static final String FUZZ_ALPHABET =
      "''''\"\"$$$---///***\n\r\t qQnNeEuU&[]{}<>()!#:;.,=|@0123456789abcxyz\\_　 社";

  /** 壊す元にする、現実的な書き方の断片 */
  private static final List<String> SEEDS =
      List.of(
          "CREATE OR REPLACE FUNCTION s.f() RETURNS void LANGUAGE plpgsql AS $function$\n"
              + "begin\n  insert into s.t(a) values ('it''s'); -- c\n  /* x /* y */ */\n"
              + "  execute $q$ delete from t $q$;\nend;\n$function$",
          "CREATE OR REPLACE PACKAGE BODY s.p AS\n  PROCEDURE a IS\n  BEGIN\n"
              + "    UPDATE t SET x = q'[it's]' WHERE y = :b; -- c\n"
              + "    $IF $$DEBUG $THEN dbms_output.put_line($$PLSQL_UNIT); $END\n  END a;\nEND p;",
          "select E'\\'' || U&'d\\0061' || B'1' || X'f', $1::text, 1..10, 1.5e-3 from \"a\"\"b\"");

  private static final int FUZZ_COUNT = 20_000;

  private static final int MAX_FUZZ_LENGTH = 120;

  private static final Duration TIME_LIMIT = Duration.ofSeconds(10);

  @ParameterizedTest
  @EnumSource(Dbms.class)
  @DisplayName("lexWithTrivia: ランダムな入力でも例外を投げず、字句をつなぐと入力に戻り、字句は隙間なく並ぶ")
  void testRandomInputsAreCoveredExactly(Dbms dbms) {
    final Random random = new Random(7818L + dbms.ordinal());
    for (int i = 0; i < FUZZ_COUNT; i++) {
      final String input = randomInput(random);
      assertCoveredExactly(input, dbms);
    }
  }

  @ParameterizedTest
  @EnumSource(Dbms.class)
  @DisplayName("lexWithTrivia: 現実的な書き方の文字をランダムに消す・足す・入れ替えても、例外を投げず入力を漏れなく覆う")
  void testMutatedInputsAreCoveredExactly(Dbms dbms) {
    final Random random = new Random(134L + dbms.ordinal());
    for (final String seed : SEEDS) {
      for (int i = 0; i < FUZZ_COUNT / 10; i++) {
        assertCoveredExactly(mutate(seed, random), dbms);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Dbms.class)
  @DisplayName("lex: 空白・コメントを除いた字句は、lexWithTriviaから空白・コメントを除いたものと一致する")
  void testLexMatchesLexWithTriviaWithoutTrivia(Dbms dbms) {
    final Random random = new Random(18L + dbms.ordinal());
    for (int i = 0; i < FUZZ_COUNT / 10; i++) {
      final String input = randomInput(random);
      assertEquals(
          SqlLexer.lexWithTrivia(input, dbms).stream()
              .filter(token -> !token.kind().isTrivia())
              .toList(),
          SqlLexer.lex(input, dbms).tokens(),
          () -> "input: " + escape(input));
    }
  }

  static Stream<Arguments> pathologicalInputs() {
    final int size = 1_000_000;
    return Stream.of(
        Arguments.of("5MB程度の本体", "select a from b where c = 'd'; -- e\n".repeat(140_000)),
        Arguments.of("引用符の並び", "'".repeat(size)),
        Arguments.of("二重引用符の並び", "\"".repeat(size)),
        Arguments.of("ブロックコメントの開始の並び", "/*".repeat(size / 2)),
        Arguments.of("閉じの無いブロックコメントの中のアスタリスク", "/*" + "*".repeat(size)),
        Arguments.of("開き括弧の並び", "(".repeat(size)),
        Arguments.of("閉じの無いドル引用符の中の似た区切り", "$a$" + "$a".repeat(size / 2)),
        Arguments.of(
            "上限の長さのタグの中の似た区切り",
            "$" + "a".repeat(SqlLexer.MAX_DOLLAR_TAG_LENGTH) + "$" + "$a".repeat(size / 2)),
        Arguments.of("ドル記号の並び", "$".repeat(size)),
        Arguments.of("閉じの無いq'の中の]", "q'[" + "]".repeat(size)),
        Arguments.of("ハイフンの並び", "-".repeat(size)),
        Arguments.of("E文字列の\\の並び", "E'" + "\\".repeat(size)),
        Arguments.of("改行の並び", "\r\n".repeat(size / 2)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pathologicalInputs")
  @DisplayName("lex: 長い入力・病的な入力でも、両DBの規則とも制限時間内に終え、入力を漏れなく覆う")
  void testPathologicalInputsFinishInTime(String description, String input) {
    for (final Dbms dbms : Dbms.values()) {
      assertTimeoutPreemptively(TIME_LIMIT, () -> assertCoveredExactly(input, dbms), dbms::name);
    }
  }

  /** 字句をつなぐと入力に戻り、各字句が空でなく、前の字句の直後から始まり、行番号がそれまでの改行の数と一致することを確かめる */
  private static void assertCoveredExactly(String input, Dbms dbms) {
    final List<SqlToken> tokens = SqlLexer.lexWithTrivia(input, dbms);
    int expectedStart = 0;
    int expectedLine = 1;
    for (final SqlToken token : tokens) {
      final int start = expectedStart;
      assertEquals(start, token.start(), () -> "token start, input: " + escape(input));
      assertTrue(token.end() > token.start(), () -> "empty token, input: " + escape(input));
      assertEquals(
          input.substring(token.start(), token.end()),
          token.text(),
          () -> "token text, input: " + escape(input));
      assertEquals(expectedLine, token.line(), () -> "line, input: " + escape(input));
      expectedLine += newlines(input, token.start(), token.end());
      expectedStart = token.end();
    }
    assertEquals(input.length(), expectedStart, () -> "not covered to the end: " + escape(input));
  }

  private static int newlines(String input, int start, int end) {
    int count = 0;
    for (int i = start; i < end; i++) {
      final char c = input.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 >= input.length() || input.charAt(i + 1) != '\n'))) {
        count++;
      }
    }
    return count;
  }

  private static String randomInput(Random random) {
    final int length = random.nextInt(MAX_FUZZ_LENGTH + 1);
    final StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(FUZZ_ALPHABET.charAt(random.nextInt(FUZZ_ALPHABET.length())));
    }
    return sb.toString();
  }

  private static String mutate(String seed, Random random) {
    final StringBuilder sb = new StringBuilder(seed);
    final int edits = 1 + random.nextInt(5);
    for (int i = 0; i < edits && !sb.isEmpty(); i++) {
      final int at = random.nextInt(sb.length());
      final char c = FUZZ_ALPHABET.charAt(random.nextInt(FUZZ_ALPHABET.length()));
      switch (random.nextInt(3)) {
        case 0 -> sb.deleteCharAt(at);
        case 1 -> sb.insert(at, c);
        default -> sb.setCharAt(at, c);
      }
    }
    return sb.toString();
  }

  private static String escape(String input) {
    return input
        .chars()
        .mapToObj(
            c -> c < 0x20 || c > 0x7e ? String.format("\\u%04X", c) : String.valueOf((char) c))
        .collect(Collectors.joining());
  }
}
