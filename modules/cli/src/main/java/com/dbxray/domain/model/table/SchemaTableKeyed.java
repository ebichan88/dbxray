package com.dbxray.domain.model.table;

/** スキーマ名・テーブル名を持つエンティティに共通の振る舞いを提供するインタフェース */
public interface SchemaTableKeyed {

  /** スキーマ名を取得するメソッド */
  String schemaName();

  /** テーブル名を取得するメソッド */
  String tableName();

  /**
   * 所属するテーブルの{@code スキーマ.テーブル}形式の名称を取得するメソッド
   */
  default String qualifiedName() {
    return tableKey().qualifiedName();
  }

  /** 所属するテーブルのテーブルキーを取得するメソッド */
  default TableKey tableKey() {
    return TableKey.of(schemaName(), tableName());
  }
}
