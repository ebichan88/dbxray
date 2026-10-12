package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 定義本体の字句から、テーブルを指す位置の名前と操作（C・R・U・D）、動的SQLを拾うクラス<br>
 * 完全な構文解析はせず、「この位置の名前はテーブルを指す」というきっかけのキーワード（{@code INSERT INTO}・{@code FROM}等）と、
 * その直前・直後の字句だけで判定する。{@code ;}と最上位の{@code LOOP}（{@code FOR r IN 問い合わせ LOOP}）で文の状態
 * （{@link StatementState}）を作り直すため、括弧の数が合わない文があっても次の文から読み直せる。字句を先頭から1回なめるだけで終える
 */
final class TableReferenceScanner {

  /** 直前にあると、INSERT・UPDATE・DELETE・TRUNCATEがDMLの文ではない（トリガー・権限・ポリシー・行ロックの構文）語 */
  private static final Set<String> NON_DML_PREDECESSORS =
      Set.of("OR", "ON", "OF", "BEFORE", "AFTER", "FOR", "KEY", "INSTEAD", "GRANT", "REVOKE");

  /**
   * 直前にあると、次の語が式・列名・別名・テーブル名の位置にある（DMLの文の始まりでも、句の始まりでもない）語<br>
   * 非予約語（PostgreSQLの{@code update}・{@code delete}・{@code execute}等）を列名に使った
   * {@code SELECT update FROM t}や、句の語と同じ綴りの列名（{@code ON a.x = start}）を、キーワードと取り違えないために使う
   */
  private static final Set<String> OPERAND_PREDECESSORS =
      Set.of(
          "SELECT",
          "DISTINCT",
          "ALL",
          "WHERE",
          "BY",
          "AND",
          "OR",
          "NOT",
          "ON",
          "AS",
          "HAVING",
          "SET",
          "FROM",
          "JOIN",
          "INTO",
          "USING",
          "APPLY",
          "ONLY",
          "LATERAL",
          "TABLE");

  /** 直前にあっても、次の語が式の位置にならない記号（文・句・副問い合わせ・配列の添字・{@code SELECT *}の終わりや始まり） */
  private static final Set<String> CLAUSE_START_SYMBOLS = Set.of(";", ">>", "(", ")", "]", "*");

  /**
   * 同じ括弧の深さで現れると、FROM等のカンマ区切りのテーブルの並びを終える語。{@code ON}は含めない（JOINの条件の後にカンマで続くため）<br>
   * 並びの後ろに続く句・演算子・分岐やループの区切り・{@code TRUNCATE}の指定の語を挙げている。{@code ;}を欠いた本体や制御構文の途中でも、
   * その後ろのカンマ区切りをテーブルの並びとして読まないための歯止め。名前・別名・条件の中の列名・{@code CASE}の分岐としての使い方は、
   * {@link #terminatesList}で除く
   */
  static final Set<String> LIST_TERMINATORS =
      Set.of(
          "WHERE",
          "GROUP",
          "HAVING",
          "WINDOW",
          "ORDER",
          "LIMIT",
          "OFFSET",
          "FETCH",
          "FOR",
          "UNION",
          "INTERSECT",
          "EXCEPT",
          "MINUS",
          "INTO",
          "RETURNING",
          "SET",
          "DO",
          "WHEN",
          "THEN",
          "ELSE",
          "END",
          "LOOP",
          "CONNECT",
          "START",
          "RESTART",
          "CONTINUE",
          "CASCADE",
          "RESTRICT",
          "SELECT",
          "VALUES");

  /** {@code CASE}式の中で、並びを終える語として働かない分岐の語 */
  private static final Set<String> CASE_CLAUSE_WORDS = Set.of("WHEN", "THEN", "ELSE", "END");

  /** Oracleの{@code PARTITION FOR (値)}・{@code SUBPARTITION FOR (値)}の{@code FOR}の直前の語 */
  private static final Set<String> PARTITION_KEYWORDS = Set.of("PARTITION", "SUBPARTITION");

  /** 括弧の中で{@code FROM}をテーブルの指定以外の意味で使う関数（{@code EXTRACT(YEAR FROM d)}等） */
  private static final Set<String> FROM_SYNTAX_FUNCTIONS =
      Set.of("EXTRACT", "SUBSTRING", "TRIM", "OVERLAY");

  /** 直後から新しい文が始まる語（{@code ;}・ラベルの{@code >>}のほか） */
  private static final Set<String> STATEMENT_PREDECESSORS =
      Set.of("BEGIN", "THEN", "ELSE", "LOOP", "DECLARE");

  /** テーブルの名前の前に置ける語 */
  private static final Set<String> TABLE_MODIFIERS = Set.of("ONLY", "LATERAL");

  /** 直前にあると、{@code EXECUTE}が動的SQLではなく権限の名前（{@code GRANT EXECUTE ON …}）である語 */
  private static final Set<String> PRIVILEGE_PREDECESSORS = Set.of("GRANT", "REVOKE");

  /** 括弧の直後にあると、括弧が副問い合わせ（括弧で囲んだJOINではない）であることを表す語 */
  private static final Set<String> QUERY_STARTS = Set.of("SELECT", "WITH", "VALUES");

  /** DELETEの直後にあると、DELETEが対象を持たない（MERGEのDELETE）語 */
  private static final Set<String> MERGE_DELETE_SUCCESSORS = Set.of("WHERE", "WHEN", "RETURNING");

  /** SQL文字列を引数に取り、リモートのDBで実行するdblinkの関数（{@code dblink_connect}等、SQLを渡さないものは除く） */
  private static final Set<String> DBLINK_SQL_FUNCTIONS =
      Set.of("DBLINK", "DBLINK_EXEC", "DBLINK_SEND_QUERY", "DBLINK_OPEN");

  /** Oracleの{@code NTH_VALUE(…) FROM FIRST|LAST}の後ろに続く語 */
  private static final Set<String> NTH_VALUE_SUCCESSORS = Set.of("OVER", "IGNORE", "RESPECT");

  /**
   * {@code INSERT INTO}・{@code DELETE FROM}・{@code MERGE INTO}・
   * {@code TRUNCATE TABLE}の、先頭のキーワード2語の長さ
   */
  private static final int STATEMENT_HEAD_WORDS = 2;

  private final SqlTokens tokens;
  private final Dbms dbms;

  /** 同じスキーマ・テーブル・操作の組は1つにまとめる（長い本体で同じテーブルを何度も書いても、メモリが言及の数に比例しないようにするため） */
  private final Set<TableReference> references = new LinkedHashSet<>();

  private final List<DynamicSqlKind> dynamicSql = new ArrayList<>();

  /** 文ごとの状態。文の終わりで丸ごと作り直す（戻し忘れが起きないようにするため） */
  private StatementState state = new StatementState();

  private TableReferenceScanner(List<SqlToken> tokens, Dbms dbms) {
    this.tokens = new SqlTokens(tokens);
    this.dbms = dbms;
  }

  /**
   * 定義本体の字句から、テーブルを指す位置の名前と動的SQLを拾うメソッド
   *
   * @param tokens 定義本体の字句（空白・コメントを除いたもの）
   */
  static ScanResult scan(List<SqlToken> tokens, Dbms dbms) {
    return new TableReferenceScanner(tokens, dbms).run();
  }

  private ScanResult run() {
    for (int i = 0; i < tokens.size(); i++) {
      visit(i);
    }
    return new ScanResult(List.copyOf(references), dynamicSql);
  }

  private void visit(int i) {
    final SqlToken token = tokens.at(i);
    if (token.isSymbol(";")) {
      state = new StatementState();
    } else if (token.isSymbol("(") || token.isSymbol("[")) {
      state.openGroup(tokens.isAnyWordAt(i - 1, FROM_SYNTAX_FUNCTIONS), i);
    } else if (token.isSymbol(")") || token.isSymbol("]")) {
      state.closeGroup();
    } else if (token.isSymbol(",")) {
      final CrudOperation listOperation = state.frame().listOperation;
      if (listOperation == CrudOperation.READ) {
        emit(readNameAt(i + 1), CrudOperation.READ);
      } else if (listOperation != null) {
        emit(targetNameAt(i + 1), listOperation);
      }
    } else if (token.isName()) {
      visitName(i, token);
    }
  }

  private void visitName(int i, SqlToken token) {
    // CTEの名前は引用符付きでもよく、動的SQLの{@code SYS.DBMS_SQL}・{@code public.dblink(}は{@code .}の直後の語のため、
    // どちらも、WORD以外・{@code .}の前後の字句を除く下の早期returnより前で判定する
    detectCommonTableExpression(i, token);
    final SqlToken next = tokens.at(i + 1);
    qualifiableDynamicSql(token, next).ifPresent(this::addDynamicSql);
    if (token.kind() != SqlTokenKind.WORD
        || tokens.isAfterDot(i)
        || tokens.isSymbolAt(i + 1, ".")) {
      return;
    }
    dynamicSql(i, token, next).ifPresent(this::addDynamicSql);
    final String word = AsciiCase.toUpper(token.source(), token.start(), token.end());
    if (LIST_TERMINATORS.contains(word) && terminatesList(i, word)) {
      state.frame().listOperation = null;
    }
    switch (word) {
      case "INSERT" -> onInsert(i, next);
      case "INTO" -> onInto(i);
      case "UPDATE" -> onUpdate(i, next);
      case "DELETE" -> onDelete(i, next);
      case "MERGE" -> onMerge(i, next);
      case "USING" -> onUsing(i);
      case "TRUNCATE" -> onTruncate(i, next);
      case "FROM" -> onFrom(i);
      case "JOIN", "APPLY" -> emit(readNameAt(i + 1), CrudOperation.READ);
      case "FETCH", "MOVE" -> state.fromIsNotTable |= isStatementStart(i);
      case "REVOKE" -> state.fromIsNotTable |= !isOperandPosition(i);
      case "RECURSIVE" -> state.frame().recursiveWith |= tokens.isWordAt(i - 1, "WITH");
      case "CASE" -> {
        if (!tokens.isWordAt(i - 1, "END")) {
          state.frame().caseDepth++;
        }
      }
      case "END" -> {
        if (state.frame().caseDepth > 0) {
          state.frame().caseDepth--;
        }
      }
      case "LOOP" -> {
        if (state.depth() == 0) {
          state = new StatementState();
        }
      }
      default -> {}
    }
  }

  /** {@code INSERT INTO t}・{@code INSERT ALL/FIRST}（Oracle）・MERGEの{@code THEN INSERT (...)} */
  private void onInsert(int i, SqlToken next) {
    if (isNonDmlUse(i) || next == null) {
      return;
    }
    if (next.isWord("INTO")) {
      state.insertTarget = targetNameAt(i + STATEMENT_HEAD_WORDS);
      emit(state.insertTarget, CrudOperation.CREATE);
      expectSubqueryTarget(i + STATEMENT_HEAD_WORDS, CrudOperation.CREATE);
    } else if (next.isWord("ALL") || next.isWord("FIRST")) {
      state.enterMultiInsert();
    } else if (next.isSymbol("(") || next.isWord("VALUES") || next.isWord("DEFAULT")) {
      emit(state.mergeTarget, CrudOperation.CREATE);
    }
  }

  /**
   * {@code INSERT ALL/FIRST}（Oracle）の各{@code INTO}。それ以外の{@code INTO}は、
   * {@code INSERT INTO}なら{@link #onInsert}が対象の名前まで読み済みで、{@code SELECT … INTO 変数}等はテーブルを指さないため、ここでは読まない
   */
  private void onInto(int i) {
    if (state.isMultiInsertLevel()) {
      emit(targetNameAt(i + 1), CrudOperation.CREATE);
    }
  }

  /** {@code UPDATE t}・MERGEの{@code THEN UPDATE SET}・{@code ON CONFLICT DO UPDATE SET} */
  private void onUpdate(int i, SqlToken next) {
    if (isNonDmlUse(i) || next == null) {
      return;
    }
    if (next.isWord("SET")) {
      emit(
          state.mergeTarget.isEmpty() ? state.insertTarget : state.mergeTarget,
          CrudOperation.UPDATE);
    } else {
      emit(targetNameAt(i + 1), CrudOperation.UPDATE);
      expectSubqueryTarget(i + 1, CrudOperation.UPDATE);
    }
  }

  /**
   * {@code DELETE FROM t}・{@code DELETE t}（Oracle）・MERGEの{@code THEN DELETE}・
   * {@code DELETE WHERE}（Oracle）
   */
  private void onDelete(int i, SqlToken next) {
    if (isNonDmlUse(i)) {
      return;
    }
    if (next != null && next.isWord("FROM")) {
      emit(targetNameAt(i + STATEMENT_HEAD_WORDS), CrudOperation.DELETE);
      expectSubqueryTarget(i + STATEMENT_HEAD_WORDS, CrudOperation.DELETE);
      state.enterDelete(i + 1);
    } else if (isSubqueryAt(i + 1)) {
      expectSubqueryTarget(i + 1, CrudOperation.DELETE);
    } else if (next != null && next.isName() && !next.isAnyWord(MERGE_DELETE_SUCCESSORS)) {
      emit(targetNameAt(i + 1), CrudOperation.DELETE);
      state.enterDelete(StatementState.NONE);
    } else {
      emit(state.mergeTarget, CrudOperation.DELETE);
    }
  }

  /** MERGEの対象の操作はWHEN句のINSERT・UPDATE・DELETEから付ける */
  private void onMerge(int i, SqlToken next) {
    if (next != null && next.isWord("INTO")) {
      state.mergeTarget = targetNameAt(i + STATEMENT_HEAD_WORDS);
      state.enterMerge();
    }
  }

  /**
   * MERGEの{@code USING}（1つ）とPostgreSQLのDELETEの{@code USING}（カンマ区切りの並び）。
   * JOINの{@code USING (列)}は、次が括弧のため読まない
   */
  private void onUsing(int i) {
    if (tokens.isSymbolAt(i + 1, "(") && !isSubqueryAt(i + 1)) {
      return;
    }
    if (state.isMergeLevel()) {
      emit(readNameAt(i + 1), CrudOperation.READ);
    } else if (state.isDeleteLevel()) {
      state.frame().listOperation = CrudOperation.READ;
      emit(readNameAt(i + 1), CrudOperation.READ);
    }
  }

  private void onTruncate(int i, SqlToken next) {
    if (isNonDmlUse(i)) {
      return;
    }
    final int start = next != null && next.isWord("TABLE") ? i + STATEMENT_HEAD_WORDS : i + 1;
    state.frame().listOperation = CrudOperation.DELETE;
    emit(targetNameAt(start), CrudOperation.DELETE);
  }

  /**
   * {@code IS [NOT] DISTINCT FROM}・{@code EXTRACT(… FROM …)}等・{@code FETCH … FROM カーソル}・
   * {@code REVOKE … FROM ロール}・Oracleの{@code NTH_VALUE(…) FROM FIRST}の{@code FROM}はテーブルを指さない。
   * {@code DELETE FROM t}の{@code FROM}は、{@link #onDelete}が対象の名前まで読み済み
   */
  private void onFrom(int i) {
    if (state.isDeleteFrom(i)
        || tokens.isWordAt(i - 1, "DISTINCT")
        || state.frame().fromIsFunctionSyntax
        || state.fromIsNotTable
        || isFromFirstOrLast(i)) {
      return;
    }
    state.frame().listOperation = CrudOperation.READ;
    // DMLの対象が副問い合わせのとき（expectSubqueryTargetで覚えたもの）は、その最初のFROMのテーブルに対象の操作を付ける
    final CrudOperation operation = state.takeSubqueryTarget().orElse(CrudOperation.READ);
    emit(readNameAt(i + 1), operation);
  }

  /**
   * Oracleの分析関数{@code NTH_VALUE(式, n) FROM FIRST|LAST [IGNORE|RESPECT NULLS] OVER (…)}の
   * {@code FROM}か
   */
  private boolean isFromFirstOrLast(int i) {
    final int firstOrLast = i + 1;
    return dbms == Dbms.ORACLE
        && (tokens.isWordAt(firstOrLast, "FIRST") || tokens.isWordAt(firstOrLast, "LAST"))
        && tokens.isAnyWordAt(firstOrLast + 1, NTH_VALUE_SUCCESSORS);
  }

  /** DMLの対象の位置が副問い合わせなら、その最初の{@code FROM}のテーブルに操作を付けるよう覚える */
  private void expectSubqueryTarget(int index, CrudOperation operation) {
    if (isSubqueryAt(index)) {
      state.expectSubqueryTarget(operation);
    }
  }

  private boolean isSubqueryAt(int index) {
    return tokens.isSymbolAt(index, "(") && tokens.isAnyWordAt(index + 1, QUERY_STARTS);
  }

  /**
   * {@code 名前 [(列…)] AS [[NOT] MATERIALIZED] (}を、その文の中のCTEの名前として覚える<br>
   * 列名の並びは名前とカンマだけを読み、それ以外が現れたらCTEではないとする（入れ子の括弧を数えないため、読む長さは並びの長さで済む）<br>
   * 自分の本体の中でCTEの名前が見えるのは、再帰（PostgreSQLの{@code WITH RECURSIVE}・Oracleの列名の並びを付けた再帰）のときだけで、
   * それ以外は本体を閉じた後から。本体の中の同名は実テーブル（{@code WITH t AS (SELECT * FROM t) …}）
   */
  private void detectCommonTableExpression(int i, SqlToken name) {
    if (!(tokens.isWordAt(i - 1, "WITH")
        || tokens.isWordAt(i - 1, "RECURSIVE")
        || tokens.isSymbolAt(i - 1, ","))) {
      return;
    }
    int j = i + 1;
    final boolean hasColumnList = tokens.isSymbolAt(j, "(");
    if (hasColumnList) {
      j++;
      while (tokens.isNameAt(j) || tokens.isSymbolAt(j, ",")) {
        j++;
      }
      if (!tokens.isSymbolAt(j, ")")) {
        return;
      }
      j++;
    }
    if (!tokens.isWordAt(j, "AS")) {
      return;
    }
    j++;
    if (tokens.isWordAt(j, "NOT")) {
      j++;
    }
    if (tokens.isWordAt(j, "MATERIALIZED")) {
      j++;
    }
    if (!tokens.isSymbolAt(j, "(")) {
      return;
    }
    final String cteName = SqlNames.identifier(name, dbms);
    if (state.frame().recursiveWith || (dbms == Dbms.ORACLE && hasColumnList)) {
      state.frame().addCommonTableExpression(cteName);
    } else {
      state.expectCommonTableExpressionBody(j, cteName);
    }
  }

  /** スキーマ・パッケージで修飾して呼ぶこともある動的SQL（{@code SYS.DBMS_SQL.PARSE}・{@code public.dblink(…)}） */
  private Optional<DynamicSqlKind> qualifiableDynamicSql(SqlToken token, SqlToken next) {
    if (next == null || token.kind() != SqlTokenKind.WORD) {
      return Optional.empty();
    }
    return switch (dbms) {
      case ORACLE ->
          token.isWord("DBMS_SQL") && next.isSymbol(".")
              ? Optional.of(DynamicSqlKind.DBMS_SQL)
              : Optional.empty();
      case POSTGRESQL ->
          next.isSymbol("(") && token.isAnyWord(DBLINK_SQL_FUNCTIONS)
              ? Optional.of(DynamicSqlKind.DBLINK)
              : Optional.empty();
    };
  }

  /** PostgreSQLの{@code EXECUTE}、Oracleの{@code EXECUTE IMMEDIATE}・{@code OPEN … FOR 文字列} */
  private Optional<DynamicSqlKind> dynamicSql(int i, SqlToken token, SqlToken next) {
    return switch (dbms) {
      case POSTGRESQL ->
          token.isWord("EXECUTE") && isDynamicExecute(i)
              ? Optional.of(DynamicSqlKind.EXECUTE)
              : Optional.empty();
      case ORACLE -> {
        if (token.isWord("EXECUTE") && next != null && next.isWord("IMMEDIATE")) {
          yield Optional.of(DynamicSqlKind.EXECUTE_IMMEDIATE);
        }
        yield token.isWord("OPEN") && isDynamicOpenFor(i)
            ? Optional.of(DynamicSqlKind.OPEN_FOR)
            : Optional.empty();
      }
    };
  }

  /**
   * PostgreSQLの{@code EXECUTE}が動的SQLか判定する<br>
   * {@code EXECUTE FUNCTION/PROCEDURE}はトリガーの定義の構文、
   * {@code GRANT EXECUTE}・{@code GRANT SELECT, EXECUTE}・{@code REVOKE GRANT OPTION FOR EXECUTE}は権限の名前、{@code SELECT execute FROM t}は列名のため除く
   */
  private boolean isDynamicExecute(int i) {
    final int previous = i - 1;
    return !(tokens.isWordAt(i + 1, "FUNCTION")
        || tokens.isWordAt(i + 1, "PROCEDURE")
        || isOperandPosition(i)
        || tokens.isAnyWordAt(previous, PRIVILEGE_PREDECESSORS)
        || (tokens.isWordAt(previous, "FOR") && tokens.isWordAt(previous - 1, "OPTION")));
  }

  private void addDynamicSql(DynamicSqlKind kind) {
    if (!dynamicSql.contains(kind)) {
      dynamicSql.add(kind);
    }
  }

  /** {@code OPEN カーソル FOR}の直後が{@code SELECT}・{@code WITH}・括弧以外（文字列・変数）ならカーソルのSQLは実行時に決まる */
  private boolean isDynamicOpenFor(int i) {
    int j = i + 1;
    final SqlToken cursor = tokens.at(j);
    if (cursor != null && cursor.kind() == SqlTokenKind.PARAMETER) {
      j++;
    } else if (cursor != null && cursor.isName()) {
      j = tokens.endOfQualifiedName(j);
    } else {
      return false;
    }
    if (!tokens.isWordAt(j, "FOR")) {
      return false;
    }
    final SqlToken source = tokens.at(j + 1);
    if (source == null) {
      return false;
    }
    return source.kind() == SqlTokenKind.STRING
        || source.kind() == SqlTokenKind.PARAMETER
        || (source.isName() && !source.isWord("SELECT") && !source.isWord("WITH"));
  }

  /**
   * 読み取りの位置（FROM・JOIN・USING・FROMの並びのカンマの後）から名前の部品を読むメソッド<br>
   * 括弧で囲んだJOIN（{@code FROM (a JOIN b ON …)}・{@code JOIN (b JOIN c ON …)}）は、括弧の中の先頭のテーブルを読む。
   * 括弧の後が{@code SELECT}等なら副問い合わせのため空（中の{@code FROM}で拾う）。
   * 直後の括弧は関数呼び出し（{@code FROM generate_series(…)}）を表すためテーブルとしない
   *
   * @return 名前の部品。位置の先頭が副問い合わせ・名前でない・関数呼び出し・DBリンク（{@code t@link}）の場合は空
   */
  private List<SqlToken> readNameAt(int index) {
    int j = index;
    while (tokens.isSymbolAt(j, "(")) {
      j++;
    }
    if (j > index && isSubqueryAt(j - 1)) {
      return List.of();
    }
    return nameAfterModifiers(j, true);
  }

  /**
   * 書き込みの位置（INSERT・UPDATE・DELETE・MERGE・TRUNCATEの対象）から名前の部品を読むメソッド<br>
   * INSERTの対象の直後の括弧は列名の並びを表すため、読み取りの位置と異なり、関数呼び出しとしない
   *
   * @return 名前の部品。位置の先頭が括弧（副問い合わせ）・名前でない・DBリンク（{@code t@link}）の場合は空
   */
  private List<SqlToken> targetNameAt(int index) {
    return nameAfterModifiers(index, false);
  }

  /**
   * {@code ONLY}・{@code LATERAL}を読み飛ばして名前の部品（{@code スキーマ.テーブル}等。最大3つ）を読むメソッド
   *
   * @param readPosition 読み取りの位置か。そうなら直後の括弧は関数呼び出しを表すため空にする
   */
  private List<SqlToken> nameAfterModifiers(int index, boolean readPosition) {
    int j = index;
    while (tokens.isAnyWordAt(j, TABLE_MODIFIERS)) {
      j++;
    }
    final List<SqlToken> parts = tokens.qualifiedNameAt(j);
    if (parts.isEmpty()) {
      return List.of();
    }
    final int end = tokens.endOfQualifiedName(j);
    if (tokens.isSymbolAt(end, "@") || (readPosition && tokens.isSymbolAt(end, "("))) {
      return List.of();
    }
    return parts;
  }

  /**
   * 名前の部品から、カタログでのスキーマ名・テーブル名を求めて記録する<br>
   * 読み取りの位置の、スキーマ修飾の無いCTEの名前は除く。書き込みの対象（INSERT・UPDATE・DELETE・MERGE・TRUNCATE）はCTEにならず、
   * 同名の実テーブルを指すため除かない
   */
  private void emit(List<SqlToken> parts, CrudOperation operation) {
    if (parts.isEmpty()) {
      return;
    }
    final String table = SqlNames.identifier(parts.get(parts.size() - 1), dbms);
    final String schema =
        parts.size() >= 2 ? SqlNames.identifier(parts.get(parts.size() - 2), dbms) : "";
    if (operation == CrudOperation.READ
        && schema.isEmpty()
        && state.isCommonTableExpression(table)) {
      return;
    }
    references.add(new TableReference(schema, table, operation));
  }

  /** 直前の字句から、INSERT・UPDATE・DELETE・TRUNCATEがDMLの文でない（トリガーのイベント・権限・行ロック・列名等）か判定する */
  private boolean isNonDmlUse(int i) {
    return tokens.isAnyWordAt(i - 1, NON_DML_PREDECESSORS) || isOperandPosition(i);
  }

  /** 直前の字句から、位置の語が式・列名・別名・テーブル名の位置（DMLの文の始まりでも、句の始まりでもない）にあるか判定する */
  private boolean isOperandPosition(int i) {
    final SqlToken previous = tokens.at(i - 1);
    if (previous == null) {
      return false;
    }
    if (previous.kind() == SqlTokenKind.SYMBOL) {
      return !CLAUSE_START_SYMBOLS.contains(previous.text());
    }
    return previous.isAnyWord(OPERAND_PREDECESSORS);
  }

  /**
   * 位置の語が、カンマ区切りのテーブルの並びを終えるか判定するメソッド<br>
   * 次の使い方の{@link #LIST_TERMINATORS}の語は、並びを終えない。テーブルの名前・別名・条件の中の列名（{@code FROM a, start}・
   * {@code AS limit}・{@code ON x = start}）、条件の中の{@code CASE}の分岐（{@code CASE WHEN … THEN … END}）、
   * Oracleの{@code PARTITION FOR (値)}
   */
  private boolean terminatesList(int i, String word) {
    if (isOperandPosition(i)) {
      return false;
    }
    if (CASE_CLAUSE_WORDS.contains(word) && state.frame().caseDepth > 0) {
      return false;
    }
    return !(word.equals("FOR") && tokens.isAnyWordAt(i - 1, PARTITION_KEYWORDS));
  }

  private boolean isStatementStart(int i) {
    final SqlToken previous = tokens.at(i - 1);
    return previous == null
        || previous.isSymbol(";")
        || previous.isSymbol(">>")
        || previous.isAnyWord(STATEMENT_PREDECESSORS);
  }

  /**
   * 文1つの中で覚えておく状態<br>
   * 文の終わり（{@code ;}・最上位の{@code LOOP}）で新しく作ることで、前の文の状態を持ち越さない。深さを覚える項目は、
   * 括弧の深さを{@link #NONE}（その文には無い）で初期化し、現在の深さと同じかどうかを{@code isXxxLevel}で問い合わせる。括弧の深さには{@code []}も数える
   */
  private static final class StatementState {

    private static final int NONE = -1;

    private final Deque<Frame> frames = new ArrayDeque<>();

    /** 本体を読んでいる最中のCTEの名前。本体の開き括弧の位置から、その名前を引く */
    private final Map<Integer, String> commonTableExpressionBodies = new HashMap<>();

    /** MERGEの対象。WHEN句のINSERT・UPDATE・DELETEの操作を付ける */
    List<SqlToken> mergeTarget = List.of();

    /** 直前のINSERTの対象。{@code ON CONFLICT DO UPDATE SET}の操作を付ける */
    List<SqlToken> insertTarget = List.of();

    /**
     * {@code FROM}の後ろがテーブルでない文（カーソルを読む{@code FETCH}・{@code MOVE}、権限を外す{@code REVOKE … FROM ロール}）か
     */
    boolean fromIsNotTable;

    private int mergeDepth = NONE;
    private int deleteDepth = NONE;
    private int multiInsertDepth = NONE;

    /** {@code DELETE FROM}のうち、対象の名前まで読み済みの{@code FROM}の位置 */
    private int consumedDeleteFrom = NONE;

    /**
     * 対象が副問い合わせのDML（Oracleの{@code UPDATE (SELECT … FROM t) SET …}等）の操作。
     * その副問い合わせの最初の{@code FROM}のテーブルに付ける
     */
    private CrudOperation subqueryTargetOperation;

    /** {@link #subqueryTargetOperation}の副問い合わせの括弧の深さ */
    private int subqueryTargetDepth = NONE;

    StatementState() {
      frames.push(new Frame(false, null));
    }

    Frame frame() {
      return frames.peek();
    }

    /** 括弧の深さ（括弧の外は0） */
    int depth() {
      return frames.size() - 1;
    }

    /**
     * 括弧・角括弧を開く
     *
     * @param index 開き括弧の位置。CTEの本体の開き括弧なら、その名前を覚える
     */
    void openGroup(boolean fromIsFunctionSyntax, int index) {
      frames.push(new Frame(fromIsFunctionSyntax, commonTableExpressionBodies.remove(index)));
    }

    /** 括弧の数が合わない文でも括弧の外より外へは戻らない。CTEの本体を閉じたら、その名前を括弧の外で見えるようにする */
    void closeGroup() {
      if (frames.size() > 1) {
        final Frame closed = frames.pop();
        if (closed.commonTableExpressionName != null) {
          frame().addCommonTableExpression(closed.commonTableExpressionName);
        }
      }
    }

    /** 本体の開き括弧の位置と、その本体を持つCTEの名前を覚える（閉じ括弧で見えるようにする） */
    void expectCommonTableExpressionBody(int openIndex, String name) {
      commonTableExpressionBodies.put(openIndex, name);
    }

    /** 名前が、いま見えるCTEの名前か（CTEは、定義した括弧の中とその内側でだけ見える） */
    boolean isCommonTableExpression(String name) {
      for (final Frame frame : frames) {
        if (frame.commonTableExpressions.contains(name)) {
          return true;
        }
      }
      return false;
    }

    /** MERGE文の中の、MERGEと同じ括弧の深さにいるか（{@code MERGE INTO}を読んだ後の{@code USING}を判定する） */
    void enterMerge() {
      mergeDepth = depth();
    }

    boolean isMergeLevel() {
      return depth() == mergeDepth;
    }

    /**
     * DELETE文の中の、DELETEと同じ括弧の深さにいるか（{@code DELETE … USING}を判定する）
     *
     * @param consumedFrom 対象の名前まで読み済みの{@code FROM}の位置。{@code FROM}が無ければ{@link #NONE}
     */
    void enterDelete(int consumedFrom) {
      deleteDepth = depth();
      consumedDeleteFrom = consumedFrom;
    }

    boolean isDeleteLevel() {
      return depth() == deleteDepth;
    }

    /** 位置の{@code FROM}が、{@code DELETE FROM}の対象の名前まで読み済みのものか */
    boolean isDeleteFrom(int index) {
      return index == consumedDeleteFrom;
    }

    /** {@code INSERT ALL/FIRST}の各{@code INTO}を判定する */
    void enterMultiInsert() {
      multiInsertDepth = depth();
    }

    boolean isMultiInsertLevel() {
      return depth() == multiInsertDepth;
    }

    /** 直後の括弧の中の最初の{@code FROM}を、副問い合わせを対象とするDMLの対象として覚える */
    void expectSubqueryTarget(CrudOperation operation) {
      subqueryTargetOperation = operation;
      subqueryTargetDepth = depth() + 1;
    }

    /**
     * 覚えた副問い合わせの対象の操作を、1度だけ取り出すメソッド
     *
     * @return 現在の深さが覚えた副問い合わせの深さと同じ場合は、その操作
     */
    Optional<CrudOperation> takeSubqueryTarget() {
      if (subqueryTargetOperation == null || depth() != subqueryTargetDepth) {
        return Optional.empty();
      }
      final CrudOperation operation = subqueryTargetOperation;
      subqueryTargetOperation = null;
      return Optional.of(operation);
    }
  }

  /** 括弧1段分の状態 */
  private static final class Frame {

    /**
     * 括弧の直前の語が、{@code FROM}をテーブルの指定以外の意味で使う関数（{@link TableReferenceScanner#FROM_SYNTAX_FUNCTIONS}）か
     */
    private final boolean fromIsFunctionSyntax;

    /** この括弧がCTEの本体なら、そのCTEの名前。そうでなければnull */
    private final String commonTableExpressionName;

    /** この括弧の中で定義したCTEの名前（カタログでの名前）。この括弧を閉じると見えなくなる */
    private Set<String> commonTableExpressions = Set.of();

    /** カンマの直後をテーブルの位置とするカンマ区切りの並びの中にいる場合の操作（FROM・USINGはR、TRUNCATEはD）。並びの中にいなければnull */
    private CrudOperation listOperation;

    /** 開いたままの{@code CASE}の数 */
    private int caseDepth;

    /** この括弧の中の{@code WITH}が{@code RECURSIVE}か */
    private boolean recursiveWith;

    private Frame(boolean fromIsFunctionSyntax, String commonTableExpressionName) {
      this.fromIsFunctionSyntax = fromIsFunctionSyntax;
      this.commonTableExpressionName = commonTableExpressionName;
    }

    private void addCommonTableExpression(String name) {
      if (commonTableExpressions.isEmpty()) {
        commonTableExpressions = new HashSet<>();
      }
      commonTableExpressions.add(name);
    }
  }
}
