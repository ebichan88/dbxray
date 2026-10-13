package com.dbxray.domain.model.sidecar;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * テーブル単位の手動付帯情報（{@link TableAnnotation}）の集合を扱うクラス<br>
 * サイドカーYAMLを読み込んだ結果を、スキーマ.テーブルをキーとして保持する。
 * 該当する付帯情報が存在しないテーブルに対しては空の付帯情報を返すため、呼び出し側はnullを気にせず利用できる
 */
public final class Annotations {

  private final Map<TableKey, TableAnnotation> byKey;

  private Annotations(Map<TableKey, TableAnnotation> byKey) {
    this.byKey = Collections.unmodifiableMap(byKey);
  }

  /**
   * テーブルキーごとの付帯情報マップからインスタンスを生成する静的ファクトリメソッド
   *
   * @param byKey テーブルキーをキー、付帯情報を値とするマップ
   * @return Annotationsインスタンス
   */
  public static Annotations of(Map<TableKey, TableAnnotation> byKey) {
    return new Annotations(new LinkedHashMap<>(byKey));
  }

  /**
   * 付帯情報を持たない空のインスタンスを生成する静的ファクトリメソッド<br>
   * サイドカー機能が無効（未設定）の場合に利用する
   *
   * @return 空のAnnotationsインスタンス
   */
  public static Annotations empty() {
    return new Annotations(Map.of());
  }

  /**
   * 指定したテーブルに対応する付帯情報を取得するメソッド
   *
   * @return 対応する付帯情報。存在しない場合は{@link TableAnnotation#EMPTY}
   */
  public TableAnnotation belongingTo(TableEntity table) {
    return byKey.getOrDefault(TableKey.of(table), TableAnnotation.EMPTY);
  }

  /**
   * 付帯情報が定義されているテーブルキーの集合を取得するメソッド<br>
   * 実在しないテーブルに対する付帯情報（孤児付帯情報）の検出に利用する
   */
  public Set<TableKey> tableKeys() {
    return byKey.keySet();
  }

  /**
   * 付帯情報が1件も存在しないか判定するメソッド
   *
   * @return 付帯情報が1件も存在しない場合はtrue
   */
  public boolean isEmpty() {
    return byKey.isEmpty();
  }
}
