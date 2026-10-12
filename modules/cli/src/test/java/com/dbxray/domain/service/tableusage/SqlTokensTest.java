package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SqlTokens の位置を指定した判定・名前の読み取りのテスト */
class SqlTokensTest {

  private static SqlTokens tokens(String sql) {
    return new SqlTokens(SqlLexer.lex(sql, Dbms.POSTGRESQL).tokens());
  }

  private static String texts(List<SqlToken> tokens) {
    return tokens.stream().map(SqlToken::text).collect(Collectors.joining(" "));
  }

  @Test
  @DisplayName("at・is…At: 範囲の外の位置を指定してもエラーにせず、字句はnull・判定は偽にする")
  void testOutOfRange() {
    final SqlTokens tokens = tokens("select a");

    assertEquals(2, tokens.size());
    assertNull(tokens.at(-1));
    assertNull(tokens.at(2));
    assertFalse(tokens.isWordAt(2, "select"));
    assertFalse(tokens.isSymbolAt(-1, "."));
    assertFalse(tokens.isNameAt(2));
    assertFalse(tokens.isAfterDot(0));
  }

  @Test
  @DisplayName("isWordAt・isSymbolAt・isNameAt・isAfterDot: 位置の字句の種類を判定する")
  void testPredicates() {
    final SqlTokens tokens = tokens("SELECT e.\"Name\", 1");

    assertTrue(tokens.isWordAt(0, "select"), "大文字小文字を区別しない");
    assertFalse(tokens.isWordAt(1, "select"));
    assertTrue(tokens.isSymbolAt(2, "."));
    assertTrue(tokens.isNameAt(1));
    assertTrue(tokens.isNameAt(3), "引用符付きの識別子も名前");
    assertFalse(tokens.isNameAt(5), "数値は名前ではない");
    assertTrue(tokens.isAfterDot(3));
    assertFalse(tokens.isAfterDot(1));
  }

  @Test
  @DisplayName("isAnyWordAt: 位置の字句が引用符の無い単語で、大文字にするとキーワードの集合のいずれかと一致するか判定する")
  void testIsAnyWordAt() {
    final SqlTokens tokens = tokens("select \"FROM\", where");
    final Set<String> keywords = Set.of("FROM", "WHERE");

    assertFalse(tokens.isAnyWordAt(0, keywords), "集合に無い語");
    assertFalse(tokens.isAnyWordAt(1, keywords), "引用符付きは単語ではない");
    assertFalse(tokens.isAnyWordAt(2, keywords), "記号は単語ではない");
    assertTrue(tokens.isAnyWordAt(3, keywords), "大文字小文字を区別しない");
    assertFalse(tokens.isAnyWordAt(4, keywords), "範囲の外");
  }

  @Test
  @DisplayName("endOfQualifiedName: 名前・スキーマ.名前の次の位置を返し、位置に名前が無ければ位置そのものを返す")
  void testEndOfQualifiedName() {
    // a(0) .(1) b(2) .(3) c(4) d(5) .(6) 1(7)
    final SqlTokens tokens = tokens("a.b.c d . 1");

    assertEquals(5, tokens.endOfQualifiedName(0), "a.b.cの次");
    assertEquals(5, tokens.endOfQualifiedName(4), "修飾の無いcの次");
    assertEquals(6, tokens.endOfQualifiedName(5), "後ろに名前の続かない.は含めない");
    assertEquals(7, tokens.endOfQualifiedName(7), "名前でない位置はそのまま");
  }

  @Test
  @DisplayName("qualifiedNameAt: 位置から名前の部品を、修飾を含めて最大3つ読む")
  void testQualifiedNameAt() {
    assertEquals("emp", texts(tokens("emp").qualifiedNameAt(0)));
    assertEquals("scott emp", texts(tokens("scott.emp").qualifiedNameAt(0)));
    assertEquals(
        "db sch tab", texts(tokens("db.sch.tab.extra").qualifiedNameAt(0)), "4つ目以降の部品は読まない");
    assertEquals("\"Q\" t", texts(tokens("\"Q\".t").qualifiedNameAt(0)));
    assertEquals("a", texts(tokens("a. 1").qualifiedNameAt(0)), "後ろに名前の続かない.は含めない");
  }

  @Test
  @DisplayName("qualifiedNameAt: 位置に名前が無い（数値・記号・範囲の外）なら空")
  void testQualifiedNameAtWithoutName() {
    final SqlTokens tokens = tokens("1 (");

    assertEquals(List.of(), tokens.qualifiedNameAt(0));
    assertEquals(List.of(), tokens.qualifiedNameAt(1));
    assertEquals(List.of(), tokens.qualifiedNameAt(2));
  }
}
