package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 変形テストの変形（改行・空白・コメント・大文字小文字を変える、コメント・文字列の中にDMLを足す）<br>
 * 字句に分けた結果をつなぎ直して作るため、元の本体のコメント・空白は変形後には残らない
 */
final class SqlTransforms {

  /** 間に空白を入れなくても隣の字句とつながらない記号 */
  private static final Set<String> SELF_DELIMITING = Set.of("(", ")", ",", ";");

  private SqlTransforms() {}

  /**
   * 変形1つ
   *
   * @param preservesTokens 字句の並びを変えない変形か（変えないものは、変形後に字句の並びが元と同じことも確かめる）
   * @param addsStatements 本体の前後に文を足す変形か（定義全体には使えない）
   */
  record Transform(
      String name, boolean preservesTokens, boolean addsStatements, Rewriter rewriter) {

    /** 本体を変形するメソッド。namesは本体が利用するテーブルの名前（コメント・文字列の中に書くDMLの対象） */
    String apply(String sql, Dbms dbms, List<String> names) {
      return rewriter.apply(sql, dbms, names.isEmpty() ? List.of("sample.employee") : names);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** 変形の関数 */
  @FunctionalInterface
  interface Rewriter {
    String apply(String sql, Dbms dbms, List<String> names);
  }

  /** DBに当てはまる変形の一覧 */
  static List<Transform> forDbms(Dbms dbms) {
    final List<Transform> transforms = new ArrayList<>();
    transforms.add(between("改行（LF）", (a, b) -> "\n"));
    transforms.add(between("改行（CRLF＋インデント）", (a, b) -> "\r\n    "));
    transforms.add(between("改行（CRのみ）", (a, b) -> "\r"));
    transforms.add(
        between(
            "空白なし",
            (a, b) ->
                SELF_DELIMITING.contains(a.text()) || SELF_DELIMITING.contains(b.text())
                    ? ""
                    : " "));
    transforms.add(between("タブ・全角空白・NBSP", (a, b) -> "\t　 \t"));
    transforms.add(between("ブロックコメント", (a, b) -> " /* x */ "));
    transforms.add(between("ブロックコメントだけで区切る", (a, b) -> "/**/"));
    transforms.add(
        between("複数行のブロックコメント", (a, b) -> "/*\r\n * 日本語のコメント -- ' \" ; DELETE FROM t\n */"));
    if (dbms == Dbms.POSTGRESQL) {
      transforms.add(between("入れ子のブロックコメント", (a, b) -> "/* a /* b ' */ c \" */"));
    } else {
      transforms.add(between("ヒント", (a, b) -> " /*+ INDEX(t) */ "));
    }
    transforms.add(between("行コメント", (a, b) -> " -- x ' \" /* ; DELETE FROM t\n"));
    transforms.add(between("行コメント（CR）", (a, b) -> "--\r"));
    transforms.add(words("大文字", AsciiCase::toUpper));
    transforms.add(words("小文字", AsciiCase::toLower));
    transforms.add(words("大文字小文字を交互", SqlTransforms::alternate));
    transforms.add(new Transform("コメントアウトしたDML", false, false, SqlTransforms::commentedOutDml));
    transforms.add(new Transform("文字列の中のDML", false, true, SqlTransforms::dmlInStrings));
    return transforms;
  }

  /** 字句の間を作り直す変形 */
  private static Transform between(String name, Separator separator) {
    return new Transform(
        name,
        true,
        false,
        (sql, dbms, names) -> {
          final List<SqlToken> tokens = SqlLexer.lex(sql, dbms).tokens();
          final StringBuilder sb = new StringBuilder();
          for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
              sb.append(separator.between(tokens.get(i - 1), tokens.get(i)));
            }
            sb.append(tokens.get(i).text());
          }
          return sb.toString();
        });
  }

  /** 引用符の無い単語の大文字小文字を変える変形 */
  private static Transform words(String name, java.util.function.UnaryOperator<String> mapper) {
    return new Transform(
        name,
        true,
        false,
        (sql, dbms, names) -> {
          final StringBuilder sb = new StringBuilder();
          int position = 0;
          for (final SqlToken token : SqlLexer.lex(sql, dbms).tokens()) {
            // 字句の間の文字列（空白・コメント）は、そのまま残す
            sb.append(sql, position, token.start());
            sb.append(
                token.kind() == SqlTokenKind.WORD ? mapper.apply(token.text()) : token.text());
            position = token.end();
          }
          sb.append(sql, position, sql.length());
          return sb.toString();
        });
  }

  /** 字句の間の文字列 */
  @FunctionalInterface
  private interface Separator {
    String between(SqlToken previous, SqlToken next);
  }

  private static String alternate(String word) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < word.length(); i++) {
      final String c = String.valueOf(word.charAt(i));
      sb.append(i % 2 == 0 ? AsciiCase.toUpper(c) : AsciiCase.toLower(c));
    }
    return sb.toString();
  }

  /** 本体の前後に、利用するテーブルへのDMLをコメントアウトして足す */
  private static String commentedOutDml(String sql, Dbms dbms, List<String> names) {
    final StringBuilder sb = new StringBuilder();
    for (final String name : names) {
      sb.append("-- DELETE FROM ").append(name).append(";\n");
      sb.append("/* UPDATE ")
          .append(name)
          .append(" SET x = 1;\n   INSERT INTO ")
          .append(name)
          .append(" VALUES (1); */\n");
    }
    sb.append(sql);
    for (final String name : names) {
      sb.append("\n-- TRUNCATE TABLE ").append(name).append("; EXECUTE 'x'");
    }
    return sb.toString();
  }

  /** 本体の前後に、利用するテーブルへのDMLを文字列にした文を足す */
  private static String dmlInStrings(String sql, Dbms dbms, List<String> names) {
    final StringBuilder sb = new StringBuilder();
    for (final String name : names) {
      if (dbms == Dbms.POSTGRESQL) {
        sb.append("RAISE NOTICE 'DELETE FROM ").append(name).append("; UPDATE ").append(name);
        sb.append(" SET x = ''y''';\nPERFORM $x$INSERT INTO ")
            .append(name)
            .append(" VALUES (1)$x$;\n");
      } else {
        sb.append("DBMS_OUTPUT.PUT_LINE('DELETE FROM ").append(name).append("; UPDATE ");
        sb.append(name).append(" SET x = ''y''');\nv := q'[INSERT INTO ").append(name);
        sb.append(" VALUES ('a')]';\n");
      }
    }
    sb.append(sql).append(";\n");
    for (final String name : names) {
      sb.append(dbms == Dbms.POSTGRESQL ? "v := E'TRUNCATE " : "v := 'TRUNCATE TABLE ");
      sb.append(name)
          .append(dbms == Dbms.POSTGRESQL ? " \\' EXECUTE x';\n" : " EXECUTE IMMEDIATE';\n");
    }
    return sb.toString();
  }

  /** 変形が字句の並びを変えていないこと（大文字小文字の違いを除く）を確かめる */
  static void assertSameTokens(String original, String transformed, Dbms dbms) {
    final List<SqlToken> expected = SqlLexer.lex(original, dbms).tokens();
    final List<SqlToken> actual = SqlLexer.lex(transformed, dbms).tokens();
    assertEquals(expected.size(), actual.size(), "token count: " + transformed);
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.get(i).kind(), actual.get(i).kind(), "token kind: " + transformed);
      assertTrue(
          AsciiCase.equalsIgnoreCase(expected.get(i).text(), actual.get(i).text()),
          "token text: " + transformed);
    }
  }
}
