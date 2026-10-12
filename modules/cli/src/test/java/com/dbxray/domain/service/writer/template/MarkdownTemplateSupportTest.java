package com.dbxray.domain.service.writer.template;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MarkdownTemplateSupport の表セルエスケープに関するテスト */
public class MarkdownTemplateSupportTest {

  @Test
  @DisplayName("escapeTableCell: null・空文字は空文字を返す")
  void testEscapeEmpty() {
    assertEquals("", MarkdownTemplateSupport.escapeTableCell(null));
    assertEquals("", MarkdownTemplateSupport.escapeTableCell(""));
  }

  @Test
  @DisplayName("escapeTableCell: パイプはエスケープされる")
  void testEscapePipe() {
    assertEquals("a\\|b\\|c", MarkdownTemplateSupport.escapeTableCell("a|b|c"));
  }

  @Test
  @DisplayName("escapeTableCell: 改行（CRLF/LF/CR）は<br>へ置換される")
  void testEscapeNewlines() {
    assertEquals("a<br>b<br>c<br>d", MarkdownTemplateSupport.escapeTableCell("a\r\nb\nc\rd"));
  }

  @Test
  @DisplayName("escapeTableCell: 通常の文字列はそのまま返す")
  void testEscapePlain() {
    assertEquals("個人情報を含む", MarkdownTemplateSupport.escapeTableCell("個人情報を含む"));
  }

  @Test
  @DisplayName("escapePipe: null・空文字は空文字を返す")
  void testEscapePipeEmpty() {
    assertEquals("", MarkdownTemplateSupport.escapePipe(null));
    assertEquals("", MarkdownTemplateSupport.escapePipe(""));
  }

  @Test
  @DisplayName("escapePipe: パイプのみエスケープし、改行はそのまま残す")
  void testEscapePipeOnly() {
    assertEquals(
        "CHECK (((a \\|\\| b) <> ''::text))",
        MarkdownTemplateSupport.escapePipe("CHECK (((a || b) <> ''::text))"));
    assertEquals("a\nb", MarkdownTemplateSupport.escapePipe("a\nb"));
  }

  @Test
  @DisplayName("marker: 真の場合は○、偽の場合は空文字を返す")
  void testMarker() {
    assertEquals("○", MarkdownTemplateSupport.marker(true));
    assertEquals("", MarkdownTemplateSupport.marker(false));
  }

  @Test
  @DisplayName("escapeHtml: <を文字参照にし、表のセルの改行の<br>だけは残す")
  void testEscapeHtml() {
    assertEquals(
        "&lt;img src=x onerror=alert(1)>a<br>b &lt;!-- c --> x &lt; 1",
        MarkdownTemplateSupport.escapeHtml("<img src=x onerror=alert(1)>a<br>b <!-- c --> x < 1"));
    assertEquals("", MarkdownTemplateSupport.escapeHtml(null));
  }

  @Test
  @DisplayName("escapeInline: 改行を空白にし、HTMLをエスケープする")
  void testEscapeInline() {
    assertEquals("a ``` &lt;b>", MarkdownTemplateSupport.escapeInline("a\n```\r\n<b>"));
  }

  @Test
  @DisplayName("row: セルの改行を<br>にし、HTMLをエスケープする")
  void testRowEscapesCells() {
    assertEquals("|1|a<br>```|&lt;b>|", MarkdownTemplateSupport.row(1, "a\n```", "<b>"));
  }

  @Test
  @DisplayName("row: \\で終わるセルは、次の区切りの|をエスケープしないよう後に空白を置く（途中の\\はそのまま）")
  void testRowSeparatesTrailingBackslashFromDelimiter() {
    // 円記号の代わりに\を書いたコメントや、Windowsのパス
    assertEquals(
        "|1|格納先 C:\\data\\ |path|",
        MarkdownTemplateSupport.row(
            1, MarkdownTemplateSupport.escapeTableCell("格納先 C:\\data\\"), "path"));
    assertEquals("|単価（\\\\ |price|", MarkdownTemplateSupport.row("単価（\\\\", "price"));
    assertEquals("|a|b\\ |", MarkdownTemplateSupport.row("a", "b\\"));
    assertEquals("|C:\\data|path|", MarkdownTemplateSupport.row("C:\\data", "path"));
  }

  @Test
  @DisplayName("codeFence: 中身の最長のバッククォートの並びより長い囲みにする（最短3個）")
  void testCodeFence() {
    assertEquals("```", MarkdownTemplateSupport.codeFence("select 1"));
    assertEquals("```", MarkdownTemplateSupport.codeFence("a `b` ``c``"));
    assertEquals("````", MarkdownTemplateSupport.codeFence("a\n```\nb"));
    assertEquals("``````", MarkdownTemplateSupport.codeFence("`````"));
    assertEquals("```", MarkdownTemplateSupport.codeFence(null));
  }

  @Test
  @DisplayName("codeSpan: 中身のバッククォートより長い区切りで囲み、改行は空白にする")
  void testCodeSpan() {
    assertEquals("`range (a)`", MarkdownTemplateSupport.codeSpan("range (a)"));
    assertEquals("``a`b``", MarkdownTemplateSupport.codeSpan("a`b"));
    assertEquals("`` `a ``", MarkdownTemplateSupport.codeSpan("`a"));
    assertEquals("```` a ``` ````", MarkdownTemplateSupport.codeSpan("a\n```"));
  }
}
