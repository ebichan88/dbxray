package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Oracleのパッケージ本体から、最上位で定義されたサブプログラムの範囲を求めるクラス<br>
 * {@code BEGIN}・{@code CASE}と{@code END}の対応をスタック（ヒープ上）で数える。{@code IF}・{@code LOOP}は{@code END
 * IF}・{@code END LOOP}で閉じるため数えない。{@code CASE}は文と式を区別する（式は{@code END}だけで閉じ、直後に{@code LOOP}・{@code
 * IF}が続いても{@code END LOOP}・{@code END IF}ではない）。宣言部の中の入れ子のサブプログラムは、外側のサブプログラムの範囲に含める。
 * 条件付きコンパイル（{@code $IF … $ELSE … $END}）は、各分岐を{@code $IF}の時点の状態から読み、{@code
 * $END}の後は最初の分岐を読み終えた状態を続ける（分岐ごとに{@code BEGIN}を書き分けても対応がずれないようにするため）。
 * スタックは要素を共有する連結リストで持ち、分岐の時点の状態を複製せずに覚える
 */
final class PackageSubprograms {

  /** 直後から宣言（サブプログラムの見出し）が始まる語。{@code WITH}はSQLの中のインラインのPL/SQL関数 */
  private static final Set<String> DECLARATION_PREDECESSORS = Set.of("IS", "AS", "DECLARE", "WITH");

  /** 直後から文が始まる語（ここに現れる{@code CASE}は{@code CASE}文） */
  private static final Set<String> STATEMENT_PREDECESSORS =
      Set.of("BEGIN", "THEN", "ELSE", "LOOP", "EXCEPTION");

  private final SqlTokens tokens;
  private final List<Subprogram> found = new ArrayList<>();

  /** {@code END}で閉じるまとまりのスタック（先頭が最も内側。空はnull） */
  private Node blocks;

  private int parenDepth;

  /** 宣言中のサブプログラムの見出し（{@code IS}・{@code AS}を読むまで）。無ければnull */
  private Header header;

  /** 読んでいる条件付きコンパイルのまとまり（先頭が最も内側） */
  private final Deque<Conditional> conditionals = new ArrayDeque<>();

  private PackageSubprograms(List<SqlToken> tokens) {
    this.tokens = new SqlTokens(tokens);
  }

  /**
   * パッケージ本体の最上位のサブプログラム1つ
   *
   * @param name カタログでの名前
   * @param parameterNames 宣言の引数名（カタログでの名前。宣言順）
   * @param language 呼び出し仕様（{@code IS LANGUAGE JAVA …}・{@code IS EXTERNAL …}）の言語。PL/SQLで書かれていれば空文字
   * @param bodyStart 本体の開始位置（宣言の{@code IS}・{@code AS}の次の字句の位置）
   * @param bodyEnd 本体の終了位置（本体を閉じる{@code END}・呼び出し仕様を閉じる{@code ;}の字句の位置。含まない）
   */
  record Subprogram(
      String name, List<String> parameterNames, String language, int bodyStart, int bodyEnd) {

    /** リストは変更不可な複製として保持する */
    Subprogram {
      parameterNames = List.copyOf(parameterNames);
    }
  }

  /**
   * パッケージ本体の最上位のサブプログラムを求めるメソッド
   *
   * @param tokens 定義の字句（空白・コメントを除いたもの）
   * @param from パッケージ本体の宣言の{@code IS}・{@code AS}の次の位置
   * @return 最上位のサブプログラム（現れた順）。{@code END}で閉じていないものは含まない
   */
  static List<Subprogram> find(List<SqlToken> tokens, int from) {
    final PackageSubprograms finder = new PackageSubprograms(tokens);
    int i = from;
    while (i < tokens.size()) {
      i = finder.visit(i);
    }
    return finder.found;
  }

  /**
   * 字句1つ分の状態の変化
   *
   * @return 次に読む字句の位置（{@code END IF}等の後ろの語を読み飛ばす場合は{@code i + 2}）
   */
  private int visit(int i) {
    final SqlToken token = tokens.at(i);
    if (token.kind() == SqlTokenKind.DIRECTIVE) {
      visitDirective(token);
    } else if (token.isSymbol("(")) {
      parenDepth++;
      if (header != null && header.parameterListDepth < 0 && i == header.nameIndex + 1) {
        header.parameterListDepth = parenDepth;
      }
    } else if (token.isSymbol(")")) {
      if (header != null && parenDepth == header.parameterListDepth) {
        header.parameterListClosed = true;
      }
      parenDepth = Math.max(0, parenDepth - 1);
    } else if (token.isSymbol(";")) {
      // 括弧は文をまたがないため、数え違いを次の文へ持ち越さない
      parenDepth = 0;
      if (header != null && !header.language.isEmpty() && blocks == null) {
        found.add(header.toSubprogram(i));
      }
      header = null;
    } else if (header != null) {
      visitHeader(i, token);
    } else if (token.kind() == SqlTokenKind.WORD && !tokens.isAfterDot(i)) {
      return visitKeyword(i, token);
    }
    return i + 1;
  }

  /** 見出しの中の字句（引数名と、見出しを閉じる{@code IS}・{@code AS}） */
  private void visitHeader(int i, SqlToken token) {
    if (!header.language.isEmpty()) {
      return;
    }
    if (parenDepth == header.parameterListDepth
        && !header.parameterListClosed
        && token.isName()
        && isParameterStart(i)) {
      header.parameterNames.add(SqlNames.identifier(token, Dbms.ORACLE));
    } else if (parenDepth == header.parenDepth && (token.isWord("IS") || token.isWord("AS"))) {
      header.bodyStart = i + 1;
      header.language = callSpecLanguage(tokens, i + 1);
      if (header.language.isEmpty()) {
        push(new Block(BlockKind.SUBPROGRAM, header, true));
        header = null;
      }
    }
  }

  /**
   * キーワード1つ分の状態の変化
   *
   * @return 次に読む字句の位置
   */
  private int visitKeyword(int i, SqlToken token) {
    final SqlToken next = tokens.at(i + 1);
    switch (AsciiCase.toUpper(token.text())) {
      case "FUNCTION", "PROCEDURE" -> {
        if (isDeclarationStart(i)
            && next != null
            && next.isName()
            && !next.isWord("IS")
            && !next.isWord("AS")) {
          header = new Header(SqlNames.identifier(next, Dbms.ORACLE), i + 1, parenDepth);
        }
      }
      case "DECLARE" -> push(new Block(BlockKind.BLOCK, null, true));
      case "BEGIN" -> {
        if (blocks != null && blocks.block.awaitingBegin) {
          final Block started = blocks.block;
          blocks = blocks.next;
          push(new Block(started.kind, started.header, false));
        } else {
          push(new Block(BlockKind.BLOCK, null, false));
        }
      }
      case "CASE" ->
          push(
              new Block(
                  isCaseStatement(i) ? BlockKind.CASE_STATEMENT : BlockKind.CASE_EXPRESSION,
                  null,
                  false));
      case "END" -> {
        return visitEnd(i, next);
      }
      default -> {}
    }
    return i + 1;
  }

  /**
   * {@code END}の処理。{@code CASE}式は{@code END}だけで閉じる。それ以外は、{@code END IF}・{@code END
   * LOOP}ならまとまりを閉じず、{@code END CASE}・{@code END}・{@code END ラベル}なら閉じる
   *
   * @return 次に読む字句の位置（{@code END IF}・{@code END LOOP}・{@code END CASE}は後ろの語を読み飛ばす）
   */
  private int visitEnd(int i, SqlToken next) {
    final boolean closesCaseExpression =
        blocks != null && blocks.block.kind == BlockKind.CASE_EXPRESSION;
    if (!closesCaseExpression && next != null && (next.isWord("IF") || next.isWord("LOOP"))) {
      return i + 2;
    }
    if (blocks != null) {
      final Block closed = blocks.block;
      blocks = blocks.next;
      if (closed.kind == BlockKind.SUBPROGRAM && blocks == null) {
        found.add(closed.header.toSubprogram(i));
      }
    }
    return !closesCaseExpression && next != null && next.isWord("CASE") ? i + 2 : i + 1;
  }

  /** 条件付きコンパイルの選択指令（{@code $IF}・{@code $ELSIF}・{@code $ELSE}・{@code $END}・{@code $ERROR}） */
  private void visitDirective(SqlToken token) {
    switch (AsciiCase.toUpper(token.text())) {
      case "$IF", "$ERROR" -> conditionals.push(new Conditional(blocks, parenDepth));
      case "$ELSIF", "$ELSE" -> {
        final Conditional conditional = conditionals.peek();
        if (conditional != null) {
          if (!conditional.firstBranchDone) {
            conditional.firstBranchDone = true;
            conditional.firstBranchBlocks = blocks;
            conditional.firstBranchParenDepth = parenDepth;
          }
          blocks = conditional.startBlocks;
          parenDepth = conditional.startParenDepth;
        }
      }
      case "$END" -> {
        final Conditional conditional = conditionals.poll();
        if (conditional != null && conditional.firstBranchDone) {
          blocks = conditional.firstBranchBlocks;
          parenDepth = conditional.firstBranchParenDepth;
        }
      }
      default -> {}
    }
  }

  /** サブプログラムの見出しを置ける位置（宣言部の先頭・前の宣言の後ろ）か */
  private boolean isDeclarationStart(int i) {
    final SqlToken previous = tokens.at(i - 1);
    return previous == null
        || previous.isSymbol(";")
        || previous.kind() == SqlTokenKind.DIRECTIVE
        || previous.isAnyWord(DECLARATION_PREDECESSORS);
  }

  /** 文の始まりに現れた{@code CASE}は{@code CASE}文。{@code CASE}式の中の{@code CASE}は常に式 */
  private boolean isCaseStatement(int i) {
    if (parenDepth > 0 || (blocks != null && blocks.block.kind == BlockKind.CASE_EXPRESSION)) {
      return false;
    }
    final SqlToken previous = tokens.at(i - 1);
    return previous == null
        || previous.isSymbol(";")
        || previous.isSymbol(">>")
        || previous.kind() == SqlTokenKind.DIRECTIVE
        || previous.isAnyWord(STATEMENT_PREDECESSORS);
  }

  /** 引数の並びの括弧の中で、{@code (}か{@code ,}の直後の名前は引数名 */
  private boolean isParameterStart(int i) {
    return tokens.isSymbolAt(i - 1, "(") || tokens.isSymbolAt(i - 1, ",");
  }

  private void push(Block block) {
    blocks = new Node(block, blocks);
  }

  /**
   * 宣言の{@code IS}・{@code AS}の直後から、呼び出し仕様の言語を求めるメソッド
   *
   * @return {@code LANGUAGE JAVA}は{@code JAVA}、{@code EXTERNAL}は{@code C}。呼び出し仕様でなければ空文字
   */
  static String callSpecLanguage(SqlTokens tokens, int i) {
    if (tokens.isWordAt(i, "EXTERNAL")) {
      return "C";
    }
    if (!tokens.isWordAt(i, "LANGUAGE")) {
      return "";
    }
    return tokens.isNameAt(i + 1) ? SqlNames.identifier(tokens.at(i + 1), Dbms.ORACLE) : "LANGUAGE";
  }

  /** 宣言中のサブプログラムの見出し（{@code FUNCTION 名前 (引数…) … IS}の{@code IS}まで） */
  private static final class Header {

    private final String name;

    /** 名前の字句の位置（直後の括弧だけを引数の並びとするため） */
    private final int nameIndex;

    private final int parenDepth;
    private final List<String> parameterNames = new ArrayList<>();

    /** 引数の並びの括弧の深さ。引数の並びが無い（まだ読んでいない）場合は-1 */
    private int parameterListDepth = -1;

    /** 引数の並びを閉じたか（以降の{@code RELIES_ON (t)}等の括弧の中の名前を引数名にしない） */
    private boolean parameterListClosed;

    private String language = "";
    private int bodyStart;

    private Header(String name, int nameIndex, int parenDepth) {
      this.name = name;
      this.nameIndex = nameIndex;
      this.parenDepth = parenDepth;
    }

    private Subprogram toSubprogram(int bodyEnd) {
      return new Subprogram(name, parameterNames, language, bodyStart, bodyEnd);
    }
  }

  /** まとまりの種類 */
  private enum BlockKind {
    SUBPROGRAM,
    /** {@code DECLARE}・{@code BEGIN}のブロック */
    BLOCK,
    CASE_STATEMENT,
    CASE_EXPRESSION
  }

  /**
   * {@code END}で閉じるまとまり1つ（分岐の時点の状態を共有するため、変更せずに置き換える）
   *
   * @param header サブプログラムなら見出し。それ以外はnull
   * @param awaitingBegin 宣言部にいて、自身の{@code BEGIN}をまだ読んでいないか
   */
  private record Block(BlockKind kind, Header header, boolean awaitingBegin) {}

  /** スタックの要素（後ろの要素を共有する連結リスト） */
  private record Node(Block block, Node next) {}

  /** 条件付きコンパイルのまとまり1つ */
  private static final class Conditional {

    /** {@code $IF}の時点の状態（各分岐の読み始めの状態） */
    private final Node startBlocks;

    private final int startParenDepth;

    /** 最初の分岐を読み終えたか */
    private boolean firstBranchDone;

    /** 最初の分岐を読み終えた時点の状態（{@code $END}の後に続ける状態） */
    private Node firstBranchBlocks;

    private int firstBranchParenDepth;

    private Conditional(Node startBlocks, int startParenDepth) {
      this.startBlocks = startBlocks;
      this.startParenDepth = startParenDepth;
    }
  }
}
