package com.dbxray.domain.model.database;

/**
 * DBのカタログから取得するデータベースの情報に関するrecordクラス
 *
 * @param dbms DBMSの種別。関数の定義本体の字句の規則（コメント・文字列の書き方）の切り替えにも使う
 * @param majorVersion DBMSのメジャーバージョン（例: PostgreSQLの16、Oracleの23）。マイナー版の更新で出力が変わらないよう、メジャーバージョンだけを持つ
 */
public record DatabaseEntity(String dbName, Dbms dbms, int majorVersion) {}
