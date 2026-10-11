package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文法から生成した妥当なOracleのパッケージで、サブプログラムごとの抽出結果が正解と一致することを確かめるテスト<br>
 * 変形テスト（{@link TableUsageMetamorphicTest}）は字句の分け方に依存するため、サブプログラムの範囲の切り出し（{@link
 * PackageSubprograms}）の誤りはこちらで検知する。サブプログラムごとに自身だけが使うテーブルを1つ（入れ子のサブプログラムがあればその分も） 本体の任意の深さに置き、{@code
 * IF}・{@code LOOP}・{@code CASE}文・{@code CASE}式（{@code FOR}の範囲・{@code
 * WHILE}の条件を含む）・ブロック・ラベル・条件付きコンパイル・列名{@code function}等を組み合わせる
 */
class GeneratedPackagesTest {

  private static final int PACKAGES = 3_000;

  private static final int MAX_DEPTH = 3;

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
                    new FunctionEntity(
                        "db",
                        "S",
                        "PKG." + expected.name,
                        1,
                        1,
                        "PROCEDURE",
                        expected.arguments,
                        "",
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

  /** パッケージ1つ分の生成器（再帰で文を組み立てる。テストのためスタックの深さは{@link #MAX_DEPTH}で抑える） */
  private static final class Generator {

    private final Random random;
    private final List<TableEntity> tables = new ArrayList<>();
    private int labelCount;

    private Generator(Random random) {
      this.random = random;
    }

    private Generated packageDefinition() {
      final StringBuilder spec = new StringBuilder("CREATE OR REPLACE PACKAGE pkg AS\n");
      final StringBuilder body = new StringBuilder("CREATE OR REPLACE PACKAGE BODY pkg AS\n");
      final List<Expected> expected = new ArrayList<>();
      body.append("  g_flag BOOLEAN := TRUE;\n");
      if (random.nextBoolean()) {
        body.append("  CURSOR c_all IS SELECT d.function AS fn FROM dual d;\n");
      }
      final int count = 1 + random.nextInt(5);
      for (int k = 0; k < count; k++) {
        final String name = "P" + k;
        if (random.nextInt(4) == 0) {
          // 引数名だけが異なるオーバーロード（本体の並びは仕様部と逆にする）
          spec.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          spec.append("  PROCEDURE ").append(name).append("(b VARCHAR2);\n");
          body.append(subprogram(name, "b VARCHAR2", name + "_B", expected, "B VARCHAR2"));
          body.append(subprogram(name, "a NUMBER", name + "_A", expected, "A NUMBER"));
        } else {
          spec.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          if (random.nextInt(3) == 0) {
            body.append("  PROCEDURE ").append(name).append("(a NUMBER);\n");
          }
          body.append(subprogram(name, "a NUMBER", name, expected, "A NUMBER"));
        }
      }
      spec.append("END pkg;\n\n");
      if (random.nextBoolean()) {
        body.append("BEGIN\n  ").append(statements(0, new boolean[] {true})).append('\n');
      }
      body.append("END pkg;\n");
      return new Generated(spec.toString() + body, tables, expected);
    }

    /** サブプログラム1つ（宣言部に入れ子のサブプログラムを置くことがある） */
    private String subprogram(
        String name, String parameters, String table, List<Expected> expected, String arguments) {
      final StringBuilder sb = new StringBuilder("  PROCEDURE ").append(name);
      sb.append('(').append(parameters).append(')');
      if (random.nextInt(4) == 0) {
        sb.append(" ACCESSIBLE BY (PROCEDURE other_proc)");
      }
      sb.append(" IS\n    v NUMBER := ").append(expression(0)).append(";\n");
      String nestedTable = null;
      if (random.nextInt(3) == 0) {
        nestedTable = "N_" + table;
        sb.append(
            "    FUNCTION inner_f(x NUMBER) RETURN NUMBER RESULT_CACHE RELIES_ON (dual) IS\n");
        sb.append("    BEGIN\n      ");
        sb.append(statementsWithTable(1, nestedTable));
        sb.append("\n      RETURN x;\n    END inner_f;\n");
      }
      sb.append("  BEGIN\n    ").append(statementsWithTable(0, "T_" + table)).append("\n");
      sb.append("  END ").append(name).append(";\n");
      final List<String> results = new ArrayList<>();
      if (nestedTable != null) {
        results.add("S." + nestedTable + ":D");
        tables.add(table(nestedTable));
      }
      results.add("S.T_" + table + ":D");
      tables.add(table("T_" + table));
      expected.add(new Expected(name, arguments, String.join(", ", results)));
      return sb.toString();
    }

    /** 文の並びのどこかに、テーブルの削除を1回だけ置く */
    private String statementsWithTable(int depth, String table) {
      final boolean[] placed = {false};
      String statements = statements(depth, placed, "DELETE FROM " + table + " WHERE id = v;");
      if (!placed[0]) {
        statements += " DELETE FROM " + table + " WHERE id = v;";
      }
      return statements;
    }

    private String statements(int depth, boolean[] placed) {
      return statements(depth, placed, null);
    }

    private String statements(int depth, boolean[] placed, String dml) {
      final StringBuilder sb = new StringBuilder();
      final int count = 1 + random.nextInt(3);
      for (int n = 0; n < count; n++) {
        if (dml != null && !placed[0] && random.nextInt(4) == 0) {
          sb.append(dml).append(' ');
          placed[0] = true;
        }
        sb.append(statement(depth, placed, dml)).append(' ');
      }
      return sb.toString().strip();
    }

    private String statement(int depth, boolean[] placed, String dml) {
      final int choice = depth >= MAX_DEPTH ? random.nextInt(3) : random.nextInt(14);
      final int next = depth + 1;
      return switch (choice) {
        case 0 -> "NULL;";
        case 1 -> "v := " + expression(depth) + ";";
        case 2 ->
            "SELECT e.function AS fn INTO v FROM dual e WHERE e.procedure = "
                + expression(depth)
                + ";";
        case 3 ->
            "IF "
                + condition(depth)
                + " THEN "
                + statements(next, placed, dml)
                + (random.nextBoolean()
                    ? " ELSIF " + condition(depth) + " THEN " + statements(next, placed, dml)
                    : "")
                + (random.nextBoolean() ? " ELSE " + statements(next, placed, dml) : "")
                + " END IF;";
        case 4 -> "LOOP " + statements(next, placed, dml) + " EXIT WHEN v > 1; END LOOP;";
        case 5 ->
            "FOR i IN 1 .. "
                + expression(depth)
                + " LOOP "
                + statements(next, placed, dml)
                + " END LOOP;";
        case 6 ->
            "WHILE " + condition(depth) + " LOOP " + statements(next, placed, dml) + " END LOOP;";
        case 7 ->
            "FOR r IN (SELECT d.function AS fn FROM dual d) LOOP "
                + statements(next, placed, dml)
                + " END LOOP;";
        case 8 ->
            "CASE v WHEN 1 THEN "
                + statements(next, placed, dml)
                + " ELSE "
                + statements(next, placed, dml)
                + " END CASE;";
        case 9 ->
            "CASE WHEN "
                + condition(depth)
                + " THEN "
                + statements(next, placed, dml)
                + " END CASE;";
        case 10 -> {
          final String label = "l" + labelCount++;
          yield "<<"
              + label
              + ">> DECLARE w NUMBER := "
              + expression(depth)
              + "; BEGIN "
              + statements(next, placed, dml)
              + " END "
              + label
              + ";";
        }
        case 11 ->
            "BEGIN "
                + statements(next, placed, dml)
                + " EXCEPTION WHEN OTHERS THEN "
                + statements(next, placed, dml)
                + " END;";
        case 12 ->
            "$IF $$flag $THEN "
                + statements(next, placed, dml)
                + " $ELSE "
                + statements(next, placed, dml)
                + " $END";
        default ->
            "FOR i IN 1 .. CASE WHEN "
                + condition(depth)
                + " THEN 10 ELSE 20 END LOOP "
                + statements(next, placed, dml)
                + " END LOOP;";
      };
    }

    private String condition(int depth) {
      return switch (random.nextInt(4)) {
        case 0 -> "v > " + expression(depth + 1);
        case 1 -> expression(depth + 1) + " = 1";
        case 2 -> "v IS NULL";
        default -> "g_flag";
      };
    }

    private String expression(int depth) {
      if (depth >= MAX_DEPTH) {
        return random.nextBoolean() ? "1" : "v";
      }
      return switch (random.nextInt(6)) {
        case 0 -> "1";
        case 1 -> "v";
        case 2 ->
            "CASE WHEN "
                + condition(depth + 1)
                + " THEN "
                + expression(depth + 1)
                + " ELSE "
                + expression(depth + 1)
                + " END";
        case 3 -> "CASE v WHEN 1 THEN " + expression(depth + 1) + " END";
        case 4 -> "NVL(" + expression(depth + 1) + ", " + expression(depth + 1) + ")";
        default -> "(" + expression(depth + 1) + " + 1)";
      };
    }

    private static TableEntity table(String name) {
      return new TableEntity("db", "S", "", name, TableType.TABLE, "");
    }
  }
}
