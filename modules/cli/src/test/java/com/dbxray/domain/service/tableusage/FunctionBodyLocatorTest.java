package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** FunctionBodyLocator の定義からの本体の切り出しのテスト */
class FunctionBodyLocatorTest {

  /** 本体の字句をつないだ文字列（区別できなかった本体は「 || 」で区切る） */
  static String text(FunctionBody body) {
    return body.segments().stream()
        .map(tokens -> tokens.stream().map(SqlToken::text).collect(Collectors.joining(" ")))
        .collect(Collectors.joining(" || "));
  }

  @Nested
  class PostgreSQL {

    private final FunctionBodyLocator locator = new FunctionBodyLocator(Dbms.POSTGRESQL);

    @Test
    @DisplayName("locate: pg_get_functiondefの形から、ドル引用符の中身だけを本体とし、見出しの型のテーブル名は含めない")
    void testDollarQuotedBody() {
      final FunctionBody body =
          locate(
              "plpgsql",
              """
              CREATE OR REPLACE FUNCTION sample.f(p_id integer DEFAULT nextval('s'))
               RETURNS SETOF sample.employee
               LANGUAGE plpgsql
              AS $function$
              BEGIN
                RETURN QUERY SELECT * FROM sample.employee WHERE id = p_id;
              END;
              $function$
              """);

      assertEquals(TableUsageStatus.ANALYZED, body.status());
      assertEquals(
          "BEGIN RETURN QUERY SELECT * FROM sample . employee WHERE id = p_id ; END ;", text(body));
      assertFalse(body.incomplete());
      assertEquals(List.of(), body.searchPath());
      assertEquals(5, body.segments().get(0).get(0).line(), "行番号は定義全体での行番号のまま");
    }

    @Test
    @DisplayName("locate: プロシージャの$procedure$・本体に区切りと同じ文字列があるため延ばした区切りも、中身を本体とする")
    void testProcedureAndExtendedDelimiter() {
      assertEquals(
          "DELETE FROM t ;",
          text(
              locate(
                  "plpgsql",
                  "CREATE OR REPLACE PROCEDURE sample.p()\n LANGUAGE plpgsql\nAS $procedure$"
                      + " DELETE FROM t; $procedure$\n")));
      assertEquals(
          "PERFORM '$function$' ; DELETE FROM t ;",
          text(
              locate(
                  "plpgsql",
                  "CREATE OR REPLACE FUNCTION sample.f()\n RETURNS void\n LANGUAGE plpgsql\nAS"
                      + " $functionx$ PERFORM '$function$'; DELETE FROM t; $functionx$\n")));
    }

    @Test
    @DisplayName("locate: SQL標準の本体（BEGIN ATOMIC・RETURN）は、見出しの後の最上位のBEGIN・RETURNから終わりまでを本体とする")
    void testSqlStandardBody() {
      assertEquals(
          "BEGIN ATOMIC INSERT INTO t VALUES ( 1 ) ; END",
          text(
              locate(
                  "sql",
                  "CREATE OR REPLACE PROCEDURE sample.p(a integer DEFAULT 1)\n LANGUAGE sql\n"
                      + "BEGIN ATOMIC\n INSERT INTO t VALUES (1);\nEND\n")));
      assertEquals(
          "RETURN ( SELECT count ( * ) FROM t )",
          text(
              locate(
                  "sql",
                  "CREATE OR REPLACE FUNCTION sample.f()\n RETURNS TABLE(id integer, employee"
                      + " text)\n LANGUAGE sql\nRETURN (SELECT count(*) FROM t)\n")));
    }

    @Test
    @DisplayName("locate: 関数名・型名のbegin・return（.の直後）と、ATOMICの続かないBEGINは本体の区切りとしない")
    void testBeginAndReturnAsNames() {
      assertEquals(
          "DELETE FROM t ;",
          text(
              locate(
                  "sql",
                  "CREATE OR REPLACE FUNCTION sample.begin()\n RETURNS void\n LANGUAGE sql\n"
                      + "AS $function$DELETE FROM t;$function$\n")));
      assertEquals(
          "DELETE FROM t ;",
          text(
              locate(
                  "plpgsql",
                  "CREATE OR REPLACE FUNCTION sample.return(a sample.begin)\n RETURNS"
                      + " sample.return\n LANGUAGE plpgsql\nAS $function$DELETE FROM t;$function$\n")));
      assertEquals(
          "BEGIN ATOMIC DELETE FROM t ; END",
          text(
              locate(
                  "sql",
                  "CREATE OR REPLACE PROCEDURE sample.begin()\n LANGUAGE sql\n"
                      + "BEGIN ATOMIC\n DELETE FROM t;\nEND\n")));
    }

    @Test
    @DisplayName("locate: SET search_pathの並びを、$user・pg_catalog・pg_tempを除いて読む（文字列は大文字小文字を保つ）")
    void testSearchPath() {
      final FunctionBody body =
          locate(
              "plpgsql",
              """
              CREATE OR REPLACE FUNCTION sample.f()
               RETURNS void
               LANGUAGE plpgsql
               SET work_mem TO '64MB'
               SET search_path TO '$user', 'MySchema', 'pg_catalog', 'it''s', 'pg_temp', 'public'
              AS $function$ SELECT 1 $function$
              """);

      assertEquals(List.of("MySchema", "it's", "public"), body.searchPath());
    }

    @Test
    @DisplayName("locate: 手で書いた形のSET search_path（=・引用符の無い名前・引用符付き識別子）も読む")
    void testHandWrittenSearchPath() {
      assertEquals(
          List.of("sample", "Other"),
          locate(
                  "sql",
                  "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET search_path = Sample,"
                      + " \"Other\", pg_temp_3 AS $$ SELECT 1 $$")
              .searchPath());
      assertEquals(
          List.of(),
          locate(
                  "sql",
                  "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET search_path TO DEFAULT AS $$"
                      + " SELECT 1 $$")
              .searchPath());
      assertEquals(
          List.of("unterminated"),
          locate("sql", "CREATE FUNCTION f() SET search_path TO 'unterminated").searchPath());
      assertEquals(
          List.of(),
          locate("sql", "CREATE FUNCTION f() SET search_path TO").searchPath(),
          "値の無いSET search_pathは指定が無いものとする");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"c", "internal", "plpython3u", "plperl", "plv8", ""})
    @DisplayName("locate: 言語がsql・plpgsql以外は解析せず、言語名を返す")
    void testUnsupportedLanguage(String language) {
      final FunctionBody body =
          locate(language, "CREATE OR REPLACE FUNCTION f() RETURNS int LANGUAGE c AS 'obj', 'sym'");

      assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, body.status());
      assertEquals(language, body.language());
    }

    @Test
    @DisplayName("locate: 言語名の大文字小文字は区別しない")
    void testLanguageCaseInsensitive() {
      assertEquals(
          TableUsageStatus.ANALYZED,
          locate("PLPGSQL", "CREATE FUNCTION f() AS $$ SELECT 1 $$").status());
    }

    @Test
    @DisplayName("locate: 定義が空（空白だけを含む）ならNO_DEFINITION")
    void testNoDefinition() {
      assertEquals(TableUsageStatus.NO_DEFINITION, locate("plpgsql", "").status());
      assertEquals(TableUsageStatus.NO_DEFINITION, locate("plpgsql", " \n ").status());
    }

    @Test
    @DisplayName("locate: 閉じていないドル引用符・本体の中の閉じていない文字列は、不完全の印を立てる")
    void testIncomplete() {
      final FunctionBody unterminatedBody =
          locate("plpgsql", "CREATE FUNCTION f() AS $function$ BEGIN DELETE FROM t;");
      assertTrue(unterminatedBody.incomplete());
      assertEquals("BEGIN DELETE FROM t ;", text(unterminatedBody));

      final FunctionBody unterminatedString =
          locate("plpgsql", "CREATE FUNCTION f() AS $function$ DELETE FROM t; RAISE 'x $function$");
      assertTrue(unterminatedString.incomplete());

      assertFalse(locate("plpgsql", "CREATE FUNCTION f() AS $$ SELECT 'x' $$").incomplete());
    }

    @Test
    @DisplayName("locate: 本体の区切りが見つからない定義は、全体を本体とする")
    void testFallbackToWholeDefinition() {
      assertEquals("SELECT * FROM t", text(locate("sql", "SELECT * FROM t")));
      assertEquals(
          "CREATE FUNCTION f ( ) AS 'x'", text(locate("sql", "CREATE FUNCTION f() AS 'x'")));
    }

    @Test
    @DisplayName("locate: 括弧の中（引数の既定値等）のAS・BEGIN・RETURN・SETは本体の区切りとしない")
    void testKeywordsInParentheses() {
      assertEquals(
          "SELECT 1",
          text(
              locate(
                  "sql",
                  "CREATE FUNCTION f(a text DEFAULT CAST(1 AS text), b int DEFAULT (SELECT 1"
                      + " RETURN)) RETURNS int AS $$ SELECT 1 $$")));
      assertEquals(
          List.of(),
          locate("sql", "CREATE FUNCTION f(SET search_path TO 'x') AS $$ SELECT 1 $$")
              .searchPath());
    }

    private FunctionBody locate(String language, String definition) {
      return locator.locate(
          new FunctionEntity("db", "sample", "f", 1, 1, "FUNCTION", "", "", language, definition));
    }
  }

  @Nested
  class Oracle {

    private final FunctionBodyLocator locator = new FunctionBodyLocator(Dbms.ORACLE);

    private static final String PACKAGE =
        """
        CREATE OR REPLACE PACKAGE pkg AS
          PROCEDURE p1(p_id NUMBER);
          FUNCTION f1 RETURN NUMBER;
          PROCEDURE over(p_id NUMBER);
          PROCEDURE over(p_name VARCHAR2);
        END pkg;

        CREATE OR REPLACE PACKAGE BODY pkg AS
          g_count NUMBER := 0;
          PROCEDURE helper;
          PROCEDURE p1(p_id NUMBER) IS
            PROCEDURE inner_p IS
            BEGIN
              DELETE FROM inner_t;
            END inner_p;
          BEGIN
            IF p_id > 0 THEN
              UPDATE t1 SET x = CASE WHEN p_id = 1 THEN 1 ELSE 2 END;
            END IF;
            CASE p_id WHEN 1 THEN NULL; ELSE NULL; END CASE;
            FOR r IN (SELECT * FROM t2) LOOP
              NULL;
            END LOOP;
            <<blk>>
            DECLARE
              v NUMBER;
            BEGIN
              NULL;
            END blk;
          END p1;
          FUNCTION f1 RETURN NUMBER IS
          BEGIN
            RETURN g_count;
          END;
          PROCEDURE helper IS BEGIN INSERT INTO h VALUES (1); END helper;
          PROCEDURE over(p_id NUMBER) IS BEGIN DELETE FROM o1; END;
          PROCEDURE over(p_name VARCHAR2) IS BEGIN DELETE FROM o2; END;
        BEGIN
          g_count := 1;
        END pkg;
        """;

    @Test
    @DisplayName("locate: 単独の関数は、見出しのIS・ASの後ろ全体を本体とし、引数の型のテーブル名は含めない")
    void testStandaloneFunction() {
      final FunctionBody body =
          locate(
              "F",
              "",
              "CREATE OR REPLACE FUNCTION f (p_row employee%ROWTYPE) RETURN NUMBER IS v NUMBER;"
                  + " BEGIN SELECT 1 INTO v FROM dual; RETURN v; END f;");

      assertEquals(TableUsageStatus.ANALYZED, body.status());
      assertEquals("v NUMBER ; BEGIN SELECT 1 INTO v FROM dual ; RETURN v ; END f ;", text(body));
      assertFalse(body.invokerRights());
      assertFalse(body.overloadsMerged());
    }

    @Test
    @DisplayName("locate: EDITIONABLE・スキーマ修飾・ASの単独のプロシージャも切り出す")
    void testStandaloneProcedure() {
      assertEquals(
          "BEGIN NULL ; END ;",
          text(
              locate(
                  "P",
                  "",
                  "CREATE OR REPLACE EDITIONABLE PROCEDURE \"SCOTT\".p AS BEGIN NULL; END;")));
    }

    @Test
    @DisplayName("locate: AUTHID CURRENT_USERの単独の関数は実行者権限とする")
    void testStandaloneInvokerRights() {
      assertTrue(
          locate("P", "", "CREATE OR REPLACE PROCEDURE p AUTHID CURRENT_USER IS BEGIN NULL; END;")
              .invokerRights());
      assertFalse(
          locate("P", "", "CREATE OR REPLACE PROCEDURE p AUTHID DEFINER IS BEGIN NULL; END;")
              .invokerRights());
    }

    @Test
    @DisplayName("locate: 名前の直後がWRAPPEDならWRAPPED（単独・パッケージの仕様部・パッケージ本体）")
    void testWrapped() {
      assertEquals(
          TableUsageStatus.WRAPPED,
          locate("F", "", "CREATE OR REPLACE FUNCTION f wrapped\na000000\n1\nabcd\n").status());
      assertEquals(
          TableUsageStatus.WRAPPED,
          locate("PKG.P", "", "CREATE OR REPLACE PACKAGE pkg wrapped\na000000\n").status());
      assertEquals(
          TableUsageStatus.WRAPPED,
          locate(
                  "PKG.P",
                  "",
                  "CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; END;\n\n"
                      + "CREATE OR REPLACE PACKAGE BODY pkg wrapped\na000000\n'x\n")
              .status());
    }

    @Test
    @DisplayName("locate: 呼び出し仕様（LANGUAGE JAVA・LANGUAGE C・EXTERNAL）は解析せず、言語名を返す")
    void testCallSpecification() {
      final FunctionBody java =
          locate(
              "F",
              "",
              "CREATE OR REPLACE FUNCTION f RETURN VARCHAR2 AS LANGUAGE JAVA NAME 'X.y() return"
                  + " java.lang.String';");
      assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, java.status());
      assertEquals("JAVA", java.language());

      assertEquals(
          "C",
          locate("P", "", "CREATE OR REPLACE PROCEDURE p AS EXTERNAL LIBRARY lib NAME \"p\";")
              .language());
      assertEquals(
          "C",
          locate("P", "", "CREATE OR REPLACE PROCEDURE p IS LANGUAGE C NAME \"p\";").language());
      assertEquals("LANGUAGE", locate("P", "", "CREATE PROCEDURE p IS LANGUAGE;").language());
    }

    @Test
    @DisplayName("locate: 定義が空ならNO_DEFINITION、想定外の形の定義は全体、IS・ASの無い単独の定義は名前の後ろを本体とする")
    void testUnusualDefinitions() {
      assertEquals(TableUsageStatus.NO_DEFINITION, locate("F", "", "").status());
      assertEquals("SELECT 1 FROM dual", text(locate("F", "", "SELECT 1 FROM dual")));
      assertEquals(
          "RETURN NUMBER ;", text(locate("F", "", "CREATE OR REPLACE FUNCTION f RETURN NUMBER;")));
      assertEquals("", text(locate("F", "", "CREATE OR REPLACE FUNCTION")));
    }

    @Test
    @DisplayName("locate: パッケージのサブプログラムは、パッケージ本体の最上位の同名の範囲を切り出し、入れ子のサブプログラム・CASE・ラベル付きのブロックを含める")
    void testPackageSubprogram() {
      final FunctionBody body = locate("PKG.P1", "P_ID NUMBER", PACKAGE);

      assertEquals(TableUsageStatus.ANALYZED, body.status());
      final String text = text(body);
      assertTrue(text.startsWith("PROCEDURE inner_p IS BEGIN DELETE FROM inner_t ;"), text);
      assertTrue(text.endsWith("BEGIN NULL ; END blk ;"), text);
      assertTrue(text.contains("FOR r IN ( SELECT * FROM t2 ) LOOP"), text);
      assertFalse(text.contains("g_count"), "宣言部の変数・初期化部は含めない: " + text);
      assertFalse(body.incomplete());
    }

    @Test
    @DisplayName("locate: 前方宣言は読み飛ばし、本体の定義の範囲を切り出す（privateなサブプログラムも同じ）")
    void testForwardDeclarationAndPrivateSubprogram() {
      assertEquals("BEGIN INSERT INTO h VALUES ( 1 ) ;", text(locate("PKG.HELPER", "", PACKAGE)));
      assertEquals("BEGIN RETURN g_count ;", text(locate("PKG.F1", "", PACKAGE)));
    }

    @Test
    @DisplayName("locate: オーバーロードは宣言の引数名の並びで1つに決める")
    void testOverloadResolvedByParameterNames() {
      final FunctionBody first = locate("PKG.OVER", "P_ID NUMBER", PACKAGE);
      final FunctionBody second = locate("PKG.OVER", "P_NAME VARCHAR2", PACKAGE);

      assertEquals("BEGIN DELETE FROM o1 ;", text(first));
      assertFalse(first.overloadsMerged());
      assertEquals("BEGIN DELETE FROM o2 ;", text(second));
    }

    @Test
    @DisplayName("locate: 引数名の並びで決まらないオーバーロードは、すべての本体を持ちその旨の印を立てる")
    void testOverloadsMerged() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p(a NUMBER); PROCEDURE p(a VARCHAR2); END;

          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p(a NUMBER) IS BEGIN DELETE FROM t1; END;
            PROCEDURE p(a VARCHAR2) IS BEGIN DELETE FROM t2; END;
          END;
          """;

      final FunctionBody body = locate("PKG.P", "A NUMBER", definition);

      assertTrue(body.overloadsMerged());
      assertEquals("BEGIN DELETE FROM t1 ; || BEGIN DELETE FROM t2 ;", text(body));
    }

    @Test
    @DisplayName("locate: 引数の既定値の括弧の中の名前は引数名としない")
    void testParameterDefaults() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p(a NUMBER DEFAULT f(x, y), "b" IN OUT NOCOPY t%ROWTYPE) IS BEGIN DELETE FROM t1; END;
            PROCEDURE p(x NUMBER) IS BEGIN DELETE FROM t2; END;
          END;
          """;

      assertEquals(
          "BEGIN DELETE FROM t1 ;", text(locate("PKG.P", "A NUMBER, b IN/OUT T", definition)));
    }

    @Test
    @DisplayName("locate: パッケージの仕様部のAUTHID CURRENT_USERは、サブプログラムを実行者権限とする")
    void testPackageInvokerRights() {
      final String definition =
          "CREATE OR REPLACE PACKAGE pkg AUTHID CURRENT_USER AS PROCEDURE p; END;\n\n"
              + "CREATE OR REPLACE PACKAGE BODY pkg AS PROCEDURE p IS BEGIN NULL; END; END;";

      assertTrue(locate("PKG.P", "", definition).invokerRights());
      assertFalse(locate("PKG.P1", "P_ID NUMBER", PACKAGE).invokerRights());
    }

    @Test
    @DisplayName("locate: パッケージ本体が無い・見えないならNO_DEFINITION、同名のサブプログラムが無いならSUBPROGRAM_NOT_FOUND")
    void testPackageBodyOrSubprogramMissing() {
      assertEquals(
          TableUsageStatus.NO_DEFINITION,
          locate("PKG.P", "", "CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; END;\n\n").status());
      assertEquals(
          TableUsageStatus.SUBPROGRAM_NOT_FOUND, locate("PKG.MISSING", "", PACKAGE).status());
      assertEquals(
          TableUsageStatus.SUBPROGRAM_NOT_FOUND,
          locate(
                  "PKG.P",
                  "",
                  "CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; END;\n"
                      + "CREATE OR REPLACE PACKAGE BODY pkg")
              .status(),
          "見出しが途中で終わるパッケージ本体");
      assertEquals(
          TableUsageStatus.SUBPROGRAM_NOT_FOUND,
          locate(
                  "PKG.P",
                  "",
                  "CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; END;\n"
                      + "CREATE OR REPLACE PACKAGE BODY pkg AS PROCEDURE p IS BEGIN NULL;")
              .status(),
          "ENDで閉じていないサブプログラム");
    }

    @Test
    @DisplayName("locate: 仕様部に閉じていない文字列がある（INVALIDの）パッケージも、本体の始まりから字句に分け直して切り出す")
    void testUnterminatedStringInSpecification() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AUTHID CURRENT_USER AS
            c CONSTANT VARCHAR2(10) := 'oops;
            PROCEDURE p;
          END pkg;

          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p IS BEGIN DELETE FROM t; END p;
          END pkg;
          """;

      final FunctionBody body = locate("PKG.P", "", definition);

      assertEquals(TableUsageStatus.ANALYZED, body.status());
      assertEquals("BEGIN DELETE FROM t ;", text(body));
      assertTrue(body.invokerRights(), "仕様部の見出しの権限はそのまま読む");
      assertEquals(
          TableUsageStatus.NO_DEFINITION,
          locate("PKG.P", "", "CREATE OR REPLACE PACKAGE pkg AS c VARCHAR2(1) := 'x; END;\n\n")
              .status(),
          "本体が無ければ、従来どおりNO_DEFINITION");
      assertEquals(
          TableUsageStatus.NO_DEFINITION,
          locate(
                  "PKG.P",
                  "",
                  "CREATE OR REPLACE PACKAGE pkg AS c VARCHAR2(1) := 'x; END;\n\n"
                      + "CREATE OR REPLACE TYPE ty AS OBJECT (a NUMBER);")
              .status(),
          "つなぎ目の後ろがパッケージ本体でなければ、NO_DEFINITION");
    }

    @Test
    @DisplayName("locate: パッケージのサブプログラムの呼び出し仕様は、引数名で決まれば言語名を返し、決まらなければPL/SQLの本体だけを持つ")
    void testPackageCallSpecification() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS FUNCTION j RETURN VARCHAR2; PROCEDURE p(c_arg NUMBER); END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            FUNCTION j RETURN VARCHAR2 AS LANGUAGE JAVA NAME 'X.y() return java.lang.String';
            PROCEDURE p(c_arg NUMBER) AS LANGUAGE C NAME "p" LIBRARY lib;
            PROCEDURE p(a VARCHAR2) IS BEGIN DELETE FROM t; END;
          END;
          """;

      final FunctionBody java = locate("PKG.J", "", definition);
      assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, java.status());
      assertEquals("JAVA", java.language());
      assertEquals("C", locate("PKG.P", "C_ARG NUMBER", definition).language());
      final FunctionBody merged = locate("PKG.P", "B DATE", definition);
      assertTrue(merged.overloadsMerged());
      assertEquals("BEGIN DELETE FROM t ;", text(merged));
    }

    @Test
    @DisplayName("locate: 引用符付きの名前のサブプログラムも、カタログの名前と照らして切り出す")
    void testQuotedNames() {
      final String definition =
          "CREATE OR REPLACE PACKAGE \"Pkg\" AS PROCEDURE \"Mixed\"; END;\n"
              + "CREATE OR REPLACE PACKAGE BODY \"Pkg\" AS PROCEDURE \"Mixed\" IS BEGIN DELETE"
              + " FROM t; END; PROCEDURE mixed IS BEGIN NULL; END; END;";

      assertEquals("BEGIN DELETE FROM t ;", text(locate("Pkg.Mixed", "", definition)));
      assertEquals("BEGIN NULL ;", text(locate("Pkg.MIXED", "", definition)));
    }

    @Test
    @DisplayName("locate: 本体の中のWITH FUNCTION（インラインのPL/SQL関数）があっても、サブプログラムの範囲を正しく閉じる")
    void testInlineWithFunction() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p; PROCEDURE q; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p IS
              c SYS_REFCURSOR;
            BEGIN
              OPEN c FOR WITH FUNCTION dbl(x NUMBER) RETURN NUMBER IS BEGIN RETURN x * 2; END;
                SELECT dbl(a) FROM t1;
            END p;
            PROCEDURE q IS BEGIN DELETE FROM t2; END q;
          END;
          """;

      assertTrue(text(locate("PKG.P", "", definition)).endsWith("SELECT dbl ( a ) FROM t1 ;"));
      assertEquals("BEGIN DELETE FROM t2 ;", text(locate("PKG.Q", "", definition)));
    }

    @Test
    @DisplayName("locate: 同じパッケージのサブプログラムを続けて読んでも、別の定義に切り替えても正しく切り出す")
    void testCacheAcrossFunctions() {
      assertEquals("BEGIN RETURN g_count ;", text(locate("PKG.F1", "", PACKAGE)));
      assertEquals("BEGIN INSERT INTO h VALUES ( 1 ) ;", text(locate("PKG.HELPER", "", PACKAGE)));
      assertEquals(
          "BEGIN NULL ; END ;",
          text(locate("P", "", "CREATE OR REPLACE PROCEDURE p IS BEGIN NULL; END;")));
      assertEquals("BEGIN RETURN g_count ;", text(locate("PKG.F1", "", PACKAGE)));
    }

    @Test
    @DisplayName(
        "locate: CASE式の直後のLOOP・IF（FORの範囲・WHILEの条件）をEND LOOP・END IFと誤らず、後ろのサブプログラムの範囲をずらさない")
    void testCaseExpressionFollowedByLoop() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p1; PROCEDURE p2; PROCEDURE p3; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p1 IS BEGIN
              FOR i IN 1 .. CASE WHEN g_big THEN 100 ELSE 10 END LOOP NULL; END LOOP;
              DELETE FROM a;
            END p1;
            PROCEDURE p2 IS BEGIN
              WHILE CASE WHEN v > 0 THEN TRUE ELSE FALSE END LOOP v := v - 1; END LOOP;
              DELETE FROM b;
            END p2;
            PROCEDURE p3 IS BEGIN
              CASE v WHEN 1 THEN x := CASE WHEN y THEN 1 END; ELSE NULL; END CASE;
              DELETE FROM c;
            END p3;
          END;
          """;

      assertTrue(text(locate("PKG.P1", "", definition)).endsWith("DELETE FROM a ;"));
      assertTrue(text(locate("PKG.P2", "", definition)).endsWith("DELETE FROM b ;"));
      assertTrue(text(locate("PKG.P3", "", definition)).endsWith("DELETE FROM c ;"));
      assertFalse(text(locate("PKG.P1", "", definition)).contains("DELETE FROM b"));
    }

    @Test
    @DisplayName("locate: 列名のfunction・procedure（別名ASを続ける形を含む）をサブプログラムの見出しと誤らない")
    void testColumnNamedFunction() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p1; PROCEDURE p2; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            CURSOR c IS SELECT e.function AS fn FROM t e;
            PROCEDURE p1 IS BEGIN
              SELECT e.function AS fn, e.procedure AS pr INTO v, w FROM t e;
              SELECT function AS fn INTO v FROM t;
              DELETE FROM a;
            END p1;
            PROCEDURE p2 IS BEGIN DELETE FROM b; END p2;
          END;
          """;

      assertTrue(text(locate("PKG.P1", "", definition)).endsWith("DELETE FROM a ;"));
      assertEquals("BEGIN DELETE FROM b ;", text(locate("PKG.P2", "", definition)));
    }

    @Test
    @DisplayName("locate: 条件付きコンパイルの分岐ごとにBEGIN・サブプログラムを書き分けても、全分岐を範囲に含め、後ろの範囲をずらさない")
    void testConditionalCompilation() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS PROCEDURE p1; PROCEDURE p2; PROCEDURE p3; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            PROCEDURE p1 IS
            $IF $$new_api $THEN
            BEGIN
              DELETE FROM a;
            $ELSIF $$old_api $THEN
            BEGIN
              DELETE FROM b;
            $ELSE
            BEGIN
              IF x THEN DELETE FROM c;
            $END
              DELETE FROM d;
            $IF $$new_api $THEN END IF; $END
            END p1;
            $IF $$v12 $THEN
            PROCEDURE p2 IS BEGIN DELETE FROM e; END p2;
            $ELSE
            PROCEDURE p2 IS BEGIN DELETE FROM f; END p2;
            $END
            $ERROR 'unsupported' $END
            PROCEDURE p3 IS BEGIN DELETE FROM g; END p3;
          END;
          """;

      final String p1 = text(locate("PKG.P1", "", definition));
      assertTrue(
          p1.contains("DELETE FROM a")
              && p1.contains("DELETE FROM b")
              && p1.contains("DELETE FROM c")
              && p1.contains("DELETE FROM d"),
          p1);
      final FunctionBody p2 = locate("PKG.P2", "", definition);
      assertEquals("BEGIN DELETE FROM e ; || BEGIN DELETE FROM f ;", text(p2));
      assertTrue(p2.overloadsMerged());
      assertEquals("BEGIN DELETE FROM g ;", text(locate("PKG.P3", "", definition)));
    }

    @Test
    @DisplayName("locate: 引数の並びの後ろの括弧（RELIES_ON・PARALLEL_ENABLE・ACCESSIBLE BY）の中の名前を引数名にしない")
    void testParenthesesAfterParameterList() {
      final String definition =
          """
          CREATE OR REPLACE PACKAGE pkg AS FUNCTION f(a NUMBER) RETURN NUMBER; FUNCTION f(b NUMBER) RETURN NUMBER; END;
          CREATE OR REPLACE PACKAGE BODY pkg AS
            FUNCTION f(a NUMBER) RETURN NUMBER RESULT_CACHE RELIES_ON (t1, t2)
              PARALLEL_ENABLE (PARTITION p BY ANY) ACCESSIBLE BY (PROCEDURE other) IS
            BEGIN DELETE FROM a; RETURN 1; END;
            FUNCTION f(b NUMBER) RETURN NUMBER IS BEGIN DELETE FROM b; RETURN 1; END;
          END;
          """;

      final FunctionBody first = locate("PKG.F", "A NUMBER", definition);
      assertEquals("BEGIN DELETE FROM a ; RETURN 1 ;", text(first));
      assertFalse(first.overloadsMerged());
    }

    @Test
    @DisplayName("parameterNames: カタログの引数の並びから引数名を取り出す")
    void testParameterNames() {
      assertEquals(
          List.of("P_ID", "P_NAME"),
          FunctionBodyLocator.parameterNames("P_ID NUMBER, P_NAME OUT VARCHAR2"));
      assertEquals(List.of(), FunctionBodyLocator.parameterNames(""));
      assertEquals(List.of("A", "B"), FunctionBodyLocator.parameterNames("  A  NUMBER ,B"));
      assertEquals(List.of("A"), FunctionBodyLocator.parameterNames("A NUMBER,,"));
    }

    private FunctionBody locate(String name, String arguments, String definition) {
      return locator.locate(
          new FunctionEntity(
              "db", "SCOTT", name, 1, 1, "PROCEDURE", arguments, "", "PL/SQL", definition));
    }
  }
}
