package com.dbxray.domain.model.table;

import java.util.List;
import java.util.Map;

/**
 * ビューが参照するテーブルの集合を扱うクラス<br>
 * ビューから参照先（{@link #belongingTo(TableEntity)}）と、
 * テーブルから参照しているビュー（{@link #referencingTo(TableEntity)}）の両方向で引ける
 */
public final class ViewReferences extends AbstractEntities<ViewReferenceEntity> {

  /** 参照されるテーブルのテーブルキーでインデックス化したマップ */
  private final Map<TableKey, List<ViewReferenceEntity>> incomingByKey;

  private ViewReferences(
      Map<TableKey, List<ViewReferenceEntity>> byKey,
      Map<TableKey, List<ViewReferenceEntity>> incomingByKey) {
    super(byKey);
    this.incomingByKey = Map.copyOf(incomingByKey);
  }

  /** ビューの参照のリストから、ビュー・参照されるテーブルの両方のテーブルキーで引けるコレクションを生成する */
  public static ViewReferences of(List<ViewReferenceEntity> list) {
    return new ViewReferences(index(list), index(list, ViewReferenceEntity::referenceTableKey));
  }

  /**
   * 指定されたテーブルを参照しているビューの参照のリストを取得するメソッド
   *
   * @return 参照しているビューの参照のリスト（取得順）。存在しない場合は空のリスト
   */
  public List<ViewReferenceEntity> referencingTo(TableEntity table) {
    return incomingByKey.getOrDefault(TableKey.of(table), List.of());
  }
}
