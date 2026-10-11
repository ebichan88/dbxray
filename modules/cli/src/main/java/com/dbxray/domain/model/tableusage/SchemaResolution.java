package com.dbxray.domain.model.tableusage;

/** 利用しているテーブルの名前のスキーマの決まり方 */
public enum SchemaResolution {
  /** スキーマ修飾・関数の{@code SET search_path}・Oracleの所有者のスキーマから決まった */
  DETERMINED(""),
  /** スキーマ修飾が無く、実行時の{@code search_path}で決まる（PostgreSQL） */
  SEARCH_PATH("search_path"),
  /** スキーマ修飾が無く、実行者権限（{@code AUTHID CURRENT_USER}）のため実行するユーザーで決まる（Oracle） */
  CURRENT_USER("current_user"),
  /** スキーマ修飾が無く所有者のスキーマにも無いため、シノニム経由で参照している可能性がある（Oracleの定義者権限） */
  SYNONYM("synonym");

  private final String id;

  SchemaResolution(String id) {
    this.id = id;
  }

  /**
   * 参考情報・定義書で決まり方を示す名前を取得するメソッド
   *
   * @return {@code search_path}・{@code current_user}・{@code synonym}。決まった場合は空文字
   */
  public String id() {
    return id;
  }
}
