package com.dbxray.domain.service.path;

import java.util.Locale;

/**
 * DBから取得した名前（DB名・スキーマ名・テーブル名・関数名等）を、出力先のパスの1要素として安全な形にするクラス<br>
 * 引用符で囲んだ識別子は{@code /}・{@code ..}等を含められるため、そのままパスにつなぐと出力先の外へ書き込めてしまう。
 * パスの区切り・Windowsでファイル名に使えない文字・制御文字は、{@code ~}とASCIIの文字コードの16進2桁に置き換える（例: {@code /}は
 * {@code ~2F}、{@code .}は{@code ~2E}）。置き換える文字はすべてASCIIのため2桁で足りる。
 * URLのパーセントエンコーディングと同じ形だが{@code %}を使わないのは、Markdownのリンクで{@code %2F}が{@code /}と解釈され、リンクがファイルにたどり着かなくなるため。
 * 名前全体が{@code .}だけからなる場合（{@code .}・{@code ..}・{@code ...}等）と空の場合も置き換える。
 * エスケープに使う{@code ~}自体も置き換えるため、異なる名前が同じ要素になることはない。
 * 通常の名前（英数字・日本語・{@code _}・{@code -}等）は変わらない
 */
public final class PathSegments {

  private static final char ESCAPE = '~';

  /** パスの区切り（{@code /}・{@code \}）と、Windowsでファイル名に使えない文字（{@code :}はドライブ指定にもなる） */
  private static final String UNSAFE_CHARACTERS = "/\\:*?\"<>|" + ESCAPE;

  /** 空の名前を表す要素（{@code ~}自体は{@code ~7E}になるため、名前の置き換え結果と重ならない） */
  private static final String EMPTY_NAME = String.valueOf(ESCAPE);

  private PathSegments() {}

  /**
   * 名前をパスの1要素にするメソッド
   *
   * @param name DBから取得した名前（nullは空として扱う）
   * @return パスの区切りを含まず、{@code .}・{@code ..}・空にならない文字列
   */
  public static String encode(String name) {
    if (name == null || name.isEmpty()) {
      return EMPTY_NAME;
    }
    // ...のように3個以上でも置き換える。Windowsはパスの要素の末尾の.を取り除くため、.・..と区別されないおそれがある
    final boolean onlyDots = name.chars().allMatch(c -> c == '.');
    final StringBuilder sb = new StringBuilder(name.length());
    name.chars()
        .forEach(
            c -> {
              if (onlyDots || isUnsafe(c)) {
                sb.append(ESCAPE).append(String.format(Locale.ROOT, "%02X", c));
              } else {
                sb.append((char) c);
              }
            });
    return sb.toString();
  }

  private static boolean isUnsafe(int c) {
    return c < 0x20 || c == 0x7F || UNSAFE_CHARACTERS.indexOf(c) >= 0;
  }
}
