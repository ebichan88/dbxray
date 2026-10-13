package com.dbxray.domain.service.writer.template;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.HORIZONTAL_RULE;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import java.util.List;

/**
 * 行数の多い表を複数ページに分割して出力する際に共通で利用するMarkdownのテンプレートを扱うクラス<br>
 * GitHub上で表として表示できる行数には上限があるため、一覧系のドキュメントは行数が多い場合に別ファイルへ分割して出力する。本体ページに載せるリンク一覧と、分割ページのページ送りを扱う
 */
public final class PagedSectionTemplates {

  private PagedSectionTemplates() {}

  /** セクションの見出し行 */
  public static String heading(String heading) {
    return "## " + heading + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * 分割した詳細ページへのリンク一覧セクション<br>
   * 行数が上限を超える場合、本体ページには表を載せず分割したページへのリンクのみを掲載する。
   * リンクの表示名は、見出しにページ番号を付けたもの（例: {@code テーブル情報_2}）
   *
   * @param pageHrefs 各ページへの相対パスのリスト（1ページ目から順に並ぶこと）
   */
  public static String pagedSectionLinks(String heading, List<String> pageHrefs) {
    final StringBuilder sb = new StringBuilder(heading(heading));
    for (int i = 0; i < pageHrefs.size(); i++) {
      sb.append(MarkdownTemplateSupport.linkListItem(heading + "_" + (i + 1), pageHrefs.get(i)));
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * 分割した詳細ページのフッター（ページ送り）
   *
   * @param prevHref 前ページへの相対パス。存在しない場合はnull
   * @param nextHref 次ページへの相対パス。存在しない場合はnull
   * @param backHref 本体ページへの相対パス
   * @param backLabel 本体ページへのリンク表示名
   */
  public static String pageFooter(
      String prevHref, String nextHref, String backHref, String backLabel) {
    final StringBuilder sb = new StringBuilder(HORIZONTAL_RULE).append(LINE_SEPARATOR_DOUBLE);
    if (prevHref != null) {
      sb.append(String.format("[<<前へ](%s) ", prevHref));
    }
    if (nextHref != null) {
      sb.append(String.format("[次へ>>](%s) ", nextHref));
    }
    return sb.append(String.format("[%s](%s)", backLabel, backHref))
        .append(LINE_SEPARATOR)
        .toString();
  }

  /**
   * ページ送りを持たない、一覧へ戻る導線のみのフッター<br>
   * 分割されない一覧・定義書ページ（テーブル定義書・個別定義書・各種一覧・ER図索引）で共通して用いる
   *
   * @param backHref 一覧ページへの相対パス
   * @param backLabel 一覧ページへのリンク表示名
   */
  public static String backOnlyFooter(String backHref, String backLabel) {
    return pageFooter(null, null, backHref, backLabel);
  }
}
