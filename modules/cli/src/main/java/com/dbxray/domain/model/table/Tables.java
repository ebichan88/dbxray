package com.dbxray.domain.model.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 出力対象のテーブル（テーブル一覧）の集合を扱うクラス<br>
 * スキーマ単位のまとまり・テーブルキーによる検索・出力対象に含まれるかの判定など、 テーブル一覧に対して複数の箇所で必要になる見方をこのクラスに集約する
 */
public final class Tables {

  private final List<TableEntity> list;

  /** テーブルキーをキー、テーブル情報を値とするマップ（同じキーが重複する場合は先に現れたものを用いる） */
  private final Map<TableKey, TableEntity> byKey;

  private Tables(List<TableEntity> list) {
    this.list = List.copyOf(list);
    final Map<TableKey, TableEntity> map = new LinkedHashMap<>();
    this.list.forEach(table -> map.putIfAbsent(TableKey.of(table), table));
    this.byKey = Collections.unmodifiableMap(map);
  }

  /**
   * テーブル情報のリストからインスタンスを生成する静的ファクトリメソッド
   *
   * @param list テーブル情報のリスト（取得順）
   */
  public static Tables of(List<TableEntity> list) {
    return new Tables(list);
  }

  /**
   * テーブル情報のリストを取得するメソッド
   *
   * @return テーブル情報のリスト（取得順・変更不可）
   */
  public List<TableEntity> asList() {
    return list;
  }

  /** テーブル情報のストリームを取得するメソッド */
  public Stream<TableEntity> stream() {
    return list.stream();
  }

  /**
   * テーブルが1件も存在しないか判定するメソッド
   *
   * @return テーブルが1件も存在しない場合はtrue
   */
  public boolean isEmpty() {
    return list.isEmpty();
  }

  /**
   * 指定したテーブルキーのテーブルが含まれるか判定するメソッド
   *
   * @return 含まれる場合はtrue
   */
  public boolean contains(TableKey key) {
    return byKey.containsKey(key);
  }

  /**
   * 指定したテーブルキーのテーブル情報を取得するメソッド
   *
   * @return テーブル情報。含まれない場合は空
   */
  public Optional<TableEntity> find(TableKey key) {
    return Optional.ofNullable(byKey.get(key));
  }

  /**
   * テーブルをスキーマ単位にまとめるメソッド<br>
   * スキーマ・テーブルの並びは取得順を保つ。返却するマップはフィールドとして保持せず呼び出しのたびに構築する （利用側のスコープを抜けた時点で解放されるようにするため）
   *
   * @return スキーマ名をキー、当該スキーマのテーブルのリストを値とするマップ
   */
  public Map<String, List<TableEntity>> bySchema() {
    final Map<String, List<TableEntity>> bySchema = new LinkedHashMap<>();
    list.forEach(
        table ->
            bySchema.computeIfAbsent(table.schemaName(), schema -> new ArrayList<>()).add(table));
    return bySchema;
  }

  /**
   * テーブルを物理テーブル名でまとめるメソッド<br>
   * 同じ名前のテーブルは取得順に並べる。返却するマップは{@link #bySchema()}と同じく呼び出しのたびに構築する
   *
   * @return 物理テーブル名をキー、当該名前のテーブル（スキーマ違い）のリストを値とするマップ
   */
  public Map<String, List<TableEntity>> byTableName() {
    final Map<String, List<TableEntity>> byName = new LinkedHashMap<>();
    list.forEach(
        table ->
            byName
                .computeIfAbsent(table.physicalTableName(), name -> new ArrayList<>())
                .add(table));
    return byName;
  }
}
