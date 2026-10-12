package com.dbxray.domain.service.tableusage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 字句の列と、位置を指定した判定・名前の読み取りをまとめたクラス<br>
 * 範囲の外の位置を指定してもエラーにせず、判定は偽、字句の取得はnullにする（前後を覗く処理で位置の検査を繰り返さないため）
 */
final class SqlTokens {

  /** スキーマ・テーブルの修飾を含めて名前の部品として読む上限（{@code カタログ.スキーマ.テーブル}） */
  private static final int MAX_NAME_PARTS = 3;

  /** 名前の部品どうしをつなぐ{@code .}と、その次の名前の字句の数 */
  private static final int DOT_AND_NAME_TOKENS = 2;

  private final List<SqlToken> tokens;

  SqlTokens(List<SqlToken> tokens) {
    this.tokens = tokens;
  }

  /** 字句の数 */
  int size() {
    return tokens.size();
  }

  /** 位置の字句。範囲の外はnull */
  SqlToken at(int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
  }

  /** 位置の字句が、指定の演算子・区切り記号か */
  boolean isSymbolAt(int index, String symbol) {
    final SqlToken token = at(index);
    return token != null && token.isSymbol(symbol);
  }

  /** 位置の字句が、大文字小文字を区別しない引用符の無い単語として、キーワードと一致するか */
  boolean isWordAt(int index, String keyword) {
    final SqlToken token = at(index);
    return token != null && token.isWord(keyword);
  }

  /**
   * 位置の字句が、引用符の無い単語として、キーワードの集合のいずれかと一致するか
   *
   * @param upperCaseKeywords 大文字で書いたキーワード
   */
  boolean isAnyWordAt(int index, Set<String> upperCaseKeywords) {
    final SqlToken token = at(index);
    return token != null && token.isAnyWord(upperCaseKeywords);
  }

  /** 位置の字句が、名前の部品（引用符の無い単語か、引用符付きの識別子）か */
  boolean isNameAt(int index) {
    final SqlToken token = at(index);
    return token != null && token.isName();
  }

  /** 直前が{@code .}か（{@code e.function}の{@code function}のように、修飾された名前の部品か） */
  boolean isAfterDot(int index) {
    return isSymbolAt(index - 1, ".");
  }

  /**
   * {@code 名前}・{@code スキーマ.名前}の終わりを求めるメソッド
   *
   * @return 名前の次の位置。位置に名前が無ければ、位置そのもの
   */
  int endOfQualifiedName(int index) {
    if (!isNameAt(index)) {
      return index;
    }
    int end = index + 1;
    while (isSymbolAt(end, ".") && isNameAt(end + 1)) {
      end += DOT_AND_NAME_TOKENS;
    }
    return end;
  }

  /**
   * 位置から名前の部品（{@code スキーマ.テーブル}等。最大3つ）を読むメソッド
   *
   * @return 名前の部品。位置に名前が無ければ空
   */
  List<SqlToken> qualifiedNameAt(int index) {
    if (!isNameAt(index)) {
      return List.of();
    }
    final List<SqlToken> parts = new ArrayList<>(List.of(at(index)));
    int next = index + 1;
    while (parts.size() < MAX_NAME_PARTS && isSymbolAt(next, ".") && isNameAt(next + 1)) {
      parts.add(at(next + 1));
      next += DOT_AND_NAME_TOKENS;
    }
    return parts;
  }
}
