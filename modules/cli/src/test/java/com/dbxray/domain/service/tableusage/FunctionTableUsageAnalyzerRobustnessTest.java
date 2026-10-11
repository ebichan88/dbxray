package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.TableUsage;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FunctionTableUsageAnalyzer がどんな定義でも例外を投げず、入力の長さに比例する時間で終えることのテスト<br>
 * 定義本体はDB由来の信頼できない入力のため、キーワードに偏らせたランダムな字句の並びと、深い入れ子・長い繰り返しの入力で確かめる
 */
class FunctionTableUsageAnalyzerRobustnessTest {

  /** ランダムな定義に使う字句。判定のきっかけになるキーワード・記号に偏らせる */
  private static final List<String> VOCABULARY =
      List.of(
          "INSERT",
          "INTO",
          "UPDATE",
          "DELETE",
          "MERGE",
          "USING",
          "TRUNCATE",
          "TABLE",
          "FROM",
          "JOIN",
          "APPLY",
          "LATERAL",
          "ONLY",
          "SELECT",
          "WITH",
          "RECURSIVE",
          "AS",
          "MATERIALIZED",
          "NOT",
          "SET",
          "VALUES",
          "DEFAULT",
          "WHEN",
          "THEN",
          "ELSE",
          "MATCHED",
          "ALL",
          "FIRST",
          "FOR",
          "OR",
          "ON",
          "OF",
          "KEY",
          "DISTINCT",
          "EXTRACT",
          "FETCH",
          "MOVE",
          "REVOKE",
          "EXECUTE",
          "IMMEDIATE",
          "OPEN",
          "DBMS_SQL",
          "dblink",
          "FUNCTION",
          "PROCEDURE",
          "PACKAGE",
          "BODY",
          "IS",
          "BEGIN",
          "END",
          "IF",
          "LOOP",
          "CASE",
          "DECLARE",
          "LANGUAGE",
          "EXTERNAL",
          "WRAPPED",
          "AUTHID",
          "CURRENT_USER",
          "RETURN",
          "search_path",
          "(",
          "(",
          ")",
          ")",
          ",",
          ",",
          ";",
          ";",
          ".",
          ".",
          "@",
          ">>",
          "t",
          "emp",
          "sample",
          "employee",
          "\"T\"",
          "'s'",
          "$1",
          ":b",
          "$$x$$",
          "1");

  private static final Tables TABLES =
      Tables.of(
          List.of(
              new TableEntity("db", "sample", "", "employee", TableType.TABLE, ""),
              new TableEntity("db", "sample", "", "t", TableType.TABLE, ""),
              new TableEntity("db", "SCOTT", "", "EMP", TableType.TABLE, ""),
              new TableEntity("db", "SCOTT", "", "T", TableType.VIEW, ""),
              new TableEntity("db", "other", "", "t", TableType.TABLE, "")));

  private static final Duration TIME_LIMIT = Duration.ofSeconds(10);

  private static final int REPEAT = 200_000;

  @ParameterizedTest
  @EnumSource(Dbms.class)
  @DisplayName("analyze: キーワードに偏らせたランダムな定義でも例外を投げず、出力対象のテーブルだけを返す")
  void testRandomDefinitions(Dbms dbms) {
    final Random random = new Random(1340L + dbms.ordinal());
    final FunctionTableUsageAnalyzer analyzer = FunctionTableUsageAnalyzer.of(TABLES, dbms);
    for (int i = 0; i < 20_000; i++) {
      final String definition = randomDefinition(random, dbms);
      final String name = random.nextBoolean() ? "f" : "PKG.F";
      final FunctionTableUsage usage =
          analyzer.analyze(
              new FunctionEntity(
                  "db",
                  dbms == Dbms.POSTGRESQL ? "sample" : "SCOTT",
                  name,
                  1,
                  1,
                  "FUNCTION",
                  random.nextBoolean() ? "" : "A NUMBER",
                  "",
                  dbms == Dbms.POSTGRESQL ? "plpgsql" : "PL/SQL",
                  definition));
      assertOnlyOutputTables(usage, definition);
    }
  }

  static Stream<Arguments> pathologicalDefinitions() {
    return Stream.of(
        Arguments.of("深い括弧", "(".repeat(REPEAT) + " SELECT * FROM t " + ")".repeat(REPEAT)),
        Arguments.of("閉じない括弧", "SELECT * FROM t WHERE x IN " + "(".repeat(REPEAT)),
        Arguments.of("BEGINの繰り返し", "BEGIN ".repeat(REPEAT)),
        Arguments.of("ENDの繰り返し", "END ".repeat(REPEAT)),
        Arguments.of("CASEの繰り返し", "CASE ".repeat(REPEAT) + "END ".repeat(REPEAT)),
        Arguments.of("入れ子のサブプログラム", "PROCEDURE p IS ".repeat(REPEAT / 4)),
        Arguments.of("閉じない見出し", "FUNCTION f ( a ".repeat(REPEAT / 4)),
        Arguments.of("CTEの繰り返し", "WITH a AS (".repeat(REPEAT / 4)),
        Arguments.of("CTEの長い列名", "WITH a (" + "b, ".repeat(REPEAT) + "c) AS (SELECT 1)"),
        Arguments.of("修飾の長い名前", "SELECT * FROM a" + ".a".repeat(REPEAT)),
        Arguments.of("OPENの長い名前", "OPEN a" + ".a".repeat(REPEAT) + " FOR v"),
        Arguments.of("ONLYの繰り返し", "SELECT * FROM " + "ONLY ".repeat(REPEAT) + "t"),
        Arguments.of("FROMの長い並び", "SELECT * FROM " + "t, ".repeat(REPEAT) + "t"),
        Arguments.of("INSERT INTOの繰り返し", "INSERT INTO ".repeat(REPEAT / 2)),
        Arguments.of("同名のサブプログラム", "PROCEDURE p IS BEGIN DELETE FROM t; END; ".repeat(REPEAT / 8)),
        Arguments.of(
            "長いsearch_path",
            "SET search_path TO " + "'a', ".repeat(REPEAT) + "'sample' AS $$ SELECT * FROM t $$"),
        Arguments.of("記号だけ", ";,.()".repeat(REPEAT)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pathologicalDefinitions")
  @DisplayName("analyze: 深い入れ子・長い繰り返しの定義でも、StackOverflowError無しに数秒以内で終える")
  void testPathologicalDefinitions(String title, String body) {
    for (final Dbms dbms : Dbms.values()) {
      final FunctionTableUsageAnalyzer analyzer = FunctionTableUsageAnalyzer.of(TABLES, dbms);
      for (final String definition : wrappings(body, dbms)) {
        for (final String name : List.of("F", "PKG.P")) {
          assertTimeoutPreemptively(
              TIME_LIMIT,
              () ->
                  analyzer.analyze(
                      new FunctionEntity(
                          "db",
                          "sample",
                          name,
                          1,
                          1,
                          "FUNCTION",
                          "A NUMBER",
                          "",
                          dbms == Dbms.POSTGRESQL ? "sql" : "PL/SQL",
                          definition)),
              () -> title + " / " + dbms);
        }
      }
    }
  }

  /** 本体を、定義の各形（そのまま・関数の本体・パッケージ本体）に包む */
  private static List<String> wrappings(String body, Dbms dbms) {
    if (dbms == Dbms.POSTGRESQL) {
      return List.of(body, "CREATE FUNCTION f() LANGUAGE sql AS $function$" + body + "$function$");
    }
    return List.of(
        body,
        "CREATE OR REPLACE PROCEDURE f IS " + body,
        "CREATE OR REPLACE PACKAGE pkg AS END; CREATE OR REPLACE PACKAGE BODY pkg AS " + body);
  }

  private static String randomDefinition(Random random, Dbms dbms) {
    final StringBuilder sb = new StringBuilder();
    final int prefix = random.nextInt(4);
    if (prefix == 1) {
      sb.append(
          dbms == Dbms.POSTGRESQL
              ? "CREATE FUNCTION f() SET search_path TO 'other', 'sample' AS $function$ "
              : "CREATE OR REPLACE PROCEDURE f AUTHID CURRENT_USER IS ");
    } else if (prefix == 2) {
      sb.append(
          dbms == Dbms.POSTGRESQL
              ? "CREATE FUNCTION f() AS $function$ "
              : "CREATE OR REPLACE PACKAGE pkg AS END; CREATE OR REPLACE PACKAGE BODY pkg AS ");
    }
    final int length = random.nextInt(60);
    for (int i = 0; i < length; i++) {
      sb.append(VOCABULARY.get(random.nextInt(VOCABULARY.size()))).append(' ');
    }
    if (prefix == 2 && dbms == Dbms.POSTGRESQL && random.nextBoolean()) {
      sb.append("$function$");
    }
    return sb.toString();
  }

  private static void assertOnlyOutputTables(FunctionTableUsage usage, String definition) {
    for (final TableUsage row : usage.tables()) {
      assertFalse(row.operations().isEmpty(), definition);
      for (final TableEntity table : row.tables()) {
        assertTrue(TABLES.contains(TableKey.of(table)), definition);
      }
      if (row.schemaDetermined()) {
        assertEquals(1, row.tables().size(), definition);
      }
    }
  }
}
