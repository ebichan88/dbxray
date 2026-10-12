package com.dbxray.domain.model.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** エンティティの集合を扱う抽象クラス */
public abstract class AbstractEntities<T extends SchemaTableKeyed> {
  protected final Map<TableKey, List<T>> byKey;

  protected AbstractEntities(Map<TableKey, List<T>> byKey) {
    this.byKey = Collections.unmodifiableMap(byKey);
  }

  /**
   * リストを、各エンティティが所属するテーブルのテーブルキーでインデックス化するユーティリティメソッド
   *
   * @return テーブルキーをキー、エンティティのリストを値とするマップ
   */
  protected static <E extends SchemaTableKeyed> Map<TableKey, List<E>> index(List<E> list) {
    return index(list, SchemaTableKeyed::tableKey);
  }

  /**
   * リストをテーブルキーでインデックス化するユーティリティメソッド
   *
   * @param keyFn エンティティからテーブルキーを抽出する関数
   * @return テーブルキーをキー、エンティティのリストを値とするマップ
   */
  protected static <E> Map<TableKey, List<E>> index(List<E> list, Function<E, TableKey> keyFn) {
    final Map<TableKey, List<E>> map = new LinkedHashMap<>();
    list.forEach(e -> map.computeIfAbsent(keyFn.apply(e), k -> new ArrayList<>()).add(e));
    map.replaceAll((k, v) -> List.copyOf(v));
    return map;
  }

  /**
   * 指定されたテーブルに関連するエンティティのリストを取得するメソッド
   *
   * @return エンティティのリスト。該当するエンティティがない場合は空のリストを返す
   */
  public List<T> belongingTo(TableEntity table) {
    return byKey.getOrDefault(TableKey.of(table), List.of());
  }
}
