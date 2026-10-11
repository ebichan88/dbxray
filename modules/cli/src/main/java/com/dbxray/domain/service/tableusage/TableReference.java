package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.tableusage.CrudOperation;

/**
 * 定義本体の、テーブルを指す位置にあった名前1件（出力対象のテーブルとの照合の前）
 *
 * @param schema カタログでのスキーマ名。スキーマ修飾が無い場合は空文字
 * @param table カタログでのテーブル名
 */
record TableReference(String schema, String table, CrudOperation operation) {}
