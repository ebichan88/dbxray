package com.dbxray.mcp.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ColumnType}のテスト */
class ColumnTypeTest {

  @Test
  @DisplayName("parse: 括弧の外を型の名前、内を長さ・精度等の指定に分け、大文字小文字・全角半角・空白の違いを揃える")
  void splitsNameAndParameters() {
    assertEquals(
        new ColumnType("character varying", "50"), ColumnType.parse("character varying(50)"));
    assertEquals(new ColumnType("number", "10,2"), ColumnType.parse("ＮＵＭＢＥＲ( 10, 2 )"));
    assertEquals(new ColumnType("text", ""), ColumnType.parse("  TEXT "));
    assertEquals(
        new ColumnType("timestamp without time zone", "6"),
        ColumnType.parse("timestamp(6)  without time zone"));
  }

  @Test
  @DisplayName("parse: 括弧が複数ある表記は指定を区切って保持し、(2)(6)と(26)を区別する")
  void separatesParameterGroups() {
    assertEquals(
        new ColumnType("interval day to second", "2;6"),
        ColumnType.parse("INTERVAL DAY(2) TO SECOND(6)"));
  }

  @Test
  @DisplayName("parse: 括弧の対応が崩れた表記も例外にせず、開いたままの括弧の後ろを指定とみなし、対応の無い閉じ括弧は無視する")
  void toleratesUnbalancedParentheses() {
    assertEquals(new ColumnType("foo", "10"), ColumnType.parse("foo(10"));
    assertEquals(new ColumnType("bar", ""), ColumnType.parse(")bar"));
  }
}
