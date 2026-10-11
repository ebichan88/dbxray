package com.dbxray.infrastructure.db.repository.dto;

import com.dbxray.domain.model.database.DatabaseEntity;
import com.dbxray.domain.model.database.Dbms;

/**
 * データベースの情報に関してORMのデータの受け渡しに利用するDTOクラス
 *
 * @param dbmsName DBMS種別（PostgreSQL/Oracle）
 * @param majorVersion DBMSのメジャーバージョン
 */
public record DatabaseDto(String dbName, String dbmsName, int majorVersion) {
  /**
   * DTOからEntityへの変換メソッド
   *
   * @return DatabaseEntityのインスタンス
   */
  public DatabaseEntity toEntity() {
    return new DatabaseEntity(dbName, Dbms.fromDisplayName(dbmsName), majorVersion);
  }
}
