package com.dbxray.domain.service.tableusage;

import static com.dbxray.testsupport.EntityFixtures.function;
import static com.dbxray.testsupport.EntityFixtures.table;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FunctionTableUsageAnalyzer がどんな定義でも例外を投げず、入力の長さに比例する時間で終えることのテスト<br>
 * 定義本体はDB由来の信頼できない入力のため、キーワードに偏らせたランダムな字句の並びと、深い入れ子・長い繰り返しの入力で確かめる。
 * 時間は、入力の長さを4倍にしたときのCPU時間の増え方で確かめる（線形なら4倍、2乗なら16倍）
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
          "$IF",
          "$THEN",
          "$ELSIF",
          "$ELSE",
          "$END",
          "$ERROR",
          "$$flag",
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

  /** ランダムな定義に混ぜる文の断片（字句だけの偏りでは、テーブルを拾う位置まで届くことが少ないため） */
  private static final List<String> STATEMENTS =
      List.of(
          "INSERT INTO t VALUES ( 1 ) ;",
          "INSERT INTO sample.employee ( a ) SELECT a FROM emp ;",
          "UPDATE emp SET a = 1 WHERE b IN ( SELECT b FROM t ) ;",
          "DELETE FROM sample.employee WHERE a = 1 ;",
          "SELECT * FROM t , emp WHERE t.a = emp.a ;",
          "MERGE INTO t USING emp ON ( t.a = emp.a ) WHEN MATCHED THEN UPDATE SET b = 1 ;",
          "TRUNCATE TABLE other.t ;",
          "WITH w AS ( SELECT 1 FROM t ) SELECT * FROM w ;",
          "BEGIN NULL ; END ;",
          "IF x THEN DELETE FROM t ; END IF ;",
          "EXECUTE IMMEDIATE 'x' ;",
          "OPEN c FOR SELECT * FROM emp ;",
          "$IF $$flag $THEN DELETE FROM t ; $ELSE UPDATE emp SET a = 1 ; $END",
          "PROCEDURE p0 IS BEGIN DELETE FROM t ; END ;");

  private static final Tables TABLES =
      Tables.of(
          List.of(
              table("sample", "employee"),
              table("sample", "t"),
              table("SCOTT", "EMP"),
              table("SCOTT", "T", TableType.VIEW),
              table("other", "t")));

  /** 時間を測る入力の長さ（繰り返しの回数）の倍率 */
  private static final int SCALE = 4;

  /** 入力の長さを{@link #SCALE}倍にしたときに許す、CPU時間の増え方（線形なら{@link #SCALE}倍、2乗なら{@link #SCALE}の2乗倍） */
  private static final int ALLOWED_GROWTH = 10;

  /** 長いほうの入力でもこれより短ければ、増え方は見ない（測定のばらつきが大きく、実害も無いため） */
  private static final Duration NEGLIGIBLE = Duration.ofMillis(200);

  /** 実行が終わらない不具合に備えた、1つの入力の測定全体の上限 */
  private static final Duration HANG_LIMIT = Duration.ofSeconds(120);

  private static final int RANDOM_DEFINITIONS = 20_000;

  /** 繰り返す回数の基準（これを{@link #SCALE}倍した長さまで測る） */
  private static final int BASE_COUNT = 30_000;

  /** ランダムな定義のうち、解析できたもの・表を持つものの最低の割合（これを下回ると、確かめる条件に届いていない） */
  private static final double MIN_ANALYZED_RATE = 0.5;

  private static final double MIN_ROWS_RATE = 0.2;

  @ParameterizedTest
  @EnumSource(Dbms.class)
  @DisplayName("analyze: キーワードに偏らせたランダムな定義でも例外を投げず、出力対象のテーブルだけを返す")
  void testRandomDefinitions(Dbms dbms) {
    final Random random = new Random(1340L + dbms.ordinal());
    final FunctionTableUsageAnalyzer analyzer = FunctionTableUsageAnalyzer.of(TABLES, dbms);
    int analyzed = 0;
    int withRows = 0;
    int determined = 0;
    for (int i = 0; i < RANDOM_DEFINITIONS; i++) {
      final FunctionEntity function = randomFunction(random, dbms);
      final FunctionTableUsage usage = analyzer.analyze(function);
      assertOnlyOutputTables(usage, function.definition());
      if (usage.status() == TableUsageStatus.ANALYZED) {
        analyzed++;
        withRows += usage.tables().isEmpty() ? 0 : 1;
        determined += usage.tables().stream().anyMatch(TableUsage::schemaDetermined) ? 1 : 0;
      }
    }
    assertTrue(analyzed >= RANDOM_DEFINITIONS * MIN_ANALYZED_RATE, "analyzed: " + analyzed);
    assertTrue(withRows >= RANDOM_DEFINITIONS * MIN_ROWS_RATE, "withRows: " + withRows);
    assertTrue(determined > 0, "determined: " + determined);
  }

  /**
   * 長い繰り返しの入力1つ
   *
   * @param baseCount 基準の繰り返しの回数（重い入力は、基準より少なくする）
   * @param build 繰り返しの回数から定義の本体を組み立てる
   */
  private record Shape(String title, int baseCount, IntFunction<String> build) {

    @Override
    public String toString() {
      return title;
    }
  }

  static Stream<Shape> pathologicalShapes() {
    return Stream.of(
        new Shape("深い括弧", BASE_COUNT, n -> "(".repeat(n) + " SELECT * FROM t " + ")".repeat(n)),
        new Shape("閉じない括弧", BASE_COUNT, n -> "SELECT * FROM t WHERE x IN " + "(".repeat(n)),
        new Shape("BEGINの繰り返し", BASE_COUNT, n -> "BEGIN ".repeat(n)),
        new Shape("ENDの繰り返し", BASE_COUNT, n -> "END ".repeat(n)),
        new Shape("CASEの繰り返し", BASE_COUNT, n -> "CASE ".repeat(n) + "END ".repeat(n)),
        new Shape("入れ子のサブプログラム", BASE_COUNT / 4, n -> "PROCEDURE p IS ".repeat(n)),
        new Shape("閉じない見出し", BASE_COUNT / 4, n -> "FUNCTION f ( a ".repeat(n)),
        new Shape("CTEの繰り返し", BASE_COUNT / 4, n -> "WITH a AS (".repeat(n)),
        new Shape("CTEの長い列名", BASE_COUNT, n -> "WITH a (" + "b, ".repeat(n) + "c) AS (SELECT 1)"),
        new Shape("修飾の長い名前", BASE_COUNT, n -> "SELECT * FROM a" + ".a".repeat(n)),
        new Shape("OPENの長い名前", BASE_COUNT, n -> "OPEN a" + ".a".repeat(n) + " FOR v"),
        new Shape("ONLYの繰り返し", BASE_COUNT, n -> "SELECT * FROM " + "ONLY ".repeat(n) + "t"),
        new Shape("FROMの長い並び", BASE_COUNT, n -> "SELECT * FROM " + "t, ".repeat(n) + "t"),
        new Shape("INSERT INTOの繰り返し", BASE_COUNT / 2, n -> "INSERT INTO ".repeat(n)),
        new Shape(
            "同名のサブプログラム",
            BASE_COUNT / 8,
            n -> "PROCEDURE p IS BEGIN DELETE FROM t; END; ".repeat(n)),
        new Shape(
            "長いsearch_path",
            BASE_COUNT,
            n -> "SET search_path TO " + "'a', ".repeat(n) + "'sample' AS $$ SELECT * FROM t $$"),
        new Shape("記号だけ", BASE_COUNT, n -> ";,.()".repeat(n)),
        new Shape("条件付きコンパイルの深い入れ子", BASE_COUNT, n -> "$IF x $THEN ".repeat(n) + "$END ".repeat(n)),
        new Shape("閉じない$IF", BASE_COUNT, n -> "$IF x $THEN ".repeat(n)),
        new Shape("対応する$IFの無い$ELSE・$END", BASE_COUNT, n -> "$ELSE $END ".repeat(n)),
        new Shape(
            "引数名の多い見出しの中に$IFを重ねる",
            BASE_COUNT,
            n ->
                "PROCEDURE p ( a NUMBER"
                    + ", a NUMBER".repeat(n)
                    + " $IF x $THEN".repeat(n)
                    + " ) IS BEGIN NULL; END;"),
        new Shape(
            "引数の間の$IF・$ELSE",
            BASE_COUNT,
            n ->
                "PROCEDURE p ( a NUMBER"
                    + ", a $IF x $THEN y $ELSE z $END NUMBER".repeat(n)
                    + " ) IS BEGIN NULL; END;"),
        new Shape("分岐ごとのBEGIN", BASE_COUNT, n -> "$IF x $THEN BEGIN $ELSE BEGIN $END ".repeat(n)),
        new Shape(
            "分岐ごとの見出し",
            BASE_COUNT / 2,
            n -> "$IF x $THEN PROCEDURE p IS $ELSE PROCEDURE p IS $END ".repeat(n)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pathologicalShapes")
  @DisplayName("analyze: 深い入れ子・長い繰り返しの定義でも、StackOverflowError無しに、長さに比例する時間で終える")
  void testPathologicalDefinitions(Shape shape) {
    final int small = shape.baseCount();
    final int large = shape.baseCount() * SCALE;
    for (final Dbms dbms : Dbms.values()) {
      for (final Wrapping wrapping : wrappings(dbms)) {
        final FunctionEntity smallInput = wrapping.entity(shape.build().apply(small));
        final FunctionEntity largeInput = wrapping.entity(shape.build().apply(large));
        assertTimeoutPreemptively(
            HANG_LIMIT,
            () -> assertLinear(shape, dbms, smallInput, largeInput),
            () -> shape + " / " + dbms);
      }
    }
  }

  /** 長い入力のCPU時間が、短い入力の{@link #ALLOWED_GROWTH}倍以内であることを確かめる */
  private static void assertLinear(
      Shape shape, Dbms dbms, FunctionEntity smallInput, FunctionEntity largeInput) {
    long smallNanos = cpuNanos(dbms, smallInput);
    long largeNanos = cpuNanos(dbms, largeInput);
    if (isTooSlow(smallNanos, largeNanos)) {
      // GC・JITによる外れ値を除くため、1度だけ測り直して小さいほうを採る
      smallNanos = Math.min(smallNanos, cpuNanos(dbms, smallInput));
      largeNanos = Math.min(largeNanos, cpuNanos(dbms, largeInput));
    }
    assertFalse(
        isTooSlow(smallNanos, largeNanos),
        () -> shape + " / " + dbms + ": " + SCALE + "倍の入力で時間が" + ALLOWED_GROWTH + "倍を超えた");
  }

  private static boolean isTooSlow(long smallNanos, long largeNanos) {
    return largeNanos > NEGLIGIBLE.toNanos() && largeNanos > smallNanos * ALLOWED_GROWTH;
  }

  /** 解析にかかった、このスレッドのCPU時間（他のスレッドのGC・JITや、機械の負荷の影響を除くため） */
  private static long cpuNanos(Dbms dbms, FunctionEntity function) {
    // パッケージの解析結果をインスタンスが覚えているため、測るたびに作り直す
    final FunctionTableUsageAnalyzer analyzer = FunctionTableUsageAnalyzer.of(TABLES, dbms);
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final long start = threads.getCurrentThreadCpuTime();
    analyzer.analyze(function);
    return threads.getCurrentThreadCpuTime() - start;
  }

  /** 本体を包む定義の形と、その定義を解析する関数の名前 */
  private record Wrapping(Dbms dbms, String name, String prefix, String suffix) {

    FunctionEntity entity(String body) {
      return function(
          dbms == Dbms.POSTGRESQL ? "sample" : "SCOTT",
          name,
          "A NUMBER",
          dbms == Dbms.POSTGRESQL ? "sql" : "PL/SQL",
          prefix + body + suffix);
    }
  }

  /** 本体を、定義の各形（そのまま・関数の本体・パッケージ本体）に包む */
  private static List<Wrapping> wrappings(Dbms dbms) {
    if (dbms == Dbms.POSTGRESQL) {
      return List.of(
          new Wrapping(dbms, "f", "", ""),
          new Wrapping(dbms, "f", "CREATE FUNCTION f() LANGUAGE sql AS $function$", "$function$"));
    }
    return List.of(
        new Wrapping(dbms, "F", "", ""),
        new Wrapping(dbms, "F", "CREATE OR REPLACE PROCEDURE f IS ", ""),
        new Wrapping(
            dbms,
            "PKG.P",
            "CREATE OR REPLACE PACKAGE pkg AS END; CREATE OR REPLACE PACKAGE BODY pkg AS ",
            ""));
  }

  /** 定義の形は、本体の区切り・サブプログラムを多くのケースで含むようにし、本体はキーワード・文の断片をランダムに並べる */
  private static FunctionEntity randomFunction(Random random, Dbms dbms) {
    final StringBuilder sb = new StringBuilder();
    final String schema = dbms == Dbms.POSTGRESQL ? "sample" : "SCOTT";
    String name = dbms == Dbms.POSTGRESQL ? "f" : "F";
    final boolean closed = random.nextBoolean();
    final int shape = random.nextInt(10);
    if (shape == 0) {
      // 本体の区切りの無い定義
    } else if (dbms == Dbms.POSTGRESQL) {
      sb.append(
          shape % 2 == 0
              ? "CREATE FUNCTION f() SET search_path TO 'other', 'sample' AS $function$ "
              : "CREATE FUNCTION f() AS $function$ ");
    } else if (shape % 2 == 0) {
      sb.append("CREATE OR REPLACE PROCEDURE f AUTHID CURRENT_USER IS ");
      sb.append(random.nextBoolean() ? "BEGIN " : "");
    } else {
      sb.append("CREATE OR REPLACE PACKAGE pkg AS END; CREATE OR REPLACE PACKAGE BODY pkg AS ");
      sb.append("PROCEDURE p0 ( a NUMBER ) IS BEGIN ");
      name = "PKG.P0";
    }
    final int length = random.nextInt(60);
    for (int i = 0; i < length; i++) {
      final boolean statement = random.nextInt(10) < 4;
      final List<String> choices = statement ? STATEMENTS : VOCABULARY;
      sb.append(choices.get(random.nextInt(choices.size()))).append(' ');
    }
    if (closed) {
      if (dbms == Dbms.POSTGRESQL) {
        sb.append(shape == 0 ? "" : "$function$");
      } else if (shape % 2 == 1) {
        sb.append("END p0 ;");
      }
    }
    return function(
        schema,
        name,
        random.nextBoolean() ? "" : "A NUMBER",
        dbms == Dbms.POSTGRESQL ? "plpgsql" : "PL/SQL",
        sb.toString());
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
