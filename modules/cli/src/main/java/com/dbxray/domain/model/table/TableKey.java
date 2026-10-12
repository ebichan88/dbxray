package com.dbxray.domain.model.table;

import java.util.Optional;

/** スキーマ名とテーブル名を組み合わせた値オブジェクト */
public record TableKey(String schema, String table) {

  /** {@code スキーマ.テーブル} 形式の文字列の区切り文字 */
  private static final char SCHEMA_TABLE_SEPARATOR = '.';

  /** スキーマ名とテーブル名からTableKeyインスタンスを生成する静的ファクトリメソッド */
  public static TableKey of(String schema, String table) {
    return new TableKey(schema, table);
  }

  /**
   * TableEntityからTableKeyインスタンスを生成する静的ファクトリメソッド
   *
   * @param t TableEntityインスタンス
   */
  public static TableKey of(TableEntity t) {
    return new TableKey(t.schemaName(), t.physicalTableName());
  }

  /**
   * {@code スキーマ.テーブル} 形式の文字列を解析してTableKeyを生成する静的ファクトリメソッド<br>
   * サイドカーYAML等、外部入力の「スキーマ.テーブル」形式のキー文字列を解釈する用途に用いる。
   * スキーマ名・テーブル名はトリムする。
   * 区切りの{@code .}が無い場合、またはトリム後にスキーマ名・テーブル名のいずれかが空になる場合は解析失敗として空を返す（呼び出し側で警告等の対応を行う）
   *
   * @param rawKey {@code スキーマ.テーブル} 形式の文字列（null・空白可）
   * @return 解析したTableKey。形式が不正な場合は空
   */
  public static Optional<TableKey> parse(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return Optional.empty();
    }
    final int separatorIndex = rawKey.indexOf(SCHEMA_TABLE_SEPARATOR);
    if (separatorIndex < 0) {
      return Optional.empty();
    }
    final String schema = rawKey.substring(0, separatorIndex).trim();
    final String table = rawKey.substring(separatorIndex + 1).trim();
    if (schema.isEmpty() || table.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new TableKey(schema, table));
  }

  /**
   * {@code スキーマ.テーブル} 形式の名称を取得するメソッド
   *
   * @return {@code スキーマ.テーブル} 形式の名称
   */
  public String qualifiedName() {
    return schema + SCHEMA_TABLE_SEPARATOR + table;
  }
}
