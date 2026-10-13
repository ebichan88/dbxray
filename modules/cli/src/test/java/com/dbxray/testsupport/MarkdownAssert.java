package com.dbxray.testsupport;

import static org.junit.jupiter.api.Assertions.*;

public final class MarkdownAssert {

  private MarkdownAssert() {}

  /** 改行コードをプラットフォーム依存→LF 正規化し、文字列全体を厳密比較。差分があった場合は可視化しやすいように期待値と実際値をそのまま出力。 */
  public static void assertMarkdownEquals(String expected, String actual) {
    String normExpected = normalize(expected);
    String normActual = normalize(actual);
    if (!normExpected.equals(normActual)) {
      System.out.println("=== EXPECTED (normalized) ===");
      System.out.println(normExpected.replace("\n", "\\n\n"));
      System.out.println("=== ACTUAL (normalized) ===");
      System.out.println(normActual.replace("\n", "\\n\n"));
    }
    assertEquals(normExpected, normActual, "Markdown content mismatch.");
  }

  private static String normalize(String s) {
    return s.replace("\r\n", "\n").replace("\r", "\n");
  }
}
