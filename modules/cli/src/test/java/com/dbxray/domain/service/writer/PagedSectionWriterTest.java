package com.dbxray.domain.service.writer;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.writer.PagedSectionWriter.PageLayout;
import com.dbxray.domain.service.writer.PagedSectionWriter.PagedSection;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PagedSectionWriter のページ分割可否・境界値に関するテスト<br>
 * 分割判定の上限（MAX_PAGE_SIZE = 3000）はクラス内部のprivate定数のため、このテストでは実測されたその値をリテラルとして直接使用する
 */
public class PagedSectionWriterTest {

  private static final int MAX_PAGE_SIZE = 3000;

  /** 書き込み内容をメモリ上に収集するFileRepositoryのスタブ */
  private static class InMemoryFileRepository implements FileRepository {
    private final Map<Path, String> files = new LinkedHashMap<>();

    @Override
    public void writeFile(Path filePath, List<String> contents) {
      files.put(filePath, String.join("", contents));
    }

    @Override
    public void appendFile(Path filePath, List<String> contents) {
      files.merge(filePath, String.join("", contents), String::concat);
    }

    @Override
    public void createDirectory(Path filePath) {
      // 何もしない
    }

    @Override
    public boolean exists(Path path) {
      return false;
    }

    @Override
    public boolean isDirectory(Path path) {
      return false;
    }

    @Override
    public List<Path> listFiles(Path directory) {
      return List.of();
    }

    @Override
    public List<String> readFile(Path filePath) {
      return List.of();
    }

    @Override
    public Path createTempDirectory(String prefix) {
      return Path.of(prefix);
    }

    @Override
    public void deleteDirectory(Path directory) {
      // 何もしない
    }

    @Override
    public void deleteFile(Path filePath) {
      // 何もしない
    }
  }

  private InMemoryFileRepository fileRepository;
  private PagedSectionWriter writer;

  private void setUp() {
    fileRepository = new InMemoryFileRepository();
    writer = new PagedSectionWriter(fileRepository, new DefaultOutputPathResolver());
  }

  private PagedSection<Integer> section(int rowCount) {
    List<Integer> rows = IntStream.rangeClosed(1, rowCount).boxed().toList();
    return new PagedSection<>(
        "見出し",
        "| No. | 値 |" + System.lineSeparator(),
        rows,
        (no, row) -> "|" + no + "|" + row + "|" + System.lineSeparator());
  }

  private PageLayout layout() {
    // 分割ページは本体ページ（page.md）と同じディレクトリに page_{ページ番号}.md として置かれる
    return new PageLayout("HEADER" + System.lineSeparator(), Path.of("page.md"), "戻る");
  }

  @Test
  @DisplayName("行数が0件の場合は空文字を返し、ファイルは書き込まれない")
  void testEmptyRowsReturnsEmptyString() {
    setUp();
    String result = writer.writePagedSection(section(0), layout());
    assertEquals("", result);
    assertTrue(fileRepository.files.isEmpty());
  }

  @Test
  @DisplayName("上限ちょうどの場合は分割せず本体に直接埋め込む")
  void testExactlyAtLimitEmbedsDirectly() {
    setUp();
    String result = writer.writePagedSection(section(MAX_PAGE_SIZE), layout());

    assertTrue(fileRepository.files.isEmpty(), "上限以下では別ファイルへ分割しない");
    assertTrue(result.contains("## 見出し"));
    assertTrue(result.contains("|1|1|"));
    assertTrue(result.contains("|" + MAX_PAGE_SIZE + "|" + MAX_PAGE_SIZE + "|"));
  }

  @Test
  @DisplayName("上限を1件超えた場合は2ページに分割する")
  void testOneOverLimitSplitsIntoTwoPages() {
    setUp();
    String result = writer.writePagedSection(section(MAX_PAGE_SIZE + 1), layout());

    assertEquals(2, fileRepository.files.size());
    assertTrue(fileRepository.files.containsKey(Path.of("page_1.md")));
    assertTrue(fileRepository.files.containsKey(Path.of("page_2.md")));
    // 本体ページにはリンクのみが返る
    assertTrue(result.contains("./page_1.md"));
    assertTrue(result.contains("./page_2.md"));
  }

  @Test
  @DisplayName("上限のちょうど倍数の場合、余りページは生成されない")
  void testExactMultipleOfLimitProducesNoRemainderPage() {
    setUp();
    writer.writePagedSection(section(MAX_PAGE_SIZE * 2), layout());

    assertEquals(2, fileRepository.files.size());
    assertFalse(fileRepository.files.containsKey(Path.of("page_3.md")), "6000件ちょうどで3ページ目ができてはいけない");
  }

  @Test
  @DisplayName("各ページには対応する範囲の行のみが含まれ、通し番号はページを跨いでも連番になる")
  void testEachPageContainsCorrectRowRange() {
    setUp();
    writer.writePagedSection(section(MAX_PAGE_SIZE + 1), layout());

    String page1 = fileRepository.files.get(Path.of("page_1.md"));
    String page2 = fileRepository.files.get(Path.of("page_2.md"));

    assertTrue(page1.contains("|1|1|"));
    assertTrue(page1.contains("|" + MAX_PAGE_SIZE + "|" + MAX_PAGE_SIZE + "|"));
    assertFalse(page1.contains("|" + (MAX_PAGE_SIZE + 1) + "|" + (MAX_PAGE_SIZE + 1) + "|"));

    // 2ページ目の行番号はページ内で1から振り直されず、全体を通した連番になる
    assertTrue(page2.contains("|" + (MAX_PAGE_SIZE + 1) + "|" + (MAX_PAGE_SIZE + 1) + "|"));
    assertFalse(page2.contains("|1|1|"));
  }

  @Test
  @DisplayName("先頭ページは前へリンクを持たず、次へリンクのみ持つ")
  void testFirstPageHasOnlyNextLink() {
    setUp();
    writer.writePagedSection(section(MAX_PAGE_SIZE + 1), layout());

    String page1 = fileRepository.files.get(Path.of("page_1.md"));
    assertFalse(page1.contains("前へ"));
    assertTrue(page1.contains("[次へ>>](./page_2.md)"));
    assertTrue(page1.contains("[戻る](./page.md)"));
  }

  @Test
  @DisplayName("最終ページは次へリンクを持たず、前へリンクのみ持つ")
  void testLastPageHasOnlyPrevLink() {
    setUp();
    writer.writePagedSection(section(MAX_PAGE_SIZE + 1), layout());

    String page2 = fileRepository.files.get(Path.of("page_2.md"));
    assertTrue(page2.contains("[<<前へ](./page_1.md)"));
    assertFalse(page2.contains("次へ"));
    assertTrue(page2.contains("[戻る](./page.md)"));
  }

  @Test
  @DisplayName("中間ページは前へ・次への両方のリンクを持つ")
  void testMiddlePageHasBothLinks() {
    setUp();
    // 3000*3+1 = 4ページ構成にする（1,2ページ目満杯、3ページ目満杯、4ページ目1件）
    writer.writePagedSection(section(MAX_PAGE_SIZE * 3 + 1), layout());

    assertEquals(4, fileRepository.files.size());
    String page2 = fileRepository.files.get(Path.of("page_2.md"));
    assertTrue(page2.contains("前へ"));
    assertTrue(page2.contains("次へ"));
  }

  @Test
  @DisplayName("各ページのファイルにはヘッダー・見出し・フッターが含まれる")
  void testPageContentIncludesHeaderAndFooter() {
    setUp();
    writer.writePagedSection(section(MAX_PAGE_SIZE + 1), layout());

    String page1 = fileRepository.files.get(Path.of("page_1.md"));
    assertTrue(page1.startsWith("HEADER"));
    assertTrue(page1.contains("## 見出し"));
  }
}
