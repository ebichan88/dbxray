package com.dbxray.domain.model.database;

import java.time.LocalDate;

/**
 * 各ドキュメントの先頭に掲載する基本情報に関するrecordクラス<br>
 * データベースの情報（DBのカタログから取得する）と、ドキュメントの生成日（実行時に決まる）を組み合わせたもの
 *
 * @param dbms DBMS種別。関数の定義本体の字句の規則（コメント・文字列の書き方）の切り替えにも使う
 * @param majorVersion DBMSのメジャーバージョン
 */
public record BaseInfoEntity(String dbName, Dbms dbms, int majorVersion, LocalDate generatedDate) {

  /**
   * データベースの情報と生成日から基本情報を生成する静的ファクトリメソッド
   *
   * @param database DBのカタログから取得したデータベースの情報
   */
  public static BaseInfoEntity of(DatabaseEntity database, LocalDate generatedDate) {
    return new BaseInfoEntity(
        database.dbName(), database.dbms(), database.majorVersion(), generatedDate);
  }

  /**
   * ドキュメント・スナップショットに出力するDBMS種別の表示名を取得するメソッド
   *
   * @return {@code PostgreSQL}・{@code Oracle}
   */
  public String dbmsName() {
    return dbms.displayName();
  }
}
