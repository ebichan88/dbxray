package com.dbxray.infrastructure.db.repository.dto;

import com.dbxray.domain.model.table.TriggerEntity;

/** トリガー情報に関してORMのデータの受け渡しに利用するDTOクラス */
public record TriggerDto(
    String schemaName,
    String tableName,
    String triggerName,
    String timing,
    String events,
    String orientation,
    String functionName,
    String triggerDefinition,
    String body) {

  /**
   * DTOからEntityへの変換メソッド<br>
   * SQLがスラッシュ区切りで連結して返す対象イベントは、ここでリストへ分解する。
   * 本体の末尾の改行・空白は、意味を持たず、スナップショットの差分の原因にもなるため除く
   *
   * @return TriggerEntityのインスタンス
   */
  public TriggerEntity toEntity() {
    return new TriggerEntity(
        schemaName,
        tableName,
        triggerName,
        DtoValues.text(timing),
        DtoValues.split(events, "/"),
        DtoValues.text(orientation),
        DtoValues.text(functionName),
        DtoValues.text(triggerDefinition),
        DtoValues.text(body).stripTrailing());
  }
}
