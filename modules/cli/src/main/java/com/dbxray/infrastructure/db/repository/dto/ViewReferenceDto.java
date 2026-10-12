package com.dbxray.infrastructure.db.repository.dto;

import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.ViewReferenceEntity;

/**
 * ビューが参照するテーブルに関してORMのデータの受け渡しに利用するDTOクラス
 *
 * @param viewName 参照する側のビューの名前
 * @param viewType 参照する側のビューの区分（view/materialized_view）
 * @param referenceTableType 参照されるテーブルの区分（table/view/materialized_view）
 */
public record ViewReferenceDto(
    String schemaName,
    String viewName,
    String viewType,
    String referenceSchemaName,
    String referenceTableName,
    String referenceTableType) {

  /**
   * DTOからEntityへの変換メソッド
   *
   * @return ViewReferenceEntityのインスタンス
   */
  public ViewReferenceEntity toEntity() {
    return new ViewReferenceEntity(
        schemaName,
        viewName,
        TableType.fromId(viewType),
        referenceSchemaName,
        referenceTableName,
        TableType.fromId(referenceTableType));
  }
}
