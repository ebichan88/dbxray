package com.dbxray.domain.service.writer;

import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

/**
 * 行数の多い表を複数ページに分割して出力する処理を共通化したクラス<br>
 * GitHub上で表として表示できる行数には上限があるため、テーブル一覧・ER図・オブジェクト一覧（トリガー/関数/シーケンス/型）など、行数が多くなり得る一覧系の
 * ドキュメントはすべてこのクラスを介して出力する。
 * 行数が上限以下の場合は本体ページに直接埋め込み、超える場合は別ファイルへ分割して
 * 本体ページにはリンクのみを掲載する。行の文字列生成はページ単位で行い、全行分を同時にメモリ保持しない
 */
public class PagedSectionWriter {

  private static final int MAX_PAGE_SIZE = 3000;
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;

  @Inject
  public PagedSectionWriter(FileRepository fileRepository, OutputPathResolver outputPathResolver) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
  }

  /**
   * ページ分割対象となる表のセクション
   *
   * @param lineMapper 行番号と要素から1行分の文字列を生成する関数
   */
  public record PagedSection<T>(
      String heading,
      String tableHeader,
      List<T> rows,
      BiFunction<Integer, T, String> lineMapper) {}

  /**
   * 表のセクションを掲載する本体ページと、分割ページの共通部分<br>
   * 分割ページのファイルパスは本体ページのファイルパスから{@link OutputPathResolver#resolvePageFile}で解決する
   *
   * @param fileHeader 本体ページ・分割ページのファイルヘッダー
   * @param file 本体ページのファイルパス
   * @param backLabel 分割ページから本体ページへのリンク表示名
   */
  public record PageLayout(String fileHeader, Path file, String backLabel) {}

  /**
   * 表のセクションを書き込むメソッド<br>
   * 行数がMarkdownの表に表示できる最大件数以下の場合は本体ページに直接埋め込み、超える場合は別ファイルへ分割して、本体ページにはリンクのみを掲載する。
   * 行の文字列生成はページ単位で行い、全行分を同時にメモリ保持しない
   *
   * @return 本体ページに掲載するセクション文字列
   */
  public <T> String writePagedSection(PagedSection<T> section, PageLayout layout) {
    final int total = section.rows().size();
    if (total == 0) {
      return "";
    }
    if (total <= MAX_PAGE_SIZE) {
      return PagedSectionTemplates.heading(section.heading())
          + section.tableHeader()
          + buildRows(section, 0, total)
          + System.lineSeparator();
    }
    final int totalPages = (total + MAX_PAGE_SIZE - 1) / MAX_PAGE_SIZE;
    for (int page = 1; page <= totalPages; page++) {
      final int from = (page - 1) * MAX_PAGE_SIZE;
      final int to = Math.min(from + MAX_PAGE_SIZE, total);
      final List<String> contents =
          List.of(
              layout.fileHeader(),
              PagedSectionTemplates.heading(section.heading()),
              section.tableHeader(),
              buildRows(section, from, to) + System.lineSeparator(),
              PagedSectionTemplates.pageFooter(
                  page > 1 ? pageHref(layout, page - 1) : null,
                  page < totalPages ? pageHref(layout, page + 1) : null,
                  siblingHref(layout.file()),
                  layout.backLabel()));
      fileRepository.writeFile(outputPathResolver.resolvePageFile(layout.file(), page), contents);
    }
    return PagedSectionTemplates.pagedSectionLinks(
        section.heading(),
        section.heading(),
        IntStream.rangeClosed(1, totalPages).mapToObj(page -> pageHref(layout, page)).toList());
  }

  /**
   * 分割ページへの相対リンクを取得するメソッド
   *
   * @param layout 本体ページと分割ページの共通部分
   * @param page ページ番号（1始まり）
   * @return 分割ページへの相対リンク
   */
  private String pageHref(PageLayout layout, int page) {
    return siblingHref(outputPathResolver.resolvePageFile(layout.file(), page));
  }

  /**
   * 本体ページ・分割ページ同士の相対リンクを取得するメソッド<br>
   * 分割ページは本体ページと同じディレクトリに置かれるため、ファイル名のみで参照できる
   *
   * @return 相対リンク（例: {@code ./tableList_testdb_2.md}）
   */
  private static String siblingHref(Path file) {
    return "./" + file.getFileName();
  }

  /**
   * 表の行を指定範囲分だけ組み立てるメソッド
   *
   * @param from 開始インデックス（含む）
   * @param to 終了インデックス（含まない）
   */
  private <T> String buildRows(PagedSection<T> section, int from, int to) {
    final StringBuilder sb = new StringBuilder();
    IntStream.range(from, to)
        .forEach(i -> sb.append(section.lineMapper().apply(i + 1, section.rows().get(i))));
    return sb.toString();
  }
}
