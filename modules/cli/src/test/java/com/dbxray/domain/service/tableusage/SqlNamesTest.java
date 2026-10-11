package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SqlNames の字句からカタログでの名前を求める規則のテスト */
class SqlNamesTest {

  @ParameterizedTest(name = "{0} → PG:{1} / ORA:{2}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "Employee|employee|EMPLOYEE",
        "\"Employee\"|Employee|Employee",
        "\"a\"\"b\"|a\"b|a\"b",
        "\"\"|``|``",
        "\"unterminated|unterminated|unterminated",
        "\"|``|``",
        "社員ı|社員ı|社員ı",
      })
  @DisplayName("identifier: 引用符の無い名前はASCIIの英字だけを畳み込み、引用符付きは引用符を外す（閉じていなければ終わりまで）")
  void testIdentifier(String text, String postgres, String oracle) {
    assertEquals(postgres, SqlNames.identifier(only(text, Dbms.POSTGRESQL), Dbms.POSTGRESQL));
    assertEquals(oracle, SqlNames.identifier(only(text, Dbms.ORACLE), Dbms.ORACLE));
  }

  @Test
  @DisplayName("identifier: PostgreSQLのU&付きの識別子は、エスケープを解かずに引用符を外す")
  void testUnicodeEscapedIdentifier() {
    assertEquals("Emp", SqlNames.identifier(only("U&\"Emp\"", Dbms.POSTGRESQL), Dbms.POSTGRESQL));
    assertEquals(
        "d\\0061t", SqlNames.identifier(only("u&\"d\\0061t\"", Dbms.POSTGRESQL), Dbms.POSTGRESQL));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "'a'|a",
        "'a''b'|a'b",
        "E'a''b'|a'b",
        "''|``",
        "'unterminated|unterminated",
        "'|``",
        "'\"'|\"",
      })
  @DisplayName("unquote: 文字列は引用符を外して2つ並びの引用符を1つにし、接頭辞は読み飛ばし、閉じていなければ終わりまでを中身とする")
  void testUnquoteString(String text, String expected) {
    assertEquals(expected, SqlNames.unquote(text, '\''));
  }

  private static SqlToken only(String text, Dbms dbms) {
    final var tokens = SqlLexer.lex(text, dbms).tokens();
    assertEquals(1, tokens.size(), text);
    return tokens.get(0);
  }
}
