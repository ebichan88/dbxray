package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQLの定義から本体を切り出すクラス<br>
 * {@code pg_get_functiondef}は本体を常にドル引用符で囲むため、見出しの後の最上位のドル引用符の中身を字句に分け直す。
 * SQL標準の本体（{@code BEGIN ATOMIC … END}・{@code RETURN 式}）はドル引用符で囲まれないため、
 * 最上位の{@code BEGIN ATOMIC}・{@code RETURN}から終わりまでとする。{@code begin}・{@code return}は予約語でなく、
 * 関数名（{@code pg_get_functiondef}はスキーマで修飾する）や型名にも使えるため、{@code .}の直後のものは区切りとしない<br>
 * {@code search_path}は見出しの{@code SET search_path}（関数の属性）だけを読む。
 * 本体の中の{@code SET}文・{@code set_config('search_path', …)}・{@code EXECUTE}で組み立てたSET文（変数でスキーマを渡す場合を含む）は、本体を切り出した後の字句のため読まない
 */
final class PostgresBodyLocator implements FunctionBodyLocator {

  /** 解析する言語 */
  private static final List<String> SUPPORTED_LANGUAGES = List.of("sql", "plpgsql");

  /** {@inheritDoc} */
  @Override
  public FunctionBody locate(FunctionEntity function) {
    final String language = function.languageName();
    if (SUPPORTED_LANGUAGES.stream()
        .noneMatch(name -> AsciiCase.equalsIgnoreCase(name, language))) {
      return new FunctionBody.UnsupportedLanguage(language);
    }
    final String definition = function.definition();
    if (definition.isBlank()) {
      return new FunctionBody.NotAnalyzable(TableUsageStatus.NO_DEFINITION);
    }
    final SqlLexResult lexed = SqlLexer.lex(definition, Dbms.POSTGRESQL);
    final List<SqlToken> all = lexed.tokens();
    final SqlTokens tokens = new SqlTokens(all);
    List<String> searchPath = List.of();
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      final SqlToken token = tokens.at(i);
      if (token.isSymbol("(")) {
        depth++;
      } else if (token.isSymbol(")")) {
        depth = Math.max(0, depth - 1);
      } else if (depth > 0) {
        continue;
      } else if (token.isWord("SET") && isSearchPathAt(tokens, i + 1)) {
        final int name = i + 1;
        searchPath = readSearchPath(tokens, name + 1);
      } else if (token.isWord("AS")
          && tokens.at(i + 1) != null
          && tokens.at(i + 1).kind() == SqlTokenKind.DOLLAR_STRING) {
        final SqlToken body = tokens.at(i + 1);
        final SqlLexResult inner =
            SqlLexer.lex(
                definition, body.contentStart(), body.contentEnd(), body.line(), Dbms.POSTGRESQL);
        return new FunctionBody.Postgres(
            inner.tokens(), lexed.incomplete() || inner.incomplete(), searchPath);
      } else if (tokens.isAfterDot(i)) {
        continue;
      } else if ((token.isWord("BEGIN") && tokens.isWordAt(i + 1, "ATOMIC"))
          || token.isWord("RETURN")) {
        return new FunctionBody.Postgres(
            all.subList(i, all.size()), lexed.incomplete(), searchPath);
      }
    }
    // 本体の区切りが無い定義の字句は見出しのため、本体と誤らないよう解析しない
    return new FunctionBody.NotAnalyzable(TableUsageStatus.NO_DEFINITION);
  }

  private static boolean isSearchPathAt(SqlTokens tokens, int i) {
    return tokens.isNameAt(i)
        && SqlNames.identifier(tokens.at(i), Dbms.POSTGRESQL).equals("search_path");
  }

  /**
   * {@code SET search_path TO 'a', 'b'}のスキーマの並びを読むメソッド<br>
   * {@code pg_get_functiondef}は各スキーマを、大文字小文字を保った文字列にして並べる。
   * 手で書いた形（{@code SET search_path = a, "B"}）も同じ規則で読む。{@code FROM CURRENT}は作成時の値に展開されて保存されるため、{@code pg_get_functiondef}の定義には現れない。
   * 手で書いた{@code FROM CURRENT}は値を読めないため、指定が無いものとする。{@code $user}は実行するユーザーの名前のスキーマに決まるため読めず、
   * 並びから除く。指定があるのに出力対象のスキーマが無い場合（空の文字列の{@code SET search_path TO ''}、
   * {@code $user}を含まず{@code pg_catalog}・{@code pg_temp}だけ）は、指定が無いものと区別するため、空のスキーマ名で持つ
   *
   * @param i {@code search_path}の次の位置（{@code TO}・{@code =}）
   * @return スキーマの並び。{@code TO}・{@code =}で値を指定していない・{@code DEFAULT}の場合は空
   */
  private static List<String> readSearchPath(SqlTokens tokens, int i) {
    final List<String> schemas = new ArrayList<>();
    if (!tokens.isWordAt(i, "TO") && !tokens.isSymbolAt(i, "=")) {
      return schemas;
    }
    boolean hasUser = false;
    boolean hasSystemSchema = false;
    int j = i + 1;
    while (tokens.at(j) != null) {
      final SqlToken value = tokens.at(j);
      final String schema;
      if (value.kind() == SqlTokenKind.STRING) {
        schema = SqlNames.unquote(value.text(), '\'');
      } else if (value.isName() && !value.isWord("DEFAULT")) {
        schema = SqlNames.identifier(value, Dbms.POSTGRESQL);
      } else {
        break;
      }
      if (schema.equals("$user")) {
        hasUser = true;
      } else if (schema.equals("pg_catalog") || schema.startsWith("pg_temp")) {
        hasSystemSchema = true;
      } else {
        schemas.add(schema);
      }
      final int comma = j + 1;
      if (tokens.isSymbolAt(comma, ",")) {
        j = comma + 1;
      } else {
        break;
      }
    }
    if (schemas.isEmpty() && hasSystemSchema && !hasUser) {
      schemas.add("");
    }
    return schemas;
  }
}
