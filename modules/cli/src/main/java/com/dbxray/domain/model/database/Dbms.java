package com.dbxray.domain.model.database;

import java.util.Arrays;

/** 接続先のDBMSの種別 */
public enum Dbms {
  POSTGRESQL("PostgreSQL"),
  ORACLE("Oracle");

  private final String displayName;

  Dbms(String displayName) {
    this.displayName = displayName;
  }

  /**
   * 基本情報・スナップショットに出力する表示名を取得するメソッド
   *
   * @return {@code PostgreSQL}・{@code Oracle}
   */
  public String displayName() {
    return displayName;
  }

  /**
   * カタログから取得した表示名から種別を求めるメソッド
   *
   * @param displayName mapperのSQLが返す表示名（{@code PostgreSQL}・{@code Oracle}）
   * @throws IllegalArgumentException 対応していない表示名の場合（mapperのSQLとこのenumの食い違い）
   */
  public static Dbms fromDisplayName(String displayName) {
    return Arrays.stream(values())
        .filter(dbms -> dbms.displayName.equals(displayName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown DBMS: " + displayName));
  }
}
