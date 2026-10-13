package com.dbxray.infrastructure.db.type;

import com.dbxray.domain.model.database.Dbms;
import java.util.Arrays;
import java.util.Objects;

/** 接続先のDB種別と、DB種別ごとに使い分ける部品（mapperの名前空間・ドメインのDBMS種別）の対応 */
public enum DatabaseType {
  /** PostgreSQL */
  POSTGRESQL("postgresql", Dbms.POSTGRESQL),
  /** Oracle */
  ORACLE("oracle", Dbms.ORACLE);

  private final String id;
  private final Dbms dbms;

  DatabaseType(String id, Dbms dbms) {
    this.id = id;
    this.dbms = dbms;
  }

  /**
   * DB種別を示す名前を取得するメソッド
   *
   * @return JDBCドライバが返すDBの製品名を小文字にしたもの。mapperの名前空間にも使う（例: {@code postgresql}）
   */
  public String id() {
    return id;
  }

  /** DB種別に対応する、ドメインのDBMS種別を取得するメソッド */
  public Dbms dbms() {
    return dbms;
  }

  /**
   * 名前からDB種別を求めるメソッド
   *
   * @param id DB種別を示す名前（{@link #id()}）
   * @throws IllegalArgumentException 対応していないDB種別の場合
   */
  public static DatabaseType fromId(String id) {
    return Arrays.stream(values())
        .filter(type -> Objects.equals(id, type.id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported database type: " + id));
  }
}
