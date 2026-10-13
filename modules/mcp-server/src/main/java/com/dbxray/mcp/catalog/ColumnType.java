package com.dbxray.mcp.catalog;

/**
 * カラムの型の表記を、型の名前と、括弧内の長さ・精度等の指定に分けたもの<br>
 * {@code timestamp(6) without time zone}のように指定が名前の途中に入る表記もあるため、括弧の外を名前、内を指定とする
 *
 * @param name 型の名前（NFKC正規化＋小文字化し、連続する空白を1つにしたもの）
 * @param parameters 括弧内の指定（空白を除いたもの）。指定が無い場合は空文字
 */
record ColumnType(String name, String parameters) {

  /** 括弧が複数ある表記（{@code interval day(2) to second(6)}等）で、指定を区切る文字。{@code (2)(6)}と{@code (26)}を区別する */
  private static final char GROUP_SEPARATOR = ';';

  /** 型の表記を分けるメソッド。括弧の対応が崩れた表記も例外にせず、開いたままの括弧の後ろは指定とみなす */
  static ColumnType parse(String type) {
    final String normalized = TextValues.normalize(type);
    final StringBuilder name = new StringBuilder();
    final StringBuilder parameters = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < normalized.length(); i++) {
      final char c = normalized.charAt(i);
      if (c == '(') {
        if (depth == 0 && !parameters.isEmpty()) {
          parameters.append(GROUP_SEPARATOR);
        }
        depth++;
      } else if (c == ')') {
        depth = Math.max(depth - 1, 0);
      } else if (depth > 0) {
        if (!Character.isWhitespace(c)) {
          parameters.append(c);
        }
      } else {
        appendCollapsingWhitespace(name, c);
      }
    }
    return new ColumnType(name.toString().strip(), parameters.toString());
  }

  private static void appendCollapsingWhitespace(StringBuilder text, char c) {
    if (!Character.isWhitespace(c)) {
      text.append(c);
    } else if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
      text.append(' ');
    }
  }
}
