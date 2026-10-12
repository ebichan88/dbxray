package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import com.dbxray.domain.service.tableusage.PackageSubprograms.Subprogram;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracleの定義から本体を切り出すクラス<br>
 * 単独の関数・プロシージャは見出しの{@code IS}・{@code AS}の後ろ全体、パッケージのサブプログラムは、パッケージ本体の最上位で定義された同名のサブプログラムの範囲とする。
 * パッケージのサブプログラムは同じパッケージの定義を共有するため、直前に読んだパッケージの字句とサブプログラムの範囲を使い回す
 * （同じスキーマの関数は名前の順に並び、同じパッケージのサブプログラムは続けて現れる）。
 * 同じパッケージの定義は、カタログの行によらず同一のため、定義の全文は比べず、スキーマ・パッケージの名前と定義の長さだけを照らす
 * （全文を比べると、サブプログラムの数だけ巨大な定義を比べることになる）
 */
final class OracleBodyLocator implements FunctionBodyLocator {

  /** {@code PACKAGE BODY}の字句の数（パッケージ本体の名前は、その次から始まる） */
  private static final int PACKAGE_BODY_TOKENS = 2;

  /** 仕様部とパッケージ本体のつなぎ目の、パッケージ本体の側（取得時に、{@code CREATE OR REPLACE}を補ったパッケージ本体を続ける） */
  private static final String PACKAGE_BODY_PREFIX = "CREATE OR REPLACE ";

  /**
   * 仕様部とパッケージ本体のつなぎ目<br>
   * {@code catalogMapper.xml}（Oracle）のパッケージの定義の取得が、仕様部の後ろに{@code CHR(10) || CHR(10)}を挟み、{@code
   * source_text}の関数が{@code 'CREATE OR REPLACE '}を補ったパッケージ本体を続けて組み立てる形と一致させている。
   * mapperの組み立てを変えると、仕様部が壊れている場合の立て直し（{@link #parseWithBrokenSpecification}）が効かなくなるため、あわせて直す
   */
  private static final String PACKAGE_BODY_SEPARATOR = "\n\n" + PACKAGE_BODY_PREFIX;

  /** 直前に読んだパッケージの、スキーマと名前（{@link #packageKey}）。無ければnull */
  private String cachedPackageKey;

  /** 直前に読んだパッケージの定義の長さ */
  private int cachedDefinitionLength;

  private OracleDefinition cachedParsed;

  /** {@inheritDoc} */
  @Override
  public FunctionBody locate(FunctionEntity function) {
    final String definition = function.definition();
    if (definition.isBlank()) {
      return new FunctionBody.NotAnalyzable(TableUsageStatus.NO_DEFINITION);
    }
    return switch (parsedDefinition(function)) {
      case OracleDefinition.Unanalyzable unanalyzable ->
          new FunctionBody.NotAnalyzable(unanalyzable.status());
      case OracleDefinition.Standalone standalone -> locateStandalone(standalone);
      case OracleDefinition.PackageBody packageBody -> locateSubprogram(function, packageBody);
    };
  }

  /** 定義を読んだ結果。パッケージのサブプログラムは、直前に同じパッケージを読んでいれば使い回す */
  private OracleDefinition parsedDefinition(FunctionEntity function) {
    final String packageKey = packageKey(function);
    final int definitionLength = function.definition().length();
    if (packageKey == null) {
      return parse(function.definition());
    }
    if (!packageKey.equals(cachedPackageKey) || definitionLength != cachedDefinitionLength) {
      cachedParsed = parse(function.definition());
      cachedPackageKey = packageKey;
      cachedDefinitionLength = definitionLength;
    }
    return cachedParsed;
  }

  /**
   * パッケージのサブプログラム（{@code パッケージ.サブプログラム}）の、パッケージを指す値
   *
   * @return スキーマとパッケージ名。単独の関数・プロシージャ（名前に{@code .}が無い）はnull
   */
  private static String packageKey(FunctionEntity function) {
    final String fullName = function.functionName();
    final int dot = fullName.lastIndexOf('.');
    return dot < 0 ? null : function.schemaName() + '\n' + fullName.substring(0, dot);
  }

  private static FunctionBody locateStandalone(OracleDefinition.Standalone standalone) {
    if (!standalone.callSpecLanguage().isEmpty()) {
      return new FunctionBody.UnsupportedLanguage(standalone.callSpecLanguage());
    }
    final List<SqlToken> tokens = standalone.lexed().tokens();
    return new FunctionBody.Oracle(
        List.of(tokens.subList(standalone.bodyStart(), tokens.size())),
        standalone.lexed().incomplete(),
        false,
        standalone.invokerRights());
  }

  /**
   * パッケージ本体から、名前の一致するサブプログラムの範囲を切り出すメソッド<br>
   * 同名が複数（オーバーロード）なら、宣言の引数名の並びで絞る。1つに決まらなければ、絞った本体すべての範囲を持つ<br>
   * カタログ上もオーバーロードされている場合だけ、本体をまとめた旨の印を立てる（条件付きコンパイルで同じ名前を分岐ごとに書いただけなら立てない）<br>
   * 範囲は本体を閉じる{@code END}まで読めたものだけのため、字句を最後まで読めなかった印は立てない
   */
  private static FunctionBody locateSubprogram(
      FunctionEntity function, OracleDefinition.PackageBody packageBody) {
    final String fullName = function.functionName();
    final String name = fullName.substring(fullName.lastIndexOf('.') + 1);
    final List<Subprogram> sameName =
        packageBody.subprograms().stream().filter(s -> s.name().equals(name)).toList();
    if (sameName.isEmpty()) {
      return new FunctionBody.NotAnalyzable(TableUsageStatus.SUBPROGRAM_NOT_FOUND);
    }
    final List<Subprogram> chosen = narrowByParameterNames(sameName, function.functionArguments());
    final List<SqlToken> tokens = packageBody.lexed().tokens();
    final List<List<SqlToken>> segments =
        chosen.stream()
            .filter(s -> s.language().isEmpty())
            .map(s -> tokens.subList(s.bodyStart(), s.bodyEnd()))
            .toList();
    if (segments.isEmpty()) {
      return new FunctionBody.UnsupportedLanguage(chosen.get(0).language());
    }
    return new FunctionBody.Oracle(
        segments, false, chosen.size() > 1 && function.isOverloaded(), packageBody.invokerRights());
  }

  /**
   * 同名のサブプログラムを、カタログの引数名の並びと一致するものに絞るメソッド
   *
   * @return 引数名の並びが一致するもの。1つも一致しなければ、同名のものすべて
   */
  private static List<Subprogram> narrowByParameterNames(
      List<Subprogram> sameName, String functionArguments) {
    if (sameName.size() == 1) {
      return sameName;
    }
    final List<String> parameterNames = parameterNames(functionArguments);
    final List<Subprogram> matched =
        sameName.stream().filter(s -> s.parameterNames().equals(parameterNames)).toList();
    return matched.isEmpty() ? sameName : matched;
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

  /** 定義を字句に分け、見出しを読む */
  private static OracleDefinition parse(String definition) {
    final SqlLexResult lexed = SqlLexer.lex(definition, Dbms.ORACLE);
    final SqlTokens tokens = new SqlTokens(lexed.tokens());
    int i = 0;
    while (tokens.at(i) != null && isCreatePrefix(tokens.at(i))) {
      i++;
    }
    final boolean isPackage = tokens.isWordAt(i, "PACKAGE");
    if (!isPackage && !tokens.isWordAt(i, "FUNCTION") && !tokens.isWordAt(i, "PROCEDURE")) {
      // 想定外の形の定義は、全体を本体とする
      return new OracleDefinition.Standalone(lexed, "", 0, false);
    }
    final int nameEnd = tokens.endOfQualifiedName(i + 1);
    if (tokens.isWordAt(nameEnd, "WRAPPED")) {
      return new OracleDefinition.Unanalyzable(TableUsageStatus.WRAPPED);
    }
    final int is = findIsOrAs(tokens, nameEnd);
    final boolean invokerRights = hasInvokerRights(tokens, nameEnd, is < 0 ? nameEnd : is);
    if (!isPackage) {
      if (is < 0) {
        // IS・ASの無い定義（AGGREGATE USING・PIPELINED USING等）に本体は無く、残りは見出しのため解析しない
        return new OracleDefinition.Unanalyzable(TableUsageStatus.NO_DEFINITION);
      }
      return new OracleDefinition.Standalone(
          lexed, PackageSubprograms.callSpecLanguage(tokens, is + 1), is + 1, invokerRights);
    }
    final int packageBody = findPackageBody(tokens, nameEnd);
    if (packageBody >= 0) {
      return parsePackageBody(lexed, packageBody, invokerRights);
    }
    // 仕様部の閉じていない文字列が本体のアポストロフィで閉じると、incompleteにならないまま本体の字句まで飲み込まれる
    final OracleDefinition rebuilt = parseWithBrokenSpecification(definition, invokerRights);
    if (rebuilt != null) {
      return rebuilt;
    }
    // 本体の無いパッケージや、仕様部しか見えない権限では、パッケージ本体を取得できない
    return new OracleDefinition.Unanalyzable(TableUsageStatus.NO_DEFINITION);
  }

  /**
   * 仕様部に閉じていない文字列等がある（INVALIDの）場合に、本体の字句まで飲み込まれてしまうため、本体の始まりから字句に分け直すメソッド<br>
   * 飲み込んだ文字列が本体のアポストロフィで閉じると、字句の解析は不完全にならないため、{@code PACKAGE BODY}が見つからなかったときは常に試す
   *
   * @return パッケージ本体を読めた結果。本体の始まりが見つからなければnull
   */
  private static OracleDefinition parseWithBrokenSpecification(
      String definition, boolean invokerRights) {
    final int separator = definition.indexOf(PACKAGE_BODY_SEPARATOR);
    if (separator < 0) {
      return null;
    }
    final int from = separator + "\n\n".length();
    final int line = 1 + SqlLexer.lineBreaks(definition, 0, from, definition.length());
    final SqlLexResult lexed =
        SqlLexer.lex(definition, from, definition.length(), line, Dbms.ORACLE);
    final int packageBody = findPackageBody(new SqlTokens(lexed.tokens()), 0);
    return packageBody >= 0 ? parsePackageBody(lexed, packageBody, invokerRights) : null;
  }

  /**
   * パッケージ本体の見出しを読み、最上位のサブプログラムの範囲を求めるメソッド
   *
   * @param packageBody {@code PACKAGE BODY}の{@code PACKAGE}の位置
   */
  private static OracleDefinition parsePackageBody(
      SqlLexResult lexed, int packageBody, boolean invokerRights) {
    final SqlTokens tokens = new SqlTokens(lexed.tokens());
    final int bodyNameEnd = tokens.endOfQualifiedName(packageBody + PACKAGE_BODY_TOKENS);
    if (tokens.isWordAt(bodyNameEnd, "WRAPPED")) {
      return new OracleDefinition.Unanalyzable(TableUsageStatus.WRAPPED);
    }
    final int bodyIs = findIsOrAs(tokens, bodyNameEnd);
    final List<Subprogram> subprograms =
        bodyIs < 0 ? List.of() : PackageSubprograms.find(lexed.tokens(), bodyIs + 1);
    return new OracleDefinition.PackageBody(lexed, subprograms, invokerRights);
  }

  private static boolean isCreatePrefix(SqlToken token) {
    return token.isWord("CREATE")
        || token.isWord("OR")
        || token.isWord("REPLACE")
        || token.isWord("EDITIONABLE")
        || token.isWord("NONEDITIONABLE");
  }

  /**
   * 見出しを閉じる最上位の{@code IS}・{@code AS}を探すメソッド
   *
   * @return 位置。見出しが{@code ;}で終わる・見つからない場合は-1
   */
  private static int findIsOrAs(SqlTokens tokens, int from) {
    int depth = 0;
    for (int i = from; i < tokens.size(); i++) {
      final SqlToken token = tokens.at(i);
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

  private static boolean hasInvokerRights(SqlTokens tokens, int from, int to) {
    for (int i = from; i + 1 < to; i++) {
      if (tokens.isWordAt(i, "AUTHID") && tokens.isWordAt(i + 1, "CURRENT_USER")) {
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
  private static int findPackageBody(SqlTokens tokens, int from) {
    for (int i = from; i + 1 < tokens.size(); i++) {
      if (tokens.isWordAt(i, "PACKAGE") && tokens.isWordAt(i + 1, "BODY")) {
        return i;
      }
    }
    return -1;
  }

  /** Oracleの定義を字句に分け、見出しを読んだ結果（本体の切り出し方が3通りに分かれる） */
  private sealed interface OracleDefinition {

    /**
     * 本体を切り出せない定義
     *
     * @param status 解析しない理由
     */
    record Unanalyzable(TableUsageStatus status) implements OracleDefinition {}

    /**
     * 単独の関数・プロシージャ
     *
     * @param callSpecLanguage 呼び出し仕様の言語。PL/SQLで書かれていれば空文字
     * @param bodyStart 本体の開始位置
     */
    record Standalone(
        SqlLexResult lexed, String callSpecLanguage, int bodyStart, boolean invokerRights)
        implements OracleDefinition {}

    /**
     * パッケージ本体
     *
     * @param subprograms 最上位のサブプログラム
     */
    record PackageBody(SqlLexResult lexed, List<Subprogram> subprograms, boolean invokerRights)
        implements OracleDefinition {

      /** リストは変更不可な複製として保持する（読んだ結果は後続の呼び出しで使い回すため） */
      public PackageBody {
        subprograms = List.copyOf(subprograms);
      }
    }
  }
}
