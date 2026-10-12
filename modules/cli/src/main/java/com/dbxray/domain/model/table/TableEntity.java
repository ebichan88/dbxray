package com.dbxray.domain.model.table;

/**
 * テーブル情報に関するrecordクラス
 *
 * @param tableType 区分（table/view/materialized_view）
 * @param definition view/materialized viewの場合のソース定義（tableの場合は空文字）
 * @param partitionKey パーティション表（宣言的パーティションの親）の場合のパーティションキー（例: {@code RANGE (sold_on)}）。
 *     パーティション表でない場合は空文字
 */
public record TableEntity(
    String dbName,
    String schemaName,
    String logicalTableName,
    String physicalTableName,
    TableType tableType,
    String definition,
    String partitionKey) {

  public TableEntity(
      String dbName,
      String schemaName,
      String logicalTableName,
      String physicalTableName,
      TableType tableType,
      String definition) {
    this(dbName, schemaName, logicalTableName, physicalTableName, tableType, definition, "");
  }

  /**
   * スキーマ.テーブル 形式の名称を取得するメソッド
   *
   * @return スキーマ.テーブル 形式の名称
   */
  public String getSchemaTableName() {
    return TableKey.of(this).qualifiedName();
  }

  /**
   * テーブル定義書の見出しに使う名称を取得するメソッド
   *
   * @return {@code 物理テーブル名（論理テーブル名）}。論理テーブル名が無い場合は物理テーブル名だけ
   */
  public String getHeaderTableName() {
    if (logicalTableName == null || logicalTableName.isBlank()) {
      return physicalTableName;
    }
    return physicalTableName + "（" + logicalTableName + "）";
  }

  /**
   * パーティション表（宣言的パーティションの親）であるか判定するメソッド
   *
   * @return パーティション表の場合はtrue
   */
  public boolean isPartitioned() {
    return !partitionKey.isEmpty();
  }

  /**
   * view または materialized viewであるか判定するメソッド
   *
   * @return view または materialized viewの場合はtrue。それ以外の場合はfalseを返却
   */
  public boolean isView() {
    return tableType.isView();
  }
}
