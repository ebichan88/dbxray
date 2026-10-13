package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 多対多をつなぐテーブル（中間テーブル）と、主キーを構成する関連<br>
 * 判定の規則は{@link JunctionDetector}を参照
 *
 * @param key 中間テーブル
 * @param links 主キーを構成する関連（外部キー・論理リレーション）。2本以上あり、主キーの列を過不足なく覆い、どの関連も外すと覆えなくなる
 */
public record JunctionTable(ObjectKey key, List<Relation> links) {

  /** 関連の複製を保持し、外から変更できないようにする */
  public JunctionTable {
    links = List.copyOf(links);
  }

  /**
   * 中間テーブルがつなぐテーブルを返すメソッド<br>
   * スナップショットに含まれないテーブルも含む
   *
   * @return 関連の順。同じテーブルを複数の関連で参照する自己多対多では、そのテーブルを1つだけ返す
   */
  public List<ObjectKey> parents() {
    return links.stream().map(Relation::to).distinct().toList();
  }
}
