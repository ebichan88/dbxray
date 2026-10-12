package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.table.Tables;

/** テストで{@link FunctionTableUsageAnalyzer}を組み立てる */
final class AnalyzerFixtures {

  private AnalyzerFixtures() {}

  /** 本番ではスキーマごとに使い回すテーブル名の索引を、その場で求めて組み立てる */
  static FunctionTableUsageAnalyzer analyzerOf(Tables tables, Dbms dbms) {
    return FunctionTableUsageAnalyzer.of(tables, tables.byTableName(), dbms);
  }
}
