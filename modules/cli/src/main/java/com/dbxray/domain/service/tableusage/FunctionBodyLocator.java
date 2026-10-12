package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;

/**
 * 関数・プロシージャの定義から、解析する本体を切り出すインタフェース<br>
 * 見出し（引数・戻り値の型等）は解析しない（{@code RETURNS SETOF t}・{@code p t%ROWTYPE}をテーブルの利用と誤らないため）。
 * 見出しはあるが本体の始まりが見つからない定義も、見出しを本体と誤らないよう解析しない<br>
 * 実装はDBMSごとに持ち（{@link PostgresBodyLocator}・{@link OracleBodyLocator}）、インスタンスはスキーマごとに作る
 */
interface FunctionBodyLocator {

  /** DBMSに合う実装を生成する静的ファクトリメソッド */
  static FunctionBodyLocator of(Dbms dbms) {
    return switch (dbms) {
      case POSTGRESQL -> new PostgresBodyLocator();
      case ORACLE -> new OracleBodyLocator();
    };
  }

  /**
   * 定義から本体を切り出すメソッド
   *
   * @return 本体。解析しない場合（言語が対象外・wrap・定義や本体が無い・サブプログラムが見つからない）は、その理由を持つ結果
   */
  FunctionBody locate(FunctionEntity function);
}
