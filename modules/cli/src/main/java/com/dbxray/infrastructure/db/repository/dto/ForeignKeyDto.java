package com.dbxray.infrastructure.db.repository.dto;

import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;

/** 外部キー情報に関してORMのデータの受け渡しに利用するDTOクラス */
public record ForeignKeyDto(
    String schemaName,
    String tableName,
    String foreignKeyName,
    String columnNames,
    String referenceSchemaName,
    String referenceTableName,
    String referenceColumnNames,
    boolean childKeyUnique,
    boolean childKeyMandatory) {

  /**
   * DTOからEntityへの変換メソッド<br>
   * DBのカタログから取得した外部キー制約のため、由来は常に{@link RelationType#PHYSICAL}となる。
   * SQLがカンマ区切りで連結して返す列名は、ここでリストへ分解する
   *
   * @return ForeignKeyEntityのインスタンス
   */
  public ForeignKeyEntity toEntity() {
    return new ForeignKeyEntity(
        schemaName,
        tableName,
        foreignKeyName,
        DtoValues.split(columnNames, ","),
        referenceSchemaName,
        referenceTableName,
        DtoValues.split(referenceColumnNames, ","),
        Cardinality.of(childKeyUnique, childKeyMandatory),
        RelationType.PHYSICAL);
  }
}
