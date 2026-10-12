package com.dbxray.presentation;

import com.dbxray.domain.model.snapshot.ContentDiff;
import com.dbxray.domain.model.snapshot.DiffResult;
import java.util.List;

/** DB vs ドキュメントの差分検知（{@code --check}モード）の比較結果からレポート用のメッセージを組み立てるクラス */
final class DiffReportFormatter {

  /** 差分メッセージに含めるunified diffの行数の上限（オブジェクト単位）。超えた分は残り行数のみ表示する */
  private static final int MAX_DIFF_LINES_PER_TARGET = 200;

  /** 差分メッセージに含めるunified diffの行数の上限（全体合計）。超えた場合、それ以降のオブジェクトの差分本体は省略する */
  private static final int MAX_DIFF_LINES_TOTAL = 2000;

  private DiffReportFormatter() {}

  /**
   * 比較結果からメッセージを組み立てるメソッド
   *
   * @return 差分の内容を含むメッセージ
   */
  static String format(DiffResult diffResult) {
    final String lineSeparator = System.getProperty("line.separator");
    if (!diffResult.hasDifference()) {
      return "No difference detected between the generated document and the committed document.";
    }
    final StringBuilder message =
        new StringBuilder(
            "Difference detected between the generated document and the committed document.");
    appendSection(
        message,
        lineSeparator,
        "Only in generated document (possibly missing commit):",
        diffResult.onlyInGenerated());
    appendSection(
        message,
        lineSeparator,
        "Only in committed document (possibly a stale file):",
        diffResult.onlyInCommitted());
    appendSection(
        message,
        lineSeparator,
        "Content differs:",
        diffResult.contentDiffer().stream().map(ContentDiff::target).toList());
    appendContentDiffs(message, lineSeparator, diffResult.contentDiffer());
    return message.toString();
  }

  /**
   * 差分の対象（ファイルパスまたはオブジェクト）の一覧をメッセージへ追記するメソッド。対象が空の場合は何も追記しない
   *
   * @param message 追記先のメッセージ
   * @param targets 区分に属する差分の対象のリスト
   */
  private static void appendSection(
      StringBuilder message, String lineSeparator, String title, List<String> targets) {
    if (targets.isEmpty()) {
      return;
    }
    message.append(lineSeparator).append(title);
    targets.forEach(target -> message.append(lineSeparator).append(" - ").append(target));
  }

  /**
   * 内容が一致しないオブジェクトについて、unified diff本体をメッセージへ追記するメソッド<br>
   * 1オブジェクトあたり{@value #MAX_DIFF_LINES_PER_TARGET}行、全体で{@value #MAX_DIFF_LINES_TOTAL}行を上限とし、
   * 超えた分は省略した旨のみ表示する（対象自体は{@link #format}が組み立てる「Content differs:」の一覧にすべて含まれるため、見落としにはならない）
   *
   * @param message 追記先のメッセージ
   * @param contentDiffer 内容が一致しないものの一覧
   */
  private static void appendContentDiffs(
      StringBuilder message, String lineSeparator, List<ContentDiff> contentDiffer) {
    int remainingBudget = MAX_DIFF_LINES_TOTAL;
    for (int i = 0; i < contentDiffer.size(); i++) {
      if (remainingBudget <= 0) {
        message
            .append(lineSeparator)
            .append("(diff omitted for ")
            .append(contentDiffer.size() - i)
            .append(" more objects)");
        break;
      }
      final List<String> unifiedDiff = contentDiffer.get(i).unifiedDiff();
      final int shown =
          Math.min(Math.min(MAX_DIFF_LINES_PER_TARGET, remainingBudget), unifiedDiff.size());
      for (int j = 0; j < shown; j++) {
        message.append(lineSeparator).append(unifiedDiff.get(j));
      }
      if (shown < unifiedDiff.size()) {
        message
            .append(lineSeparator)
            .append("... (")
            .append(unifiedDiff.size() - shown)
            .append(" more lines)");
      }
      remainingBudget -= shown;
    }
  }
}
