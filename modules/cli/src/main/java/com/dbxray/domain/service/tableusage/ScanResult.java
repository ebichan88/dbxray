package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import java.util.List;

/**
 * 定義本体からテーブルを指す位置の名前を拾った結果
 *
 * @param references テーブルを指す位置にあった名前（出現順。CTEの名前は除く）
 * @param dynamicSql 動的SQLの種類（最初に現れた順）
 */
record ScanResult(List<TableReference> references, List<DynamicSqlKind> dynamicSql) {

  /** リストは変更不可な複製として保持する */
  ScanResult {
    references = List.copyOf(references);
    dynamicSql = List.copyOf(dynamicSql);
  }
}
