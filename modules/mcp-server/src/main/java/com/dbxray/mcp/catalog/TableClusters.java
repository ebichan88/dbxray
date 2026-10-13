package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 関連のまとまりを求めた結果
 *
 * @param clusters まとまり。テーブルの多い順（同数は代表のテーブルの被参照の多い順・名前の順）
 * @param hubs まとまりを分けるために除いた共通のテーブル（被参照の多い順、同数は名前の順）
 * @param unrelatedTables 範囲内のどのテーブルとも関連を持たないため、まとまりに含めなかったテーブルの数
 */
public record TableClusters(
    List<TableCluster> clusters, List<TableEntry> hubs, int unrelatedTables) {

  /**
   * ハブ（まとまりを分けるために除く共通のテーブル）とみなす、被参照のテーブル数の下限<br>
   * 少数のテーブルからしか参照されないテーブルを除くと、共通のマスタではなく一続きの関連を切るだけになるため、下限に満たないテーブルしか無いまとまりは、上限を超えてもそのまま残す
   */
  public static final int HUB_MIN_INCOMING = 3;

  /** 複製して保持する */
  public TableClusters {
    clusters = List.copyOf(clusters);
    hubs = List.copyOf(hubs);
  }
}
