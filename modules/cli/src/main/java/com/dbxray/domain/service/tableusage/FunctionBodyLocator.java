package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import com.dbxray.domain.service.tableusage.PackageSubprograms.Subprogram;
import java.util.ArrayList;
import java.util.List;

/**
 * 関数・プロシージャの定義から、解析する本体を切り出すクラス<br>
 * 見出し（引数・戻り値の型等）は解析しない（{@code RETURNS SETOF t}・{@code p t%ROWTYPE}をテーブルの利用と誤らないため）。
 * Oracleのパッケージのサブプログラムは同じパッケージの定義を共有するため、直前に読んだ定義の字句とサブプログラムの範囲を使い回す
 * （同じスキーマの関数は名前の順に並び、同じパッケージのサブプログラムは続けて現れる）。インスタンスはスキーマごとに作る
 */
final class FunctionBodyLocator {

  /** 解析する言語（PostgreSQL） */
  private static final List<String> POSTGRES_LANGUAGES = List.of("sql", "plpgsql");

  private final Dbms dbms;

  /** 直前に読んだOracleの定義（パッケージのサブプログラムで使い回す） */
  private String cachedDefinition;

  private OracleDefinition cachedOracleDefinition;

  FunctionBodyLocator(Dbms dbms) {
    this.dbms = dbms;
  }

  /** 定義から本体を切り出す */
  FunctionBody locate(FunctionEntity function) {
    return dbms == Dbms.POSTGRESQL ? locatePostgres(function) : locateOracle(function);
  }

  /**
   * PostgreSQLの本体を切り出すメソッド<br>
   * {@code pg_get_functiondef}は本体を常にドル引用符で囲むため、見出しの後の最上位のドル引用符の中身を字句に分け直す。 SQL標準の本体（{@code BEGIN
   * ATOMIC … END}・{@code RETURN 式}）はドル引用符で囲まれないため、最上位の{@code BEGIN ATOMIC}・{@code
   * RETURN}から終わりまでとする。{@code begin}・{@code return}は予約語でなく、関数名（{@code
   * pg_get_functiondef}はスキーマで修飾する）や型名にも使えるため、{@code .}の直後のものは区切りとしない
   */
  private FunctionBody locatePostgres(FunctionEntity function) {
    final String language = function.languageName();
    if (POSTGRES_LANGUAGES.stream().noneMatch(name -> AsciiCase.equalsIgnoreCase(name, language))) {
      return FunctionBody.unsupportedLanguage(language);
    }
    final String definition = function.definition();
    if (definition.isBlank()) {
      return FunctionBody.notAnalyzable(TableUsageStatus.NO_DEFINITION);
    }
    final SqlLexResult lexed = SqlLexer.lex(definition, Dbms.POSTGRESQL);
    final List<SqlToken> tokens = lexed.tokens();
    List<String> searchPath = List.of();
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      final SqlToken token = tokens.get(i);
      if (token.isSymbol("(")) {
        depth++;
      } else if (token.isSymbol(")")) {
        depth = Math.max(0, depth - 1);
      } else if (depth > 0) {
        continue;
      } else if (token.isWord("SET") && isSearchPathAt(tokens, i + 1)) {
        searchPath = readSearchPath(tokens, i + 2);
      } else if (token.isWord("AS")
          && i + 1 < tokens.size()
          && tokens.get(i + 1).kind() == SqlTokenKind.DOLLAR_STRING) {
        final SqlToken body = tokens.get(i + 1);
        final SqlLexResult inner =
            SqlLexer.lex(
                definition,
                body.dollarContentStart(),
                body.dollarContentEnd(),
                body.line(),
                Dbms.POSTGRESQL);
        return FunctionBody.postgres(
            inner.tokens(), lexed.incomplete() || inner.incomplete(), searchPath);
      } else if (i > 0 && tokens.get(i - 1).isSymbol(".")) {
        continue;
      } else if ((token.isWord("BEGIN")
              && i + 1 < tokens.size()
              && tokens.get(i + 1).isWord("ATOMIC"))
          || token.isWord("RETURN")) {
        return FunctionBody.postgres(
            tokens.subList(i, tokens.size()), lexed.incomplete(), searchPath);
      }
    }
    return FunctionBody.postgres(tokens, lexed.incomplete(), searchPath);
  }

  private static boolean isSearchPathAt(List<SqlToken> tokens, int i) {
    return i < tokens.size()
        && tokens.get(i).isName()
        && SqlNames.identifier(tokens.get(i), Dbms.POSTGRESQL).equals("search_path");
  }

  /**
   * {@code SET search_path TO 'a', 'b'}のスキーマの並びを読むメソッド<br>
   * {@code pg_get_functiondef}は各スキーマを、大文字小文字を保った文字列にして並べる。手で書いた形（{@code SET search_path = a,
   * "B"}）も同じ規則で読む
   *
   * @param i {@code search_path}の次の位置（{@code TO}・{@code =}）
   */
  private static List<String> readSearchPath(List<SqlToken> tokens, int i) {
    final List<String> schemas = new ArrayList<>();
    int j = i + 1;
    while (j < tokens.size()) {
      final SqlToken value = tokens.get(j);
      final String schema;
      if (value.kind() == SqlTokenKind.STRING) {
        schema = stringContent(value.text());
      } else if (value.isName() && !value.isWord("DEFAULT")) {
        schema = SqlNames.identifier(value, Dbms.POSTGRESQL);
      } else {
        break;
      }
      if (!schema.equals("$user")
          && !schema.equals("pg_catalog")
          && !schema.startsWith("pg_temp")) {
        schemas.add(schema);
      }
      if (j + 1 < tokens.size() && tokens.get(j + 1).isSymbol(",")) {
        j += 2;
      } else {
        break;
      }
    }
    return schemas;
  }

  /** {@code 'a''b'}の中身（{@code a'b}）。閉じていない文字列は終わりまでを中身とする */
  private static String stringContent(String text) {
    final int open = text.indexOf('\'');
    final boolean closed = text.length() - open >= 2 && text.endsWith("'");
    return text.substring(open + 1, closed ? text.length() - 1 : text.length()).replace("''", "'");
  }

  /**
   * Oracleの本体を切り出すメソッド<br>
   * 単独の関数・プロシージャは見出しの{@code IS}・{@code AS}の後ろ全体、パッケージのサブプログラムは、パッケージ本体の最上位で定義された同名のサブプログラムの範囲とする
   */
  private FunctionBody locateOracle(FunctionEntity function) {
    final String definition = function.definition();
    if (definition.isBlank()) {
      return FunctionBody.notAnalyzable(TableUsageStatus.NO_DEFINITION);
    }
    if (!definition.equals(cachedDefinition)) {
      cachedOracleDefinition = OracleDefinition.parse(definition);
      cachedDefinition = definition;
    }
    final OracleDefinition parsed = cachedOracleDefinition;
    if (parsed.status != TableUsageStatus.ANALYZED) {
      return FunctionBody.notAnalyzable(parsed.status);
    }
    final List<SqlToken> tokens = parsed.lexed.tokens();
    if (!parsed.isPackage) {
      if (!parsed.language.isEmpty()) {
        return FunctionBody.unsupportedLanguage(parsed.language);
      }
      return FunctionBody.oracle(
          List.of(tokens.subList(parsed.bodyStart, tokens.size())),
          parsed.lexed.incomplete(),
          false,
          parsed.invokerRights);
    }
    return locateSubprogram(function, parsed);
  }

  /**
   * パッケージ本体から、名前の一致するサブプログラムの範囲を切り出すメソッド<br>
   * 同名が複数（オーバーロード）なら、宣言の引数名の並びで1つに決める。決まらなければすべての範囲を持ち、その旨の印を立てる。 範囲は本体を閉じる{@code
   * END}まで読めたものだけのため、字句を最後まで読めなかった印は立てない
   */
  private static FunctionBody locateSubprogram(FunctionEntity function, OracleDefinition parsed) {
    final String fullName = function.functionName();
    final String name = fullName.substring(fullName.lastIndexOf('.') + 1);
    final List<Subprogram> sameName =
        parsed.subprograms.stream().filter(s -> s.name().equals(name)).toList();
    if (sameName.isEmpty()) {
      return FunctionBody.notAnalyzable(TableUsageStatus.SUBPROGRAM_NOT_FOUND);
    }
    List<Subprogram> chosen = sameName;
    if (sameName.size() > 1) {
      final List<String> parameterNames = parameterNames(function.functionArguments());
      final List<Subprogram> byParameters =
          sameName.stream().filter(s -> s.parameterNames().equals(parameterNames)).toList();
      if (byParameters.size() == 1) {
        chosen = byParameters;
      }
    }
    if (chosen.size() == 1 && !chosen.get(0).language().isEmpty()) {
      return FunctionBody.unsupportedLanguage(chosen.get(0).language());
    }
    final List<SqlToken> tokens = parsed.lexed.tokens();
    final List<List<SqlToken>> segments =
        chosen.stream()
            .filter(s -> s.language().isEmpty())
            .map(s -> tokens.subList(s.bodyStart(), s.bodyEnd()))
            .toList();
    return FunctionBody.oracle(segments, false, chosen.size() > 1, parsed.invokerRights);
  }

  /**
   * カタログの引数の並び（{@code P_ID NUMBER, P_NAME OUT VARCHAR2}）から引数名を取り出すメソッド
   *
   * @return 引数名（カタログでの名前。宣言順）
   */
  static List<String> parameterNames(String functionArguments) {
    final List<String> names = new ArrayList<>();
    int start = 0;
    while (start <= functionArguments.length()) {
      int end = functionArguments.indexOf(',', start);
      if (end < 0) {
        end = functionArguments.length();
      }
      final String argument = functionArguments.substring(start, end).strip();
      if (!argument.isEmpty()) {
        final int space = indexOfWhitespace(argument);
        names.add(space < 0 ? argument : argument.substring(0, space));
      }
      start = end + 1;
    }
    return names;
  }

  private static int indexOfWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Oracleの定義を字句に分け、見出しを読んだ結果
   *
   * @param status 本体を切り出せる場合は{@link TableUsageStatus#ANALYZED}、それ以外は解析しない理由
   * @param language 単独の関数・プロシージャが呼び出し仕様の場合の言語。それ以外は空文字
   * @param bodyStart 単独の関数・プロシージャの本体の開始位置
   * @param subprograms パッケージ本体の最上位のサブプログラム（パッケージの場合だけ）
   */
  private record OracleDefinition(
      SqlLexResult lexed,
      TableUsageStatus status,
      String language,
      int bodyStart,
      boolean isPackage,
      List<Subprogram> subprograms,
      boolean invokerRights) {

    /** 仕様部とパッケージ本体のつなぎ目（取得時に、仕様部の後ろへ空行を挟み、{@code CREATE OR REPLACE}を補った本体を続ける） */
    private static final String PACKAGE_BODY_SEPARATOR = "\n\nCREATE OR REPLACE ";

    private static OracleDefinition parse(String definition) {
      final SqlLexResult lexed = SqlLexer.lex(definition, Dbms.ORACLE);
      final List<SqlToken> tokens = lexed.tokens();
      int i = 0;
      while (i < tokens.size() && isCreatePrefix(tokens.get(i))) {
        i++;
      }
      final boolean isPackage = isWordAt(tokens, i, "PACKAGE");
      if (!isPackage && !isWordAt(tokens, i, "FUNCTION") && !isWordAt(tokens, i, "PROCEDURE")) {
        // 想定外の形の定義は、全体を本体とする
        return standalone(lexed, "", 0, false);
      }
      final int nameEnd = skipName(tokens, i + 1);
      if (isWordAt(tokens, nameEnd, "WRAPPED")) {
        return notAnalyzable(lexed, TableUsageStatus.WRAPPED);
      }
      final int is = findIsOrAs(tokens, nameEnd);
      final boolean invokerRights = hasInvokerRights(tokens, nameEnd, is < 0 ? nameEnd : is);
      if (!isPackage) {
        if (is < 0) {
          return standalone(lexed, "", nameEnd, invokerRights);
        }
        return standalone(
            lexed, PackageSubprograms.callSpecLanguage(tokens, is + 1), is + 1, invokerRights);
      }
      final int packageBody = findPackageBody(tokens, nameEnd);
      if (packageBody >= 0) {
        return packageBody(lexed, packageBody, invokerRights);
      }
      if (lexed.incomplete()) {
        // 仕様部に閉じていない文字列等がある（INVALIDの）場合は、字句が本体まで続いてしまうため、本体の始まりから字句に分け直す
        final int bodyOffset = definition.indexOf(PACKAGE_BODY_SEPARATOR);
        if (bodyOffset >= 0) {
          final int from = bodyOffset + 2;
          final SqlLexResult bodyLexed =
              SqlLexer.lex(
                  definition, from, definition.length(), lineAt(definition, from), Dbms.ORACLE);
          final int bodyPackageBody = findPackageBody(bodyLexed.tokens(), 0);
          if (bodyPackageBody >= 0) {
            return packageBody(bodyLexed, bodyPackageBody, invokerRights);
          }
        }
      }
      // 本体の無いパッケージや、仕様部しか見えない権限では、パッケージ本体を取得できない
      return notAnalyzable(lexed, TableUsageStatus.NO_DEFINITION);
    }

    /**
     * パッケージ本体の見出しを読み、最上位のサブプログラムの範囲を求めるメソッド
     *
     * @param packageBody {@code PACKAGE BODY}の{@code PACKAGE}の位置
     */
    private static OracleDefinition packageBody(
        SqlLexResult lexed, int packageBody, boolean invokerRights) {
      final List<SqlToken> tokens = lexed.tokens();
      final int bodyNameEnd = skipName(tokens, packageBody + 2);
      if (isWordAt(tokens, bodyNameEnd, "WRAPPED")) {
        return notAnalyzable(lexed, TableUsageStatus.WRAPPED);
      }
      final int bodyIs = findIsOrAs(tokens, bodyNameEnd);
      final List<Subprogram> subprograms =
          bodyIs < 0 ? List.of() : PackageSubprograms.find(tokens, bodyIs + 1);
      return new OracleDefinition(
          lexed, TableUsageStatus.ANALYZED, "", 0, true, subprograms, invokerRights);
    }

    /** 位置の行番号（1始まり） */
    private static int lineAt(String text, int position) {
      int line = 1;
      for (int i = 0; i < position; i++) {
        if (text.charAt(i) == '\n') {
          line++;
        }
      }
      return line;
    }

    private static OracleDefinition standalone(
        SqlLexResult lexed, String language, int bodyStart, boolean invokerRights) {
      return new OracleDefinition(
          lexed, TableUsageStatus.ANALYZED, language, bodyStart, false, List.of(), invokerRights);
    }

    private static OracleDefinition notAnalyzable(SqlLexResult lexed, TableUsageStatus status) {
      return new OracleDefinition(lexed, status, "", 0, false, List.of(), false);
    }

    private static boolean isCreatePrefix(SqlToken token) {
      return token.isWord("CREATE")
          || token.isWord("OR")
          || token.isWord("REPLACE")
          || token.isWord("EDITIONABLE")
          || token.isWord("NONEDITIONABLE");
    }

    /**
     * {@code スキーマ.名前}を読み飛ばすメソッド
     *
     * @return 名前の次の位置
     */
    private static int skipName(List<SqlToken> tokens, int i) {
      int j = i;
      if (j < tokens.size() && tokens.get(j).isName()) {
        j++;
        while (j + 1 < tokens.size() && tokens.get(j).isSymbol(".") && tokens.get(j + 1).isName()) {
          j += 2;
        }
      }
      return j;
    }

    /**
     * 見出しを閉じる最上位の{@code IS}・{@code AS}を探すメソッド
     *
     * @return 位置。見出しが{@code ;}で終わる・見つからない場合は-1
     */
    private static int findIsOrAs(List<SqlToken> tokens, int from) {
      int depth = 0;
      for (int i = from; i < tokens.size(); i++) {
        final SqlToken token = tokens.get(i);
        if (token.isSymbol("(")) {
          depth++;
        } else if (token.isSymbol(")")) {
          depth = Math.max(0, depth - 1);
        } else if (token.isSymbol(";")) {
          return -1;
        } else if (depth == 0 && (token.isWord("IS") || token.isWord("AS"))) {
          return i;
        }
      }
      return -1;
    }

    private static boolean hasInvokerRights(List<SqlToken> tokens, int from, int to) {
      for (int i = from; i + 1 < to; i++) {
        if (tokens.get(i).isWord("AUTHID") && tokens.get(i + 1).isWord("CURRENT_USER")) {
          return true;
        }
      }
      return false;
    }

    /**
     * 仕様部の後ろの{@code PACKAGE BODY}を探すメソッド
     *
     * @return {@code PACKAGE}の位置。無い場合は-1
     */
    private static int findPackageBody(List<SqlToken> tokens, int from) {
      for (int i = from; i + 1 < tokens.size(); i++) {
        if (tokens.get(i).isWord("PACKAGE") && tokens.get(i + 1).isWord("BODY")) {
          return i;
        }
      }
      return -1;
    }

    private static boolean isWordAt(List<SqlToken> tokens, int i, String keyword) {
      return i < tokens.size() && tokens.get(i).isWord(keyword);
    }
  }
}
