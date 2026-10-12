package com.dbxray.domain.service.tableusage;

import static com.dbxray.testsupport.EntityFixtures.function;
import static com.dbxray.testsupport.EntityFixtures.table;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.Tables;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文法から生成した妥当なOracleのパッケージで、サブプログラムごとの抽出結果が正解と一致することを確かめるテスト<br>
 * 変形テスト（{@link TableUsageMetamorphicTest}）は字句の分け方に依存するため、
 * サブプログラムの範囲の切り出し（{@link PackageSubprograms}）の誤りはこちらで検知する。サブプログラムごとに自身だけが使うテーブルを1つ（入れ子のサブプログラムがあればその分も）本体の任意の深さに置く。
 * 文は{@code IF}・{@code LOOP}・{@code CASE}文・{@code CASE}式（{@code FOR}の範囲・
 * {@code WHILE}の条件を含む）・ブロック・ラベル等を組み合わせ、条件付きコンパイルは、
 * 分岐ごとに{@code BEGIN}・{@code CASE}・見出し・サブプログラムを書き分けた形まで生成する。
 * パッケージの宣言部のカーソル・初期化の処理は、どのサブプログラムにも含まれないテーブルを使い、
 * 列の別名・列名に{@code function}・{@code procedure}を使う
 */
class GeneratedPackagesTest {

  private static final int PACKAGES = 3_000;

  private static final int MAX_DEPTH = 3;

  /** 1つの文の並びに置く文の最大数 */
  private static final int MAX_STATEMENTS = 3;

  /** 1つのパッケージに置くサブプログラム（オーバーロードを除く）の最大数 */
  private static final int MAX_SUBPROGRAMS = 5;

  /** どのサブプログラムにも含まれない、パッケージの宣言部のカーソルが使うテーブル */
  private static final String PACKAGE_TABLE = "T_PKG";

  /** どのサブプログラムにも含まれない、パッケージの初期化の処理が使うテーブル */
  private static final String INIT_TABLE = "T_INIT";

  @Test
  @DisplayName("analyze: 生成した妥当なパッケージのすべてのサブプログラムで、自身と入れ子のサブプログラムのテーブルだけを抽出する")
  void testGeneratedPackages() {
    final Random random = new Random(20261011L);
    for (int p = 0; p < PACKAGES; p++) {
      final Generated generated = new Generator(random).packageDefinition();
      final FunctionTableUsageAnalyzer analyzer =
          FunctionTableUsageAnalyzer.of(Tables.of(generated.tables), Dbms.ORACLE);
      for (final Expected expected : generated.expected) {
        final String actual =
            FunctionTableUsageAnalyzerTest.describe(
                analyzer.analyze(
                    function(
                        "S",
                        "PKG." + expected.name,
                        expected.arguments,
                        "PL/SQL",
                        generated.definition)));
        assertEquals(expected.result, actual, () -> expected.name + "\n" + generated.definition);
      }
    }
  }

  /** 生成したパッケージと、サブプログラムごとの正解 */
  private record Generated(String definition, List<TableEntity> tables, List<Expected> expected) {}

  /**
   * サブプログラム1つの正解
   *
   * @param arguments カタログの引数の並び（オーバーロードを引数名で対応付けるため）
   * @param result {@link FunctionTableUsageAnalyzerTest#describe}の記法
   */
  private record Expected(String name, String arguments, String result) {}

  /** 文を1つ生成する分岐 */
  @FunctionalInterface
  private interface StatementArm {
    String generate(int depth, DmlSlot slot);
  }

  /** 文の並びのどこかに1回だけ置くDML（置いたかを覚える）。置かない並びは{@link #none}を使う */
  private static final class DmlSlot {

    private final String dml;
    private boolean placed;

    private DmlSlot(String dml) {
      this.dml = dml;
    }

    private static DmlSlot of(String dml) {
      return new DmlSlot(dml);
    }

    private static DmlSlot none() {
      return new DmlSlot(null);
    }

    private boolean canPlace() {
      return dml != null && !placed;
    }

    private String place() {
      placed = true;
      return dml;
    }
  }

  /** パッケージ1つ分の生成器（再帰で文を組み立てる。テストのためスタックの深さは{@link #MAX_DEPTH}で抑える） */
  private static final class Generator {

    private final Random random;
    private final List<TableEntity> tables = new ArrayList<>();
    private final Set<String> tableNames = new HashSet<>();
    private int labelCount;

    /** 入れ子にならない文の分岐（深さが上限に達したら、こちらだけから選ぶ） */
    private final List<StatementArm> leafStatements =
        List.of(
            (depth, slot) -> "NULL;",
            (depth, slot) -> "v := " + expression(depth) + ";",
            (depth, slot) ->
                "SELECT e.function AS fn INTO v FROM dual e WHERE e.procedure = "
                    + expression(depth)
                    + ";",
            (depth, slot) ->
                pick(
                    List.of(
                        "SELECT 1 AS function INTO v FROM dual;",
                        "SELECT 1 procedure INTO v FROM dual;",
                        "SELECT function INTO v FROM (SELECT 1 AS function FROM dual);")));

    /** 文を入れ子にする分岐 */
    private final List<StatementArm> nestedStatements =
        List.of(
            this::ifStatement,
            this::loopStatement,
            this::forStatement,
            this::whileStatement,
            this::forQueryStatement,
            this::caseStatement,
            this::searchedCaseStatement,
            this::labeledBlock,
            this::exceptionBlock,
            this::balancedConditional,
            this::elsifConditional,
            this::conditionalBlockStart,
            this::conditionalCaseStart,
            this::forCaseRange);

    private final List<StatementArm> allStatements =
        Stream.concat(leafStatements.stream(), nestedStatements.stream()).toList();

    private final List<IntFunction<String>> leafExpressions = List.of(depth -> "1", depth -> "v");

    private final List<IntFunction<String>> nestedExpressions =
        List.of(
            depth ->
                "CASE WHEN "
                    + condition(depth + 1)
                    + " THEN "
                    + expression(depth + 1)
                    + " ELSE "
                    + expression(depth + 1)
                    + " END",
            depth -> "CASE v WHEN 1 THEN " + expression(depth + 1) + " END",
            depth -> "NVL(" + expression(depth + 1) + ", " + expression(depth + 1) + ")",
            depth -> "(" + expression(depth + 1) + " + 1)");

    private final List<IntFunction<String>> allExpressions =
        Stream.concat(leafExpressions.stream(), nestedExpressions.stream()).toList();

    private final List<IntFunction<String>> conditions =
        List.of(
            depth -> "v > " + expression(depth + 1),
            depth -> expression(depth + 1) + " = 1",
            depth -> "v IS NULL",
            depth -> "g_flag");

    /** サブプログラムの見出し（名前と引数の並びから、{@code IS}までを組み立てる） */
    private final List<BinaryOperator<String>> headers =
        List.of(
            (name, parameters) -> "  PROCEDURE " + name + "(" + parameters + ") IS\n",
            (name, parameters) ->
                "  PROCEDURE "
                    + name
                    + "("
                    + parameters
                    + ") ACCESSIBLE BY (PROCEDURE other_proc) IS\n",
            (name, parameters) ->
                "  PROCEDURE "
                    + name
                    + "("
                    + parameters
                    + ") $IF $$flag $THEN ACCESSIBLE BY (PROCEDURE other_proc) $END IS\n",
            (name, parameters) ->
                "  $IF $$flag $THEN PROCEDURE "
                    + name
                    + "("
                    + parameters
                    + ") IS $ELSE PROCEDURE "
                    + name
                    + "("
                    + parameters
                    + ") IS $END\n");

    /** 問い合わせの列の別名・列名に{@code function}・{@code procedure}を使うカーソル（%sはテーブル） */
    private final List<String> keywordCursors =
        List.of(
            "SELECT d.function AS fn FROM %s d",
            "SELECT 1 AS function FROM %s",
            "SELECT 1 AS function FROM %s WHERE 1 IS NOT NULL",
            "SELECT 1 AS procedure, 2 function FROM %s",
            "SELECT function, procedure FROM (SELECT 1 AS function, 2 AS procedure FROM %s)");

    private Generator(Random random) {
      this.random = random;
    }

    private Generated packageDefinition() {
      final StringBuilder spec = new StringBuilder("CREATE OR REPLACE PACKAGE pkg AS\n");
      final StringBuilder body = new StringBuilder("CREATE OR REPLACE PACKAGE BODY pkg AS\n");
      final List<Expected> expected = new ArrayList<>();
      register(PACKAGE_TABLE);
      register(INIT_TABLE);
      body.append("  g_flag BOOLEAN := TRUE;\n");
      if (oneIn(2)) {
        body.append(cursor("c_all", PACKAGE_TABLE));
      }
      final int count = 1 + random.nextInt(MAX_SUBPROGRAMS);
      for (int k = 0; k < count; k++) {
        final String name = "P" + k;
        if (oneIn(3)) {
          body.append("  $IF $$flag $THEN\n")
              .append(cursor("c_cond" + k, PACKAGE_TABLE))
              .append("  $END\n");
        }
        if (oneIn(4)) {
          // 引数名だけが異なるオーバーロード（本体の並びは仕様部と逆にする）
          spec.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          spec.append("  PROCEDURE ").append(name).append("(b VARCHAR2);\n");
          body.append(subprogram(name, "b VARCHAR2", name + "_B", expected, "B VARCHAR2"));
          body.append(subprogram(name, "a NUMBER", name + "_A", expected, "A NUMBER"));
        } else if (oneIn(5)) {
          // 条件付きコンパイルで、同じサブプログラムを分岐ごとに書く
          spec.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          final boolean nested = oneIn(3);
          body.append("  $IF $$flag $THEN\n");
          body.append(subprogramText(name, "a NUMBER", name, nested));
          body.append("  $ELSE\n");
          body.append(subprogramText(name, "a NUMBER", name, nested));
          body.append("  $END\n");
          expected.add(expectedOf(name, "A NUMBER", name, nested));
        } else {
          spec.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          if (oneIn(3)) {
            body.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          }
          body.append(subprogram(name, "a NUMBER", name, expected, "A NUMBER"));
        }
      }
      spec.append("END pkg;\n\n");
      if (oneIn(2)) {
        body.append(cursor("c_tail", PACKAGE_TABLE));
      }
      if (oneIn(2)) {
        body.append("BEGIN\n  INSERT INTO ").append(INIT_TABLE).append(" (id) VALUES (1);\n  ");
        body.append(statements(0, DmlSlot.none())).append('\n');
      }
      body.append("END pkg;\n");
      return new Generated(spec.toString() + body, tables, expected);
    }

    /** サブプログラム1つの本体と正解（正解は{@code expected}に足す） */
    private String subprogram(
        String name, String parameters, String table, List<Expected> expected, String arguments) {
      final boolean nested = oneIn(3);
      expected.add(expectedOf(name, arguments, table, nested));
      return subprogramText(name, parameters, table, nested);
    }

    /**
     * サブプログラム1つの本体（宣言部に入れ子のサブプログラムを置くことがある）
     *
     * @param nested 宣言部に入れ子のサブプログラムを置くか
     */
    private String subprogramText(String name, String parameters, String table, boolean nested) {
      final StringBuilder sb = new StringBuilder(pick(headers).apply(name, parameters));
      sb.append("    v NUMBER := ").append(expression(0)).append(";\n");
      if (oneIn(3)) {
        sb.append("  ").append(cursor("c_loc", "dual"));
      }
      if (nested) {
        sb.append(
            "    FUNCTION inner_f(x NUMBER) RETURN NUMBER RESULT_CACHE RELIES_ON (dual) IS\n");
        sb.append("    BEGIN\n      ");
        sb.append(statementsWithTable(1, "N_" + table));
        sb.append("\n      RETURN x;\n    END inner_f;\n");
      }
      sb.append("  BEGIN\n    ").append(statementsWithTable(0, "T_" + table)).append("\n");
      sb.append("  END ").append(name).append(";\n");
      return sb.toString();
    }

    /** サブプログラムの正解を求め、使うテーブルを出力対象として登録する */
    private Expected expectedOf(String name, String arguments, String table, boolean nested) {
      final List<String> results = new ArrayList<>();
      if (nested) {
        results.add("S.N_" + table + ":D");
        register("N_" + table);
      }
      results.add("S.T_" + table + ":D");
      register("T_" + table);
      return new Expected(name, arguments, String.join(", ", results));
    }

    private void register(String tableName) {
      if (tableNames.add(tableName)) {
        tables.add(table("S", tableName));
      }
    }

    /** パッケージ・サブプログラムの宣言部のカーソル（列の別名・列名に{@code function}・{@code procedure}を使う） */
    private String cursor(String name, String table) {
      return "  CURSOR " + name + " IS " + pick(keywordCursors).formatted(table) + ";\n";
    }

    /** 文の並びのどこかに、テーブルの削除を1回だけ置く */
    private String statementsWithTable(int depth, String table) {
      final String dml = "DELETE FROM " + table + " WHERE id = v;";
      final DmlSlot slot = DmlSlot.of(dml);
      String statements = statements(depth, slot);
      if (!slot.placed) {
        statements += " " + dml;
      }
      return statements;
    }

    private String statements(int depth, DmlSlot slot) {
      final StringBuilder sb = new StringBuilder();
      final int count = 1 + random.nextInt(MAX_STATEMENTS);
      for (int n = 0; n < count; n++) {
        if (slot.canPlace() && oneIn(4)) {
          sb.append(slot.place()).append(' ');
        }
        sb.append(statement(depth, slot)).append(' ');
      }
      return sb.toString().strip();
    }

    private String statement(int depth, DmlSlot slot) {
      final List<StatementArm> arms = depth >= MAX_DEPTH ? leafStatements : allStatements;
      return pick(arms).generate(depth, slot);
    }

    private String ifStatement(int depth, DmlSlot slot) {
      final int next = depth + 1;
      return "IF "
          + condition(depth)
          + " THEN "
          + statements(next, slot)
          + (random.nextBoolean()
              ? " ELSIF " + condition(depth) + " THEN " + statements(next, slot)
              : "")
          + (random.nextBoolean() ? " ELSE " + statements(next, slot) : "")
          + " END IF;";
    }

    private String loopStatement(int depth, DmlSlot slot) {
      return "LOOP " + statements(depth + 1, slot) + " EXIT WHEN v > 1; END LOOP;";
    }

    private String forStatement(int depth, DmlSlot slot) {
      return "FOR i IN 1 .. "
          + expression(depth)
          + " LOOP "
          + statements(depth + 1, slot)
          + " END LOOP;";
    }

    private String whileStatement(int depth, DmlSlot slot) {
      return "WHILE " + condition(depth) + " LOOP " + statements(depth + 1, slot) + " END LOOP;";
    }

    private String forQueryStatement(int depth, DmlSlot slot) {
      return "FOR r IN (SELECT d.function AS fn FROM dual d) LOOP "
          + statements(depth + 1, slot)
          + " END LOOP;";
    }

    private String caseStatement(int depth, DmlSlot slot) {
      return "CASE v WHEN 1 THEN "
          + statements(depth + 1, slot)
          + " ELSE "
          + statements(depth + 1, slot)
          + " END CASE;";
    }

    private String searchedCaseStatement(int depth, DmlSlot slot) {
      return "CASE WHEN "
          + condition(depth)
          + " THEN "
          + statements(depth + 1, slot)
          + " END CASE;";
    }

    private String labeledBlock(int depth, DmlSlot slot) {
      final String label = "l" + labelCount++;
      return "<<"
          + label
          + ">> DECLARE w NUMBER := "
          + expression(depth)
          + "; BEGIN "
          + statements(depth + 1, slot)
          + " END "
          + label
          + ";";
    }

    private String exceptionBlock(int depth, DmlSlot slot) {
      return "BEGIN "
          + statements(depth + 1, slot)
          + " EXCEPTION WHEN OTHERS THEN "
          + statements(depth + 1, slot)
          + " END;";
    }

    /** どの分岐も{@code BEGIN}・{@code END}の数が釣り合う条件付きコンパイル */
    private String balancedConditional(int depth, DmlSlot slot) {
      return "$IF $$flag $THEN "
          + statements(depth + 1, slot)
          + " $ELSE "
          + statements(depth + 1, slot)
          + " $END";
    }

    private String elsifConditional(int depth, DmlSlot slot) {
      return "$IF $$flag $THEN "
          + statements(depth + 1, slot)
          + " $ELSIF $$other $THEN "
          + statements(depth + 1, slot)
          + (random.nextBoolean() ? " $ELSE " + statements(depth + 1, slot) : "")
          + " $END";
    }

    /** 分岐ごとに{@code BEGIN}を書き、{@code END}は{@code $END}の後に1つだけ書く条件付きコンパイル */
    private String conditionalBlockStart(int depth, DmlSlot slot) {
      return "$IF $$flag $THEN BEGIN "
          + statements(depth + 1, slot)
          + " $ELSE BEGIN "
          + statements(depth + 1, slot)
          + " $END "
          + statements(depth + 1, slot)
          + " END;";
    }

    /** 分岐ごとに{@code CASE}文の始まりを書き、{@code END CASE}は{@code $END}の後に1つだけ書く条件付きコンパイル */
    private String conditionalCaseStart(int depth, DmlSlot slot) {
      return "$IF $$flag $THEN CASE v WHEN 1 THEN "
          + statements(depth + 1, slot)
          + " $ELSE CASE WHEN "
          + condition(depth)
          + " THEN "
          + statements(depth + 1, slot)
          + " $END ELSE "
          + statements(depth + 1, slot)
          + " END CASE;";
    }

    private String forCaseRange(int depth, DmlSlot slot) {
      return "FOR i IN 1 .. CASE WHEN "
          + condition(depth)
          + " THEN 10 ELSE 20 END LOOP "
          + statements(depth + 1, slot)
          + " END LOOP;";
    }

    private String condition(int depth) {
      return pick(conditions).apply(depth);
    }

    private String expression(int depth) {
      return pick(depth >= MAX_DEPTH ? leafExpressions : allExpressions).apply(depth);
    }

    private <T> T pick(List<T> choices) {
      return choices.get(random.nextInt(choices.size()));
    }

    /** 1/{@code n}の確率で真になる */
    private boolean oneIn(int n) {
      return random.nextInt(n) == 0;
    }
  }
}
