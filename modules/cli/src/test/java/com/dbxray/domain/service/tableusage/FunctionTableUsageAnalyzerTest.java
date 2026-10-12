package com.dbxray.domain.service.tableusage;

import static com.dbxray.testsupport.EntityFixtures.function;
import static com.dbxray.testsupport.EntityFixtures.table;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** FunctionTableUsageAnalyzer の名前の解決・出力対象との照合・状態のテスト（ケースの一覧はTableUsageMetamorphicTestでも使う） */
class FunctionTableUsageAnalyzerTest {

  /** PostgreSQLの出力対象のテーブル */
  static final Tables POSTGRES_TABLES =
      Tables.of(
          List.of(
              table("sample", "employee", TableType.TABLE),
              table("sample", "department", TableType.TABLE),
              table("sample", "employee_view", TableType.VIEW),
              table("sample", "project", TableType.MATERIALIZED_VIEW),
              table("other", "employee", TableType.TABLE),
              table("other", "project", TableType.TABLE),
              table("sample", "MixedCase", TableType.TABLE),
              table("other", "audit_log", TableType.TABLE)));

  /** Oracleの出力対象のテーブル */
  static final Tables ORACLE_TABLES =
      Tables.of(
          List.of(
              table("SCOTT", "EMP", TableType.TABLE),
              table("SCOTT", "DEPT", TableType.TABLE),
              table("SCOTT", "EMP_V", TableType.VIEW),
              table("HR", "EMP", TableType.TABLE),
              table("HR", "JOBS", TableType.TABLE),
              table("SCOTT", "Mixed", TableType.TABLE)));

  /**
   * 解析のテストケース1件
   *
   * @param overloadCount カタログ上の同名の関数・プロシージャ（オーバーロード）の数
   * @param header PostgreSQLの見出しに足す行（{@code SET search_path}等）。Oracleでは使わない
   * @param body PostgreSQLはドル引用符の中身、Oracleは定義全体（{@link #definition}は、PostgreSQLでは見出しを加えた定義全体）
   * @param expected {@link #describe}の記法
   */
  record AnalyzerCase(
      String title,
      Dbms dbms,
      String functionName,
      String arguments,
      int overloadCount,
      String header,
      String body,
      String expected) {

    @Override
    public String toString() {
      return (dbms == Dbms.POSTGRESQL ? "PG: " : "ORA: ") + title;
    }

    /** 変形していない定義（PostgreSQLは見出しとドル引用符で囲んだ本体を含む定義全体、Oracleは本体と同じ） */
    String definition() {
      if (dbms == Dbms.POSTGRESQL) {
        return "CREATE OR REPLACE FUNCTION sample."
            + functionName
            + "()\n RETURNS void\n LANGUAGE plpgsql\n"
            + header
            + "AS $function$\n"
            + body
            + "\n$function$\n";
      }
      return body;
    }

    FunctionTableUsage analyze() {
      return analyzeDefinition(definition());
    }

    /** 変形した定義を、このケースの関数の定義として解析する */
    FunctionTableUsage analyzeDefinition(String transformedDefinition) {
      final Tables tables = dbms == Dbms.POSTGRESQL ? POSTGRES_TABLES : ORACLE_TABLES;
      return FunctionTableUsageAnalyzer.of(tables, dbms)
          .analyze(functionEntity(transformedDefinition));
    }

    /** 変形した定義を持つ、このケースの関数 */
    FunctionEntity functionEntity(String transformedDefinition) {
      return function(
          dbms == Dbms.POSTGRESQL ? "sample" : "SCOTT",
          functionName,
          overloadCount,
          arguments,
          dbms == Dbms.POSTGRESQL ? "plpgsql" : "PL/SQL",
          transformedDefinition);
    }

    /** 期待値に現れるテーブルの名前（変形テストで、コメント・文字列の中に書くDMLの対象に使う） */
    List<String> expectedNames() {
      return ExpectedNotation.tableNames(expected);
    }
  }

  static AnalyzerCase pg(String title, String header, String body, String expected) {
    return new AnalyzerCase(title, Dbms.POSTGRESQL, "f", "", 1, header, body, expected);
  }

  static AnalyzerCase ora(
      String title, String name, String arguments, String body, String expected) {
    return ora(title, name, arguments, 1, body, expected);
  }

  static AnalyzerCase ora(
      String title,
      String name,
      String arguments,
      int overloadCount,
      String body,
      String expected) {
    return new AnalyzerCase(title, Dbms.ORACLE, name, arguments, overloadCount, "", body, expected);
  }

  private static final String PACKAGE =
      """
      CREATE OR REPLACE PACKAGE pkg AS
        PROCEDURE load(p_id NUMBER);
        PROCEDURE load(p_name VARCHAR2);
        PROCEDURE same(a NUMBER);
        PROCEDURE same(a VARCHAR2);
      END pkg;

      CREATE OR REPLACE PACKAGE BODY pkg AS
        PROCEDURE load(p_id NUMBER) IS
        BEGIN
          MERGE INTO emp e USING hr.jobs j ON (e.job = j.id)
            WHEN MATCHED THEN UPDATE SET e.sal = j.min_sal;
        END load;
        PROCEDURE load(p_name VARCHAR2) IS
        BEGIN
          DELETE FROM dept WHERE dname = p_name;
        END load;
        PROCEDURE same(a NUMBER) IS BEGIN INSERT INTO emp (id) VALUES (a); END;
        PROCEDURE same(a VARCHAR2) IS BEGIN EXECUTE IMMEDIATE a; SELECT 1 INTO v FROM dept; END;
      END pkg;
      """;

  static List<AnalyzerCase> postgresCases() {
    return List.of(
        pg("スキーマ修飾", "", "SELECT * FROM sample.employee;", "sample.employee:R"),
        pg("大文字の名前を畳み込む", "", "SELECT * FROM SAMPLE.EMPLOYEE;", "sample.employee:R"),
        pg("引用符付きは大文字小文字を保つ", "", "SELECT * FROM sample.\"MixedCase\";", "sample.MixedCase:R"),
        pg("引用符の無い名前は小文字に畳み込むため一致しない", "", "SELECT * FROM sample.MixedCase;", "-"),
        pg(
            "出力対象外",
            "",
            "SELECT * FROM sample.nothing, pg_catalog.pg_class, information_schema.tables;",
            "-"),
        pg("修飾が無い名前は候補を持つ", "", "SELECT * FROM department;", "(search_path).department:R{sample}"),
        pg("候補が複数", "", "DELETE FROM employee;", "(search_path).employee:D{sample,other}"),
        pg("候補が無ければ載せない", "", "SELECT * FROM nothing, dual;", "-"),
        pg(
            "search_pathの先頭から出力対象のあるスキーマに決める",
            " SET search_path TO 'other', 'sample'\n",
            "SELECT * FROM employee JOIN department ON true;",
            "other.employee:R, sample.department:R"),
        pg(
            "search_pathのどのスキーマにも無ければ載せない",
            " SET search_path TO 'public'\n",
            "SELECT * FROM employee;",
            "-"),
        pg(
            "search_pathが$userだけなら決めない",
            " SET search_path TO '$user', 'pg_catalog'\n",
            "SELECT * FROM employee;",
            "(search_path).employee:R{sample,other}"),
        pg(
            "search_pathが空の文字列なら出力対象のスキーマが無いため載せない",
            " SET search_path TO ''\n",
            "SELECT * FROM employee;",
            "-"),
        pg(
            "search_pathがpg_catalogとpg_tempだけなら出力対象のスキーマが無いため載せない",
            " SET search_path TO 'pg_catalog', 'pg_temp'\n",
            "SELECT * FROM employee;",
            "-"),
        pg(
            "search_pathがpg_catalogだけでも、修飾したテーブルは決める",
            " SET search_path TO 'pg_catalog'\n",
            "SELECT * FROM employee, sample.department;",
            "sample.department:R"),
        pg(
            "search_pathがDEFAULTなら指定が無いものとして候補を持つ",
            " SET search_path TO DEFAULT\n",
            "SELECT * FROM employee;",
            "(search_path).employee:R{sample,other}"),
        pg(
            "本体の中のSET文は読まず、見出しの指定で決める",
            " SET search_path TO 'sample'\n",
            "SET LOCAL search_path TO 'other'; SELECT * FROM employee;",
            "sample.employee:R"),
        pg(
            "見出しに指定が無ければ、本体の中のSET文があっても候補を持つ",
            "",
            "SET search_path TO 'other'; SELECT * FROM employee;",
            "(search_path).employee:R{sample,other}"),
        pg(
            "set_configで変数を渡しても読まず、候補を持つ（動的SQLとしない）",
            "",
            "PERFORM set_config('search_path', v_schema, true); SELECT * FROM employee;",
            "(search_path).employee:R{sample,other}"),
        pg(
            "EXECUTEで組み立てたSET文は読まず、候補を持つ（動的SQL）",
            "",
            "EXECUTE 'SET search_path TO ' || v_schema; SELECT * FROM employee;",
            "(search_path).employee:R{sample,other} [dyn EXECUTE]"),
        pg(
            "ビュー・マテリアライズドビュー",
            "",
            "SELECT * FROM sample.employee_view, sample.project;",
            "sample.employee_view:R, sample.project:R"),
        pg(
            "同じテーブルの操作を合わせる",
            "",
            "INSERT INTO sample.employee SELECT * FROM sample.employee;",
            "sample.employee:CR"),
        pg(
            "スキーマが決まった行をスキーマ名・テーブル名の順に、決まらない行を名前の順に並べる",
            "",
            "DELETE FROM project; UPDATE sample.employee SET x = 1; INSERT INTO other.audit_log"
                + " VALUES (1); SELECT * FROM department; SELECT * FROM sample.department;",
            "other.audit_log:C, sample.department:R, sample.employee:U, (search_path).department:R{sample},"
                + " (search_path).project:D{sample,other}"),
        pg(
            "動的SQL",
            "",
            "EXECUTE format('DELETE FROM %I', t); SELECT * FROM sample.employee;",
            "sample.employee:R [dyn EXECUTE]"),
        pg(
            "CTEの名前は出力対象の同名のテーブルに当てない",
            "",
            "WITH employee AS (SELECT * FROM sample.department) SELECT * FROM employee;",
            "sample.department:R"),
        pg("同じ名前の関数の呼び出し", "", "SELECT * FROM employee(1); PERFORM sample.employee(2);", "-"),
        pg(
            "PL/pgSQLの本体",
            "",
            """
            DECLARE
              r record;
            BEGIN
              -- 退職者を削除する
              FOR r IN SELECT * FROM sample.employee WHERE retired LOOP
                DELETE FROM sample.employee WHERE id = r.id;
                INSERT INTO other.audit_log (msg) VALUES ('DELETE FROM department');
              END LOOP;
              /* UPDATE sample.department SET x = 1; */
            END;
            """,
            "other.audit_log:C, sample.employee:RD"));
  }

  static List<AnalyzerCase> oracleCases() {
    return List.of(
        ora(
            "定義者権限では修飾の無い名前を所有者のスキーマに決め、無ければシノニム経由の候補とする",
            "P",
            "",
            "CREATE OR REPLACE PROCEDURE p IS v NUMBER; BEGIN UPDATE emp SET sal = 0; SELECT 1"
                + " INTO v FROM jobs; INSERT INTO hr.jobs VALUES (1); END;",
            "HR.JOBS:C, SCOTT.EMP:U, (synonym).JOBS:R{HR}"),
        ora(
            "実行者権限では修飾の無い名前を決めず候補を持つ",
            "P",
            "",
            "CREATE OR REPLACE PROCEDURE p AUTHID CURRENT_USER IS v NUMBER; BEGIN DELETE FROM emp;"
                + " SELECT 1 INTO v FROM jobs; SELECT 1 INTO v FROM scott.dept; END;",
            "SCOTT.DEPT:R, (current_user).EMP:D{SCOTT,HR}, (current_user).JOBS:R{HR}"),
        ora(
            "DUAL・DBリンク・引数の型は載せない",
            "F",
            "",
            "CREATE OR REPLACE FUNCTION f(p emp%ROWTYPE) RETURN DATE IS v DATE; BEGIN SELECT"
                + " sysdate INTO v FROM dual; DELETE FROM emp@remote; RETURN v; END;",
            "-"),
        ora(
            "引用符付きの名前",
            "P",
            "",
            "CREATE OR REPLACE PROCEDURE p IS BEGIN DELETE FROM \"Mixed\"; DELETE FROM mixed; END;",
            "SCOTT.Mixed:D"),
        ora(
            "ビュー",
            "P",
            "",
            "CREATE OR REPLACE PROCEDURE p IS BEGIN FOR r IN (SELECT * FROM emp_v) LOOP NULL; END"
                + " LOOP; END;",
            "SCOTT.EMP_V:R"),
        ora(
            "パッケージのオーバーロードを引数名で決める（1つ目）",
            "PKG.LOAD",
            "P_ID NUMBER",
            2,
            PACKAGE,
            "HR.JOBS:R, SCOTT.EMP:U"),
        ora(
            "パッケージのオーバーロードを引数名で決める（2つ目）",
            "PKG.LOAD",
            "P_NAME VARCHAR2",
            2,
            PACKAGE,
            "SCOTT.DEPT:D"),
        ora(
            "引数名で決まらないオーバーロードはすべての本体からまとめる",
            "PKG.SAME",
            "A NUMBER",
            2,
            PACKAGE,
            "SCOTT.DEPT:R, SCOTT.EMP:C [dyn EXECUTE IMMEDIATE] [overloadsMerged]"));
  }

  static Stream<AnalyzerCase> allCases() {
    return Stream.concat(postgresCases().stream(), oracleCases().stream());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allCases")
  @DisplayName("analyze: 名前を解決し、出力対象のテーブルと一致したものだけを操作とともに返す")
  void testAnalyze(AnalyzerCase analyzerCase) {
    assertEquals(analyzerCase.expected(), describe(analyzerCase.analyze()));
  }

  @Test
  @DisplayName("analyze: スキーマが決まらない行は、候補・区分（候補の区分がばらつくなら空）を持つ")
  void testUndeterminedRow() {
    final FunctionTableUsage usage = pg("", "", "SELECT * FROM project, department;", "").analyze();

    final TableUsage department = usage.tables().get(0);
    assertFalse(department.schemaDetermined());
    assertEquals("", department.schemaName());
    assertEquals("department", department.tableName());
    assertEquals(List.of("sample"), department.schemaCandidates());
    assertEquals(Optional.of(TableType.TABLE), department.tableType());
    final TableUsage project = usage.tables().get(1);
    assertEquals(List.of("sample", "other"), project.schemaCandidates());
    assertEquals(Optional.empty(), project.tableType());
  }

  @Test
  @DisplayName("analyze: スキーマが決まった行は、出力対象のテーブル（区分を含む）を持つ")
  void testDeterminedRow() {
    final TableUsage row =
        pg("", "", "UPDATE sample.employee_view SET x = 1;", "").analyze().tables().get(0);

    assertTrue(row.schemaDetermined());
    assertEquals("sample", row.schemaName());
    assertEquals(List.of(), row.schemaCandidates());
    assertEquals(Optional.of(TableType.VIEW), row.tableType());
    assertEquals(List.of(CrudOperation.UPDATE), row.orderedOperations());
  }

  @Test
  @DisplayName("analyze: 言語が対象外・定義が無い・wrap・サブプログラムが見つからない場合は、その状態を返す")
  void testNotAnalyzable() {
    final FunctionTableUsageAnalyzer postgres =
        FunctionTableUsageAnalyzer.of(POSTGRES_TABLES, Dbms.POSTGRESQL);
    final FunctionTableUsage python =
        postgres.analyze(function("sample", "f", "", "plpython3u", "CREATE ..."));
    assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, python.status());
    assertEquals("plpython3u", python.language());
    assertEquals(
        "no_definition", describe(postgres.analyze(function("sample", "f", "", "sql", ""))));

    final FunctionTableUsageAnalyzer oracle =
        FunctionTableUsageAnalyzer.of(ORACLE_TABLES, Dbms.ORACLE);
    assertEquals(
        "wrapped",
        describe(oracle.analyze(oracleFunction("F", "CREATE OR REPLACE FUNCTION f wrapped\na0"))));
    assertEquals(
        "subprogram_not_found", describe(oracle.analyze(oracleFunction("PKG.NONE", PACKAGE))));
    assertEquals(
        "unsupported_language:JAVA",
        describe(
            oracle.analyze(
                oracleFunction(
                    "F",
                    "CREATE FUNCTION f RETURN NUMBER AS LANGUAGE JAVA NAME 'X.f() return int';"))));
  }

  @Test
  @DisplayName("analyze: 字句を最後まで読めなかった定義は、読めた範囲の結果に不完全の印を付ける")
  void testIncomplete() {
    final FunctionTableUsage usage =
        FunctionTableUsageAnalyzer.of(POSTGRES_TABLES, Dbms.POSTGRESQL)
            .analyze(
                function(
                    "sample",
                    "f",
                    "",
                    "plpgsql",
                    "CREATE FUNCTION f() AS $function$ BEGIN DELETE FROM sample.employee; /* x"));

    assertEquals("sample.employee:D [incomplete]", describe(usage));
  }

  @Test
  @DisplayName("analyze: 同じインスタンスで同じパッケージの複数のサブプログラムを解析しても、それぞれの本体から抽出する")
  void testSamePackageSubprograms() {
    final FunctionTableUsageAnalyzer analyzer =
        FunctionTableUsageAnalyzer.of(ORACLE_TABLES, Dbms.ORACLE);

    assertEquals(
        "SCOTT.DEPT:D",
        describe(analyzer.analyze(oracleFunction("PKG.LOAD", "P_NAME VARCHAR2", PACKAGE))));
    assertEquals(
        "HR.JOBS:R, SCOTT.EMP:U",
        describe(analyzer.analyze(oracleFunction("PKG.LOAD", "P_ID NUMBER", PACKAGE))));
  }

  @Test
  @DisplayName("analyze: 同じテーブルキーが重複しても、スキーマが決まらない行の候補を重複させない")
  void testDuplicateTableKeysAreNotRepeatedAsCandidates() {
    final Tables tables =
        Tables.of(
            List.of(
                table("sample", "employee", TableType.TABLE),
                table("sample", "employee", TableType.VIEW),
                table("other", "employee", TableType.TABLE)));

    final AnalyzerCase analyzerCase = pg("", "", "SELECT * FROM employee;", "");

    final TableUsage usage =
        FunctionTableUsageAnalyzer.of(tables, Dbms.POSTGRESQL)
            .analyze(analyzerCase.functionEntity(analyzerCase.definition()))
            .tables()
            .get(0);

    assertEquals(List.of("sample", "other"), usage.schemaCandidates());
    assertEquals(Optional.of(TableType.TABLE), usage.tableType());
  }

  @Test
  @DisplayName("analyze: 長いsearch_pathと大量の参照を持つ定義でも、参照の数に比例する時間で解析する")
  void testLongSearchPathIsAnalyzedInLinearTime() {
    final int count = 40_000;
    final String header =
        " SET search_path TO "
            + IntStream.range(0, count)
                .mapToObj(i -> "'schema" + i + "'")
                .collect(Collectors.joining(", "))
            + ", 'other'\n";
    final String body = "SELECT * FROM employee;\n".repeat(count);
    final AnalyzerCase analyzerCase = pg("", header, body, "");

    final FunctionTableUsage usage =
        assertTimeoutPreemptively(Duration.ofSeconds(10), analyzerCase::analyze);

    assertEquals("other.employee:R", describe(usage));
  }

  @Test
  @DisplayName("analyze: 出力対象のテーブルが無くても、動的SQLだけを返す")
  void testNoTables() {
    final FunctionTableUsage usage =
        FunctionTableUsageAnalyzer.of(Tables.of(List.of()), Dbms.ORACLE)
            .analyze(
                oracleFunction(
                    "P",
                    "CREATE PROCEDURE p IS BEGIN DELETE FROM emp; EXECUTE IMMEDIATE 'x'; END;"));

    assertEquals("[dyn EXECUTE IMMEDIATE]", describe(usage));
  }

  /**
   * 解析の結果を短い記法にするメソッド<br>
   * 行は{@code スキーマ.テーブル:操作}、スキーマが決まらない行は{@code (決まり方).テーブル:操作{候補,…}}。解析しなかった場合は状態（と言語）
   */
  static String describe(FunctionTableUsage usage) {
    if (usage.status() != TableUsageStatus.ANALYZED) {
      return usage.status().name().toLowerCase(Locale.ROOT)
          + (usage.language().isEmpty() ? "" : ":" + usage.language());
    }
    final List<String> parts = new ArrayList<>();
    final String rows =
        usage.tables().stream()
            .map(
                row ->
                    (row.schemaDetermined() ? row.schemaName() : "(" + row.resolution().id() + ")")
                        + "."
                        + row.tableName()
                        + ":"
                        + row.orderedOperations().stream()
                            .map(CrudOperation::letter)
                            .collect(Collectors.joining())
                        + (row.schemaDetermined()
                            ? ""
                            : "{" + String.join(",", row.schemaCandidates()) + "}"))
            .collect(Collectors.joining(", "));
    if (!rows.isEmpty()) {
      parts.add(rows);
    }
    if (!usage.dynamicSql().isEmpty()) {
      parts.add(
          "[dyn "
              + usage.dynamicSql().stream()
                  .map(DynamicSqlKind::label)
                  .collect(Collectors.joining(","))
              + "]");
    }
    if (usage.incomplete()) {
      parts.add("[incomplete]");
    }
    if (usage.overloadsMerged()) {
      parts.add("[overloadsMerged]");
    }
    return parts.isEmpty() ? "-" : String.join(" ", parts);
  }

  private static FunctionEntity oracleFunction(String name, String definition) {
    return oracleFunction(name, "", definition);
  }

  private static FunctionEntity oracleFunction(String name, String arguments, String definition) {
    return function("SCOTT", name, arguments, "PL/SQL", definition);
  }
}
