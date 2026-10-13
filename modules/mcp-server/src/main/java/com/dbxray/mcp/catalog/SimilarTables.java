package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 構造が似ているテーブルを探した結果
 *
 * @param total 条件を満たしたテーブルの総数（件数の上限で切り捨てる前の数）
 * @param tables 類似度の高い順（同じ場合は共通のカラムの多い順、DB名・スキーマ名・テーブル名の順）に、件数の上限まで並べたテーブル
 */
public record SimilarTables(int total, List<SimilarTable> tables) {

  /** 複製して保持する */
  public SimilarTables {
    tables = List.copyOf(tables);
  }
}
