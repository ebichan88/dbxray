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
import java.util.Set;

/**
 * 定義本体の字句から、テーブルを指す位置の名前と操作（C・R・U・D）、動的SQLを拾うクラス<br>
 * 完全な構文解析はせず、「この位置の名前はテーブルを指す」というきっかけのキーワード（{@code INSERT INTO}・{@code FROM}等）と、
 * その直前・直後の字句だけで判定する。{@code ;}と最上位の{@code LOOP}（{@code FOR r IN 問い合わせ
 * LOOP}）で文の状態（括弧の深さ・MERGE/INSERTの対象・CTEの名前等）を戻すため、 括弧の数が合わない文があっても次の文から読み直せる。字句を先頭から1回なめるだけで終える
 */
final class TableReferenceScanner {

  /** 直前にあると、INSERT・UPDATE・DELETEがDMLの文ではない（トリガー・権限・ポリシー・行ロックの構文）語 */
  private static final Set<String> NON_DML_PREDECESSORS =
      Set.of("OR", "ON", "OF", "BEFORE", "AFTER", "FOR", "KEY", "INSTEAD", "GRANT", "REVOKE");

  /** 同じ括弧の深さで現れると、FROM等のカンマ区切りのテーブルの並びを終える語。{@code ON}は含めない（JOINの条件の後にカンマで続くため） */
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

  private final List<SqlToken> tokens;
  private final Dbms dbms;
  private final List<TableReference> references = new ArrayList<>();
  private final Set<DynamicSqlKind> dynamicSql = new LinkedHashSet<>();

  // 以降は文ごとの状態（resetStatementで戻す）
  private final Deque<Frame> frames = new ArrayDeque<>();
  private final Set<String> cteNames = new HashSet<>();
  private List<SqlToken> mergeTarget = List.of();
  private List<SqlToken> insertTarget = List.of();
  private int mergeDepth;
  private int deleteDepth;
  private int multiInsertDepth;

  /**
   * 対象が副問い合わせのDML（Oracleの{@code UPDATE (SELECT … FROM t) SET …}等）の操作。その副問い合わせの最初の{@code
   * FROM}のテーブルに付ける
   */
  private CrudOperation subqueryTargetOperation;

  /** {@link #subqueryTargetOperation}の副問い合わせの括弧の深さ */
  private int subqueryTargetDepth;

  /** {@code FROM}の後ろがテーブルでない文（カーソルを読む{@code FETCH}・{@code MOVE}、権限を外す{@code REVOKE … FROM ロール}）か */
  private boolean fromIsNotTable;

  private TableReferenceScanner(List<SqlToken> tokens, Dbms dbms) {
    this.tokens = tokens;
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
    resetStatement();
    for (int i = 0; i < tokens.size(); i++) {
      visit(i);
    }
    return new ScanResult(references, List.copyOf(dynamicSql));
  }

  private void visit(int i) {
    final SqlToken token = tokens.get(i);
    if (token.isSymbol(";")) {
      resetStatement();
    } else if (token.isSymbol("(")) {
      final SqlToken previous = at(i - 1);
      frames.push(
          new Frame(
              previous != null && previous.kind() == SqlTokenKind.WORD
                  ? AsciiCase.toUpper(previous.text())
                  : ""));
    } else if (token.isSymbol(")")) {
      if (frames.size() > 1) {
        frames.pop();
      }
    } else if (token.isSymbol(",")) {
      if (frame().listOperation == CrudOperation.READ) {
        emit(readNameAt(i + 1), CrudOperation.READ);
      } else if (frame().listOperation != null) {
        emit(nameAt(i + 1, false), frame().listOperation);
      }
    } else if (token.isName()) {
      visitName(i, token);
    }
  }

  private void visitName(int i, SqlToken token) {
    detectCommonTableExpression(i, token);
    detectQualifiableDynamicSql(i, token);
    final SqlToken next = at(i + 1);
    if (token.kind() != SqlTokenKind.WORD
        || isAfterDot(i)
        || (next != null && next.isSymbol("."))) {
      return;
    }
    detectDynamicSql(i, token, next);
    final String word = AsciiCase.toUpper(token.text());
    if (LIST_TERMINATORS.contains(word)) {
      frame().listOperation = null;
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
      case "FETCH", "MOVE", "REVOKE" -> fromIsNotTable |= isStatementStart(i);
      case "LOOP" -> {
        if (depth() == 0) {
          resetStatement();
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
      insertTarget = nameAt(i + 2, false);
      emit(insertTarget, CrudOperation.CREATE);
      expectSubqueryTarget(i + 2, CrudOperation.CREATE);
    } else if (next.isWord("ALL") || next.isWord("FIRST")) {
      multiInsertDepth = depth();
    } else if (next.isSymbol("(") || next.isWord("VALUES") || next.isWord("DEFAULT")) {
      emit(mergeTarget, CrudOperation.CREATE);
    }
  }

  /**
   * {@code INSERT ALL/FIRST}（Oracle）の各{@code INTO}。それ以外の{@code INTO}（{@code SELECT … INTO
   * 変数}等）はテーブルを指さない
   */
  private void onInto(int i) {
    if (depth() == multiInsertDepth) {
      emit(nameAt(i + 1, false), CrudOperation.CREATE);
    }
  }

  /** {@code UPDATE t}・MERGEの{@code THEN UPDATE SET}・{@code ON CONFLICT DO UPDATE SET} */
  private void onUpdate(int i, SqlToken next) {
    if (isNonDmlUse(i) || next == null) {
      return;
    }
    if (next.isWord("SET")) {
      emit(mergeTarget.isEmpty() ? insertTarget : mergeTarget, CrudOperation.UPDATE);
    } else {
      emit(nameAt(i + 1, false), CrudOperation.UPDATE);
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
      emit(nameAt(i + 2, false), CrudOperation.DELETE);
      expectSubqueryTarget(i + 2, CrudOperation.DELETE);
      deleteDepth = depth();
    } else if (isSubqueryAt(i + 1)) {
      expectSubqueryTarget(i + 1, CrudOperation.DELETE);
    } else if (next != null
        && next.isName()
        && !(next.kind() == SqlTokenKind.WORD
            && MERGE_DELETE_SUCCESSORS.contains(AsciiCase.toUpper(next.text())))) {
      emit(nameAt(i + 1, false), CrudOperation.DELETE);
      deleteDepth = depth();
    } else {
      emit(mergeTarget, CrudOperation.DELETE);
    }
  }

  /** MERGEの対象の操作はWHEN句のINSERT・UPDATE・DELETEから付ける */
  private void onMerge(int i, SqlToken next) {
    if (next != null && next.isWord("INTO")) {
      mergeTarget = nameAt(i + 2, false);
      mergeDepth = depth();
    }
  }

  /**
   * MERGEの{@code USING}（1つ）とPostgreSQLのDELETEの{@code USING}（カンマ区切りの並び）。JOINの{@code USING
   * (列)}は次が括弧のため名前にならない
   */
  private void onUsing(int i) {
    if (depth() == mergeDepth) {
      emit(nameAt(i + 1, true), CrudOperation.READ);
    } else if (depth() == deleteDepth) {
      frame().listOperation = CrudOperation.READ;
      emit(nameAt(i + 1, true), CrudOperation.READ);
    }
  }

  private void onTruncate(int i, SqlToken next) {
    final int start = next != null && next.isWord("TABLE") ? i + 2 : i + 1;
    frame().listOperation = CrudOperation.DELETE;
    emit(nameAt(start, false), CrudOperation.DELETE);
  }

  /**
   * {@code IS [NOT] DISTINCT FROM}・{@code EXTRACT(… FROM …)}等・{@code FETCH … FROM カーソル}・{@code
   * REVOKE … FROM ロール}の{@code FROM}はテーブルを指さない
   */
  private void onFrom(int i) {
    final SqlToken previous = at(i - 1);
    if ((previous != null && (previous.isWord("DISTINCT") || previous.isWord("DELETE")))
        || FROM_SYNTAX_FUNCTIONS.contains(frame().functionName)
        || fromIsNotTable) {
      return;
    }
    frame().listOperation = CrudOperation.READ;
    if (subqueryTargetOperation != null && depth() == subqueryTargetDepth) {
      emit(readNameAt(i + 1), subqueryTargetOperation);
      subqueryTargetOperation = null;
    } else {
      emit(readNameAt(i + 1), CrudOperation.READ);
    }
  }

  /** DMLの対象の位置が副問い合わせなら、その最初の{@code FROM}のテーブルに操作を付けるよう覚える */
  private void expectSubqueryTarget(int index, CrudOperation operation) {
    if (isSubqueryAt(index)) {
      subqueryTargetOperation = operation;
      subqueryTargetDepth = depth() + 1;
    }
  }

  private boolean isSubqueryAt(int index) {
    return isSymbolAt(index, "(")
        && at(index + 1) != null
        && at(index + 1).kind() == SqlTokenKind.WORD
        && QUERY_STARTS.contains(AsciiCase.toUpper(at(index + 1).text()));
  }

  /**
   * {@code 名前 [(列…)] AS [[NOT] MATERIALIZED] (}を、その文の中のCTEの名前として覚える<br>
   * 列名の並びは名前とカンマだけを読み、それ以外が現れたらCTEではないとする（入れ子の括弧を数えないため、読む長さは並びの長さで済む）
   */
  private void detectCommonTableExpression(int i, SqlToken name) {
    final SqlToken previous = at(i - 1);
    if (previous == null
        || !(previous.isWord("WITH") || previous.isWord("RECURSIVE") || previous.isSymbol(","))) {
      return;
    }
    int j = i + 1;
    if (isSymbolAt(j, "(")) {
      j++;
      while (at(j) != null && (at(j).isName() || at(j).isSymbol(","))) {
        j++;
      }
      if (!isSymbolAt(j, ")")) {
        return;
      }
      j++;
    }
    if (!isWordAt(j, "AS")) {
      return;
    }
    j++;
    if (isWordAt(j, "NOT")) {
      j++;
    }
    if (isWordAt(j, "MATERIALIZED")) {
      j++;
    }
    if (isSymbolAt(j, "(")) {
      cteNames.add(SqlNames.identifier(name, dbms));
    }
  }

  /** スキーマ・パッケージで修飾して呼ぶこともある動的SQL（{@code SYS.DBMS_SQL.PARSE}・{@code public.dblink(…)}） */
  private void detectQualifiableDynamicSql(int i, SqlToken token) {
    final SqlToken next = at(i + 1);
    if (next == null || token.kind() != SqlTokenKind.WORD) {
      return;
    }
    if (dbms == Dbms.ORACLE && token.isWord("DBMS_SQL") && next.isSymbol(".")) {
      dynamicSql.add(DynamicSqlKind.DBMS_SQL);
    }
    if (dbms == Dbms.POSTGRESQL
        && (token.isWord("DBLINK") || token.isWord("DBLINK_EXEC"))
        && next.isSymbol("(")) {
      dynamicSql.add(DynamicSqlKind.DBLINK);
    }
  }

  /**
   * PostgreSQLの{@code EXECUTE}（{@code EXECUTE FUNCTION/PROCEDURE}はトリガーの定義の構文、{@code GRANT
   * EXECUTE}等は権限の名前のため除く）、Oracleの{@code EXECUTE IMMEDIATE}・{@code OPEN … FOR 文字列}
   */
  private void detectDynamicSql(int i, SqlToken token, SqlToken next) {
    if (dbms == Dbms.POSTGRESQL) {
      final SqlToken previous = at(i - 1);
      if (token.isWord("EXECUTE")
          && !(next != null && (next.isWord("FUNCTION") || next.isWord("PROCEDURE")))
          && !(previous != null
              && PRIVILEGE_PREDECESSORS.contains(AsciiCase.toUpper(previous.text())))) {
        dynamicSql.add(DynamicSqlKind.EXECUTE);
      }
      return;
    }
    if (token.isWord("EXECUTE") && next != null && next.isWord("IMMEDIATE")) {
      dynamicSql.add(DynamicSqlKind.EXECUTE_IMMEDIATE);
    }
    if (token.isWord("OPEN") && isDynamicOpenFor(i)) {
      dynamicSql.add(DynamicSqlKind.OPEN_FOR);
    }
  }

  /** {@code OPEN カーソル FOR}の直後が{@code SELECT}・{@code WITH}・括弧以外（文字列・変数）ならカーソルのSQLは実行時に決まる */
  private boolean isDynamicOpenFor(int i) {
    int j = i + 1;
    if (at(j) != null && at(j).kind() == SqlTokenKind.PARAMETER) {
      j++;
    } else if (at(j) != null && at(j).isName()) {
      j++;
      while (isSymbolAt(j, ".") && at(j + 1) != null && at(j + 1).isName()) {
        j += 2;
      }
    } else {
      return false;
    }
    if (!isWordAt(j, "FOR")) {
      return false;
    }
    final SqlToken source = at(j + 1);
    if (source == null) {
      return false;
    }
    return source.kind() == SqlTokenKind.STRING
        || source.kind() == SqlTokenKind.PARAMETER
        || (source.isName() && !source.isWord("SELECT") && !source.isWord("WITH"));
  }

  /**
   * 読み取りの位置（FROM・JOIN・FROMの並びのカンマの後）から名前の部品を読むメソッド<br>
   * 括弧で囲んだJOIN（{@code FROM (a JOIN b ON …)}・{@code JOIN (b JOIN c ON …)}）は、括弧の中の先頭のテーブルを読む。
   * 括弧の後が{@code SELECT}等なら副問い合わせのため空（中の{@code FROM}で拾う）
   */
  private List<SqlToken> readNameAt(int index) {
    int j = index;
    while (isSymbolAt(j, "(")) {
      j++;
    }
    if (j > index && isSubqueryAt(j - 1)) {
      return List.of();
    }
    return nameAt(j, true);
  }

  /**
   * テーブルを指す位置から名前の部品（{@code スキーマ.テーブル}等。最大3つ）を読むメソッド
   *
   * @param readPosition 読み取りの位置（FROM・JOIN・USING等）か。読み取りの位置では、直後の括弧は関数呼び出し（{@code FROM
   *     generate_series(…)}）を表すためテーブルとしない。INSERTの対象では列名の並びを表すためテーブルとする
   * @return 名前の部品。位置の先頭が括弧（副問い合わせ）・名前でない・関数呼び出し・DBリンク（{@code t@link}）の場合は空
   */
  private List<SqlToken> nameAt(int index, boolean readPosition) {
    int j = index;
    while (at(j) != null
        && at(j).kind() == SqlTokenKind.WORD
        && TABLE_MODIFIERS.contains(AsciiCase.toUpper(at(j).text()))) {
      j++;
    }
    final SqlToken first = at(j);
    if (first == null || !first.isName()) {
      return List.of();
    }
    final List<SqlToken> parts = new ArrayList<>(List.of(first));
    int k = j + 1;
    while (parts.size() < 3 && isSymbolAt(k, ".") && at(k + 1) != null && at(k + 1).isName()) {
      parts.add(at(k + 1));
      k += 2;
    }
    if (isSymbolAt(k, "@") || (readPosition && isSymbolAt(k, "("))) {
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
    if (schema.isEmpty() && cteNames.contains(table)) {
      return;
    }
    references.add(new TableReference(schema, table, operation));
  }

  /** 直前の語から、INSERT・UPDATE・DELETEがDMLの文でない（トリガーのイベント・権限・行ロック等）か判定する */
  private boolean isNonDmlUse(int i) {
    final SqlToken previous = at(i - 1);
    if (previous == null) {
      return false;
    }
    return previous.isSymbol(",")
        || (previous.kind() == SqlTokenKind.WORD
            && NON_DML_PREDECESSORS.contains(AsciiCase.toUpper(previous.text())));
  }

  private boolean isStatementStart(int i) {
    final SqlToken previous = at(i - 1);
    return previous == null
        || previous.isSymbol(";")
        || previous.isSymbol(">>")
        || (previous.kind() == SqlTokenKind.WORD
            && STATEMENT_PREDECESSORS.contains(AsciiCase.toUpper(previous.text())));
  }

  private boolean isAfterDot(int i) {
    return isSymbolAt(i - 1, ".");
  }

  private boolean isSymbolAt(int index, String symbol) {
    final SqlToken token = at(index);
    return token != null && token.isSymbol(symbol);
  }

  private boolean isWordAt(int index, String keyword) {
    final SqlToken token = at(index);
    return token != null && token.isWord(keyword);
  }

  /** 範囲の外はnull */
  private SqlToken at(int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
  }

  private Frame frame() {
    return frames.peek();
  }

  private int depth() {
    return frames.size() - 1;
  }

  private void resetStatement() {
    frames.clear();
    frames.push(new Frame(""));
    cteNames.clear();
    mergeTarget = List.of();
    insertTarget = List.of();
    mergeDepth = -1;
    deleteDepth = -1;
    multiInsertDepth = -1;
    subqueryTargetOperation = null;
    subqueryTargetDepth = -1;
    fromIsNotTable = false;
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
