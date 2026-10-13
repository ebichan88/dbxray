package com.dbxray.domain.model.insight;

import com.dbxray.domain.model.viewpoint.ViewpointContent;
import java.util.List;

/**
 * 観点（業務ドメイン別にテーブルをまとめる切り口）の参考情報1ファイル分<br>
 * 実行のたびに取得し直すが、宣言を変えない限り内容は変わらない。{@code --check}の比較対象ではないため、生成日のような実行ごとに変わる値は持たない
 *
 * @param formatVersion 参考情報の形式のバージョン（形式を互換性なく変更した場合に上げる）
 */
public record ViewpointsInsight(int formatVersion, List<ViewpointInsight> viewpoints) {

  /** 現行の参考情報の形式のバージョン */
  public static final int FORMAT_VERSION = 1;

  /**
   * 出力対象の観点から参考情報を生成するメソッド
   *
   * @param contents 出力対象の観点ごとの出力内容（{@code Viewpoint.resolve}で求めたもの）
   */
  public static ViewpointsInsight of(List<ViewpointContent> contents) {
    return new ViewpointsInsight(
        FORMAT_VERSION, contents.stream().map(ViewpointInsight::of).toList());
  }
}
