package com.dbxray.domain.service.writer.template;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PagedSectionTemplates のセクション生成テスト */
public class PagedSectionTemplatesTest {

  @Test
  @DisplayName("heading: 見出しと空行を出力する")
  void testHeading() {
    final String nl = System.lineSeparator();
    assertEquals("## 外部キー一覧" + nl + nl, PagedSectionTemplates.heading("外部キー一覧"));
  }

  @Test
  @DisplayName("pagedSectionLinks: 見出しと分割ページへのリンクのみを出力する")
  void testPagedSectionLinks() {
    String section =
        PagedSectionTemplates.pagedSectionLinks(
            "外部キー一覧",
            List.of("./erDiagram_TEST_DB_public_1.md", "./erDiagram_TEST_DB_public_2.md"));
    assertTrue(section.contains("## 外部キー一覧"));
    assertTrue(section.contains("* [外部キー一覧_1](./erDiagram_TEST_DB_public_1.md)"));
    assertTrue(section.contains("* [外部キー一覧_2](./erDiagram_TEST_DB_public_2.md)"));
    // テーブル一覧の分割時と同様、件数の説明文は出力しない
    assertFalse(section.contains("分割して掲載"));
  }

  @Test
  @DisplayName("pageFooter: 先頭ページは前へを出力しない")
  void testPageFooterFirstPage() {
    String footer = PagedSectionTemplates.pageFooter(null, "./p2.md", "./back.md", "ER図へ");
    assertTrue(footer.startsWith("___"));
    assertFalse(footer.contains("前へ"));
    assertTrue(footer.contains("[次へ>>](./p2.md)"));
    assertTrue(footer.contains("[ER図へ](./back.md)"));
  }

  @Test
  @DisplayName("pageFooter: 最終ページは次へを出力しない")
  void testPageFooterLastPage() {
    String footer = PagedSectionTemplates.pageFooter("./p1.md", null, "./back.md", "ER図へ");
    assertTrue(footer.contains("[<<前へ](./p1.md)"));
    assertFalse(footer.contains("次へ"));
    assertTrue(footer.contains("[ER図へ](./back.md)"));
  }

  @Test
  @DisplayName("pageFooter: 中間ページは前へ・次への両方を出力する")
  void testPageFooterMiddlePage() {
    String footer = PagedSectionTemplates.pageFooter("./p1.md", "./p3.md", "./back.md", "トリガー一覧へ");
    assertTrue(footer.contains("[<<前へ](./p1.md)"));
    assertTrue(footer.contains("[次へ>>](./p3.md)"));
    assertTrue(footer.contains("[トリガー一覧へ](./back.md)"));
  }
}
