package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 構造（カラム名）が似ているテーブルを探す条件
 *
 * @param columns 基準のカラム。正規化したカラム名が重なるものは、先に現れたものだけを比べる
 * @param base 基準にした既存のテーブル。カラム名で指定した場合はnull
 * @param minShared 共通のカラム数の下限
 * @param minSimilarity 類似度（%）の下限
 */
public record SimilarTableQuery(
    List<ColumnEntry> columns, ObjectKey base, int minShared, int minSimilarity) {

  /** 複製して保持する */
  public SimilarTableQuery {
    columns = List.copyOf(columns);
  }

  /** 既存のテーブルを基準にする条件を組み立てるメソッド */
  public static SimilarTableQuery ofTable(TableEntry base, int minShared, int minSimilarity) {
    return new SimilarTableQuery(base.columns(), base.key(), minShared, minSimilarity);
  }

  /**
   * カラム名の並びを基準にする条件を組み立てるメソッド<br>
   * 型が分からないため、結果に型の違いは含まれない
   *
   * @throws IllegalArgumentException カラム名が1つも無い場合
   */
  public static SimilarTableQuery ofColumns(List<String> names, int minShared, int minSimilarity) {
    if (names.isEmpty()) {
      throw new IllegalArgumentException("カラム名が空です。");
    }
    return new SimilarTableQuery(
        names.stream()
            .map(name -> new ColumnEntry(name, null, null, false, false, null, null))
            .toList(),
        null,
        minShared,
        minSimilarity);
  }

  /** 共通のカラム数と類似度が、ともに下限以上か判定するメソッド */
  boolean accepts(int sharedColumns, int similarity) {
    return sharedColumns >= minShared && similarity >= minSimilarity;
  }
}
