package com.dbxray.domain.service.tableusage;

/**
 * ASCIIの英字だけを対象にした大文字小文字の比較・変換<br>
 * {@link String#equalsIgnoreCase}・{@link String#toUpperCase()}は、ASCII以外の文字も変換する（{@code ı}が{@code
 * I}になる等）ため、 キーワードの判定と識別子の畳み込み（PostgreSQL・OracleともASCIIの英字だけを畳み込む）には使わない
 */
final class AsciiCase {

  private AsciiCase() {}

  /** ASCIIの英字の大文字小文字を区別せずに比べる */
  static boolean equalsIgnoreCase(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      if (toUpper(a.charAt(i)) != toUpper(b.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** ASCIIの英小文字だけを大文字にする */
  static String toUpper(String value) {
    final StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      sb.append(toUpper(value.charAt(i)));
    }
    return sb.toString();
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
