package com.dbxray.infrastructure.db.repository.dto;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;

/** テーブル情報に関してORMのデータの受け渡しに利用するDTOクラス */
public record TableDto(
    String dbName,
    String schemaName,
    String logicalTableName,
    String physicalTableName,
    String tableType,
    String definition,
    String partitionKey) {

  /**
   * DTOからEntityへの変換メソッド<br>
   * 区分はこの時点で{@link TableType}へ変換するため、未知の区分は読み込み時に検知される
   *
   * @return TableEntityのインスタンス
   * @throws IllegalArgumentException 区分が未知の値の場合
   */
  public TableEntity toEntity() {
    return new TableEntity(
        dbName,
        schemaName,
        DtoValues.text(logicalTableName),
        physicalTableName,
        TableType.fromId(tableType),
        DtoValues.text(definition),
        DtoValues.text(partitionKey));
  }
}
