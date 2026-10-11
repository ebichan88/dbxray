package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 定義本体の字句から、テーブルを指す位置の名前と操作（C・R・U・D）、動的SQLを拾うクラス<br>
 * 完全な構文解析はせず、「この位置の名前はテーブルを指す」というきっかけのキーワード（{@code INSERT INTO}・{@code FROM}等）と、
 * その直前・直後の字句だけで判定する。{@code ;}と最上位の{@code LOOP}（{@code FOR r IN 問い合わせ LOOP}）で文の状態（{@link
 * StatementState}）を作り直すため、 括弧の数が合わない文があっても次の文から読み直せる。字句を先頭から1回なめるだけで終える
 */
final class TableReferenceScanner {

  /** 直前にあると、INSERT・UPDATE・DELETEがDMLの文ではない（トリガー・権限・ポリシー・行ロックの構文）語 */
  private static final Set<String> NON_DML_PREDECESSORS =
      Set.of("OR", "ON", "OF", "BEFORE", "AFTER", "FOR", "KEY", "INSTEAD", "GRANT", "REVOKE");

  /**
   * 同じ括弧の深さで現れると、FROM等のカンマ区切りのテーブルの並びを終える語。{@code ON}は含めない（JOINの条件の後にカンマで続くため）<br>
   * 並びの後ろに続く句・演算子・分岐やループの区切り・{@code TRUNCATE}の指定の語を挙げている。{@code ;}を欠いた本体や制御構文の途中でも、
   * その後ろのカンマ区切りをテーブルの並びとして読まないための歯止めで、語ごとの働きは{@code TableReferenceScannerTest}の {@code
   * testListTerminators}で確かめている
   */
  private static final Set<String> LIST_TERMINATORS =
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

  /** 括弧の中で{@code FROM}をテーブルの指定以外の意味で使う関数（{@code EXTRACT(YEAR FROM d)}等） */
  private static final Set<String> FROM_SYNTAX_FUNCTIONS =
      Set.of("EXTRACT", "SUBSTRING", "TRIM", "OVERLAY");

  /** 直後から新しい文が始まる語（{@code ;}・ラベルの{@code >>}のほか） */
  private static final Set<String> STATEMENT_PREDECESSORS =
      Set.of("BEGIN", "THEN", "ELSE", "LOOP", "DECLARE");

  /** テーブルの名前の前に置ける語 */
  private static final Set<String> TABLE_MODIFIERS = Set.of("ONLY", "LATERAL");

  /**
   * 直前にあると、{@code EXECUTE}が動的SQLではなく権限の名前（{@code GRANT EXECUTE ON …}・{@code GRANT SELECT, EXECUTE
   * ON …}）である語
   */
  private static final Set<String> PRIVILEGE_PREDECESSORS = Set.of("GRANT", "REVOKE", ",");

  /** 括弧の直後にあると、括弧が副問い合わせ（括弧で囲んだJOINではない）であることを表す語 */
  private static final Set<String> QUERY_STARTS = Set.of("SELECT", "WITH", "VALUES");

  /** DELETEの直後にあると、DELETEが対象を持たない（MERGEのDELETE）語 */
  private static final Set<String> MERGE_DELETE_SUCCESSORS = Set.of("WHERE", "WHEN");

  private final SqlTokens tokens;
  private final Dbms dbms;
  private final List<TableReference> references = new ArrayList<>();
  private final Set<DynamicSqlKind> dynamicSql = new LinkedHashSet<>();

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
    return new ScanResult(references, List.copyOf(dynamicSql));
  }

  private void visit(int i) {
    final SqlToken token = tokens.at(i);
    if (token.isSymbol(";")) {
      state = new StatementState();
    } else if (token.isSymbol("(")) {
      final SqlToken previous = tokens.at(i - 1);
      state.openParen(
          previous != null && previous.kind() == SqlTokenKind.WORD
              ? AsciiCase.toUpper(previous.text())
              : "");
    } else if (token.isSymbol(")")) {
      state.closeParen();
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
    detectQualifiableDynamicSql(i, token);
    final SqlToken next = tokens.at(i + 1);
    if (token.kind() != SqlTokenKind.WORD
        || tokens.isAfterDot(i)
        || (next != null && next.isSymbol("."))) {
      return;
    }
    detectDynamicSql(i, token, next);
    final String word = AsciiCase.toUpper(token.text());
    if (LIST_TERMINATORS.contains(word)) {
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
      case "FETCH", "MOVE", "REVOKE" -> state.fromIsNotTable |= isStatementStart(i);
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
      state.insertTarget = targetNameAt(i + 2);
      emit(state.insertTarget, CrudOperation.CREATE);
      expectSubqueryTarget(i + 2, CrudOperation.CREATE);
    } else if (next.isWord("ALL") || next.isWord("FIRST")) {
      state.enterMultiInsert();
    } else if (next.isSymbol("(") || next.isWord("VALUES") || next.isWord("DEFAULT")) {
      emit(state.mergeTarget, CrudOperation.CREATE);
    }
  }

  /**
   * {@code INSERT ALL/FIRST}（Oracle）の各{@code INTO}。それ以外の{@code INTO}は、{@code INSERT INTO}なら{@link
   * #onInsert}が対象の名前まで読み済みで、{@code SELECT … INTO 変数}等はテーブルを指さないため、ここでは読まない
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
   * {@code DELETE FROM t}・{@code DELETE t}（Oracle）・MERGEの{@code THEN DELETE}・{@code DELETE
   * WHERE}（Oracle）
   */
  private void onDelete(int i, SqlToken next) {
    if (isNonDmlUse(i)) {
      return;
    }
    if (next != null && next.isWord("FROM")) {
      emit(targetNameAt(i + 2), CrudOperation.DELETE);
      expectSubqueryTarget(i + 2, CrudOperation.DELETE);
      state.enterDelete();
    } else if (isSubqueryAt(i + 1)) {
      expectSubqueryTarget(i + 1, CrudOperation.DELETE);
    } else if (next != null && next.isName() && !next.isAnyWord(MERGE_DELETE_SUCCESSORS)) {
      emit(targetNameAt(i + 1), CrudOperation.DELETE);
      state.enterDelete();
    } else {
      emit(state.mergeTarget, CrudOperation.DELETE);
    }
  }

  /** MERGEの対象の操作はWHEN句のINSERT・UPDATE・DELETEから付ける */
  private void onMerge(int i, SqlToken next) {
    if (next != null && next.isWord("INTO")) {
      state.mergeTarget = targetNameAt(i + 2);
      state.enterMerge();
    }
  }

  /**
   * MERGEの{@code USING}（1つ）とPostgreSQLのDELETEの{@code USING}（カンマ区切りの並び）。JOINの{@code USING
   * (列)}は、次が括弧のため読まない
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
    final int start = next != null && next.isWord("TABLE") ? i + 2 : i + 1;
    state.frame().listOperation = CrudOperation.DELETE;
    emit(targetNameAt(start), CrudOperation.DELETE);
  }

  /**
   * {@code IS [NOT] DISTINCT FROM}・{@code EXTRACT(… FROM …)}等・{@code FETCH … FROM カーソル}・{@code
   * REVOKE … FROM ロール}の{@code FROM}はテーブルを指さない。{@code DELETE FROM t}の{@code FROM}は、{@link
   * #onDelete}が対象の名前まで読み済み
   */
  private void onFrom(int i) {
    final SqlToken previous = tokens.at(i - 1);
    if ((previous != null && (previous.isWord("DISTINCT") || previous.isWord("DELETE")))
        || FROM_SYNTAX_FUNCTIONS.contains(state.frame().functionName)
        || state.fromIsNotTable) {
      return;
    }
    state.frame().listOperation = CrudOperation.READ;
    // DMLの対象が副問い合わせのとき（expectSubqueryTargetで覚えたもの）は、その最初のFROMのテーブルに対象の操作を付ける
    final CrudOperation operation = state.takeSubqueryTarget().orElse(CrudOperation.READ);
    emit(readNameAt(i + 1), operation);
  }

  /** DMLの対象の位置が副問い合わせなら、その最初の{@code FROM}のテーブルに操作を付けるよう覚える */
  private void expectSubqueryTarget(int index, CrudOperation operation) {
    if (isSubqueryAt(index)) {
      state.expectSubqueryTarget(operation);
    }
  }

  private boolean isSubqueryAt(int index) {
    final SqlToken first = tokens.at(index + 1);
    return tokens.isSymbolAt(index, "(") && first != null && first.isAnyWord(QUERY_STARTS);
  }

  /**
   * {@code 名前 [(列…)] AS [[NOT] MATERIALIZED] (}を、その文の中のCTEの名前として覚える<br>
   * 列名の並びは名前とカンマだけを読み、それ以外が現れたらCTEではないとする（入れ子の括弧を数えないため、読む長さは並びの長さで済む）
   */
  private void detectCommonTableExpression(int i, SqlToken name) {
    final SqlToken previous = tokens.at(i - 1);
    if (previous == null
        || !(previous.isWord("WITH") || previous.isWord("RECURSIVE") || previous.isSymbol(","))) {
      return;
    }
    int j = i + 1;
    if (tokens.isSymbolAt(j, "(")) {
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
    if (tokens.isSymbolAt(j, "(")) {
      state.cteNames.add(SqlNames.identifier(name, dbms));
    }
  }

  /** スキーマ・パッケージで修飾して呼ぶこともある動的SQL（{@code SYS.DBMS_SQL.PARSE}・{@code public.dblink(…)}） */
  private void detectQualifiableDynamicSql(int i, SqlToken token) {
    final SqlToken next = tokens.at(i + 1);
    if (next == null || token.kind() != SqlTokenKind.WORD) {
      return;
    }
    switch (dbms) {
      case ORACLE -> {
        if (token.isWord("DBMS_SQL") && next.isSymbol(".")) {
          dynamicSql.add(DynamicSqlKind.DBMS_SQL);
        }
      }
      case POSTGRESQL -> {
        if ((token.isWord("DBLINK") || token.isWord("DBLINK_EXEC")) && next.isSymbol("(")) {
          dynamicSql.add(DynamicSqlKind.DBLINK);
        }
      }
    }
  }

  /**
   * PostgreSQLの{@code EXECUTE}（{@code EXECUTE FUNCTION/PROCEDURE}はトリガーの定義の構文、{@code GRANT
   * EXECUTE}等は権限の名前のため除く）、Oracleの{@code EXECUTE IMMEDIATE}・{@code OPEN … FOR 文字列}
   */
  private void detectDynamicSql(int i, SqlToken token, SqlToken next) {
    switch (dbms) {
      case POSTGRESQL -> {
        final SqlToken previous = tokens.at(i - 1);
        if (token.isWord("EXECUTE")
            && !(next != null && (next.isWord("FUNCTION") || next.isWord("PROCEDURE")))
            && !(previous != null
                && PRIVILEGE_PREDECESSORS.contains(AsciiCase.toUpper(previous.text())))) {
          dynamicSql.add(DynamicSqlKind.EXECUTE);
        }
      }
      case ORACLE -> {
        if (token.isWord("EXECUTE") && next != null && next.isWord("IMMEDIATE")) {
          dynamicSql.add(DynamicSqlKind.EXECUTE_IMMEDIATE);
        }
        if (token.isWord("OPEN") && isDynamicOpenFor(i)) {
          dynamicSql.add(DynamicSqlKind.OPEN_FOR);
        }
      }
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
   * 括弧の後が{@code SELECT}等なら副問い合わせのため空（中の{@code FROM}で拾う）。直後の括弧は関数呼び出し（{@code FROM
   * generate_series(…)}）を表すためテーブルとしない
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
    while (tokens.at(j) != null && tokens.at(j).isAnyWord(TABLE_MODIFIERS)) {
      j++;
    }
    final List<SqlToken> parts = tokens.qualifiedNameAt(j);
    final int end = j + 2 * parts.size() - 1;
    if (parts.isEmpty()
        || tokens.isSymbolAt(end, "@")
        || (readPosition && tokens.isSymbolAt(end, "("))) {
      return List.of();
    }
    return parts;
  }

  /** 名前の部品から、カタログでのスキーマ名・テーブル名を求めて記録する。スキーマ修飾の無いCTEの名前は除く */
  private void emit(List<SqlToken> parts, CrudOperation operation) {
    if (parts.isEmpty()) {
      return;
    }
    final String table = SqlNames.identifier(parts.get(parts.size() - 1), dbms);
    final String schema =
        parts.size() >= 2 ? SqlNames.identifier(parts.get(parts.size() - 2), dbms) : "";
    if (schema.isEmpty() && state.cteNames.contains(table)) {
      return;
    }
    references.add(new TableReference(schema, table, operation));
  }

  /** 直前の語から、INSERT・UPDATE・DELETEがDMLの文でない（トリガーのイベント・権限・行ロック等）か判定する */
  private boolean isNonDmlUse(int i) {
    final SqlToken previous = tokens.at(i - 1);
    return previous != null && (previous.isSymbol(",") || previous.isAnyWord(NON_DML_PREDECESSORS));
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
   * 文の終わり（{@code ;}・最上位の{@code LOOP}）で新しく作ることで、前の文の状態を持ち越さない。深さを覚える項目は、 括弧の深さを{@link
   * #NONE}（その文には無い）で初期化し、現在の深さと同じかどうかを{@code isXxxLevel}で問い合わせる
   */
  private static final class StatementState {

    private static final int NONE = -1;

    private final Deque<Frame> frames = new ArrayDeque<>();

    /** この文の中のCTEの名前（カタログでの名前） */
    final Set<String> cteNames = new HashSet<>();

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

    /**
     * 対象が副問い合わせのDML（Oracleの{@code UPDATE (SELECT … FROM t) SET …}等）の操作。その副問い合わせの最初の{@code
     * FROM}のテーブルに付ける
     */
    private CrudOperation subqueryTargetOperation;

    /** {@link #subqueryTargetOperation}の副問い合わせの括弧の深さ */
    private int subqueryTargetDepth = NONE;

    StatementState() {
      frames.push(new Frame(""));
    }

    Frame frame() {
      return frames.peek();
    }

    /** 括弧の深さ（括弧の外は0） */
    int depth() {
      return frames.size() - 1;
    }

    void openParen(String functionName) {
      frames.push(new Frame(functionName));
    }

    /** 括弧の数が合わない文でも括弧の外より外へは戻らない */
    void closeParen() {
      if (frames.size() > 1) {
        frames.pop();
      }
    }

    /** MERGE文の中の、MERGEと同じ括弧の深さにいるか（{@code MERGE INTO}を読んだ後の{@code USING}を判定する） */
    void enterMerge() {
      mergeDepth = depth();
    }

    boolean isMergeLevel() {
      return depth() == mergeDepth;
    }

    /** DELETE文の中の、DELETEと同じ括弧の深さにいるか（{@code DELETE … USING}を判定する） */
    void enterDelete() {
      deleteDepth = depth();
    }

    boolean isDeleteLevel() {
      return depth() == deleteDepth;
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

    /** 括弧の直前の語（関数呼び出しの名前。直前が語でなければ空文字） */
    private final String functionName;

    /** カンマの直後をテーブルの位置とするカンマ区切りの並びの中にいる場合の操作（FROM・USINGはR、TRUNCATEはD）。並びの中にいなければnull */
    private CrudOperation listOperation;

    private Frame(String functionName) {
      this.functionName = functionName;
    }
  }
}
