package com.dbxray.domain.service.tableusage;

/**
 * ASCIIの英字だけを対象にした大文字小文字の比較・変換<br>
 * {@link String#equalsIgnoreCase}・{@link String#toUpperCase()}は、ASCII以外の文字も変換する（{@code ı}が{@code
 * I}になる等）ため、 キーワードの判定と識別子の畳み込み（PostgreSQL・OracleともASCIIの英字だけを畳み込む）には使わない
 */
final class AsciiCase {

  private AsciiCase() {}

  /** 文字列の一部の範囲が、ASCIIの英字の大文字小文字を区別せずに別の文字列と等しいか（部分文字列を作らずに比べる） */
  static boolean regionEqualsIgnoreCase(String source, int start, int end, String other) {
    if (end - start != other.length()) {
      return false;
    }
    for (int i = 0; i < other.length(); i++) {
      if (toUpper(source.charAt(start + i)) != toUpper(other.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** ASCIIの英小文字だけを大文字にする */
  static String toUpper(String value) {
    return toUpper(value, 0, value.length());
  }

  /** 文字列の一部の範囲について、ASCIIの英小文字だけを大文字にした文字列を返す */
  static String toUpper(String value, int start, int end) {
    final char[] chars = new char[end - start];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = toUpper(value.charAt(start + i));
    }
    return new String(chars);
  }

  /** ASCIIの英大文字だけを小文字にする */
  static String toLower(String value) {
    final StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return sb.toString();
  }

  private static char toUpper(char c) {
    return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
  }
}
