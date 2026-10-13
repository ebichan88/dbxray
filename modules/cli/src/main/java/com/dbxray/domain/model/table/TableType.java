package com.dbxray.domain.model.table;

import java.util.Arrays;
import java.util.Objects;

/** テーブルの種類を表す列挙型 */
public enum TableType {
  /** テーブル */
  TABLE("table"),
  /** ビュー */
  VIEW("view"),
  /** マテリアライズドビュー */
  MATERIALIZED_VIEW("materialized_view");

  private final String id;

  TableType(String id) {
    this.id = id;
  }

  /**
   * 区分を示す名前を取得するメソッド
   *
   * @return カタログから取得した区分の名前。定義書の「区分」列・ディレクトリ名・スナップショットにも使う（例: {@code materialized_view}）
   */
  public String id() {
    return id;
  }

  /**
   * 名前から区分を求めるメソッド
   *
   * @param id 区分を示す名前（{@link #id()}）
   * @throws IllegalArgumentException 対応していない区分の場合（nullを含む）
   */
  public static TableType fromId(String id) {
    return Arrays.stream(values())
        .filter(type -> Objects.equals(id, type.id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown table type: " + id));
  }

  /**
   * view または materialized viewであるか判定するメソッド
   *
   * @return view または materialized viewの場合はtrue、それ以外の場合はfalse
   */
  public boolean isView() {
    return switch (this) {
      case VIEW, MATERIALIZED_VIEW -> true;
      case TABLE -> false;
    };
  }
}
