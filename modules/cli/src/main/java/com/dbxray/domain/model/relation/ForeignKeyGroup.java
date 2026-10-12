package com.dbxray.domain.model.relation;

import com.dbxray.domain.model.table.TableKey;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1枚のER図に描画する外部キーのまとまりを扱うクラス<br>
 * スキーマ単位のER図全体、または連結成分をまとめ直したグループ（{@link ForeignKeyGroups}参照）を表す。
 * 図に登場するテーブル（ノード）の算出と、図の規模に関わる判定（ノード数の上限超過・主なテーブル）を提供する
 */
public final class ForeignKeyGroup {

  private final List<ForeignKeyEntity> foreignKeys;

  /** 外部キーの両端のテーブルキー（スキーマ名・テーブル名順） */
  private final List<TableKey> nodes;

  private ForeignKeyGroup(List<ForeignKeyEntity> foreignKeys) {
    this.foreignKeys = List.copyOf(foreignKeys);
    final Set<TableKey> nodeKeys = new LinkedHashSet<>();
    foreignKeys.forEach(
        fk -> {
          nodeKeys.add(fk.tableKey());
          nodeKeys.add(fk.referenceTableKey());
        });
    this.nodes =
        nodeKeys.stream()
            .sorted(Comparator.comparing(TableKey::schema).thenComparing(TableKey::table))
            .toList();
  }

  /**
   * 外部キーのリストからまとまりを生成するメソッド
   *
   * @param foreignKeys まとまりに属する外部キーのリスト
   */
  public static ForeignKeyGroup of(List<ForeignKeyEntity> foreignKeys) {
    return new ForeignKeyGroup(foreignKeys);
  }

  /** まとまりに属する外部キーのリストを取得するメソッド */
  public List<ForeignKeyEntity> foreignKeys() {
    return foreignKeys;
  }

  /**
   * 図に登場するテーブル（外部キーの両端）を取得するメソッド<br>
   * 出力のたびに並び順が変わらないよう、スキーマ名・テーブル名順に並べる
   *
   * @return 重複を除いたテーブルキーのリスト
   */
  public List<TableKey> nodes() {
    return nodes;
  }

  /**
   * 図に登場するテーブル（ノード）の数を取得するメソッド
   *
   * @return テーブル数
   */
  public int nodeCount() {
    return nodes.size();
  }

  /**
   * ノード数が1枚の図に描画する上限を超えるか判定するメソッド
   *
   * @return 上限を超える場合はtrue（上限なしの場合は常にfalse）
   */
  boolean exceeds(NodeLimit limit) {
    return limit.isExceededBy(nodeCount());
  }

  /**
   * 上限のもとで図を描画するか、描画を省略するかの計画を立てるメソッド<br>
   * 「描くか・省くか」の決定はここに集約し、呼び出し側は結果に従って出力を切り替える
   */
  public RenderingPlan planRendering(NodeLimit limit) {
    return exceeds(limit) ? new RenderingPlan.Omit(this, limit) : new RenderingPlan.Draw(this);
  }

  /**
   * まとまりの中で最も多くの外部キーが接続するテーブルを求めるメソッド<br>
   * どのまとまりなのかを識別する手がかりとして一覧に掲載する。外部キーを1つ以上持つまとまりでだけ呼ぶ
   *
   * @return 最も多くの外部キーが接続するテーブル。同数の場合はスキーマ名・テーブル名順で先頭のもの
   * @throws java.util.NoSuchElementException 外部キーが無いまとまりの場合
   */
  public TableKey mainTable() {
    final Map<TableKey, Integer> degrees = new HashMap<>();
    foreignKeys.forEach(
        fk -> {
          degrees.merge(fk.tableKey(), 1, Integer::sum);
          degrees.merge(fk.referenceTableKey(), 1, Integer::sum);
        });
    return degrees.entrySet().stream()
        .sorted(
            Map.Entry.<TableKey, Integer>comparingByValue()
                .reversed()
                .thenComparing(entry -> entry.getKey().schema())
                .thenComparing(entry -> entry.getKey().table()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }
}
