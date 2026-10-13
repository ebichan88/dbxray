package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.List;

/** テスト用のテーブルを組み立てるビルダー */
public final class TestTables {

  private final ObjectKey key;
  private String logicalName;
  private String type = "table";
  private String description;
  private String remarks;
  private final List<ColumnEntry> columns = new ArrayList<>();
  private final List<RelationEntry> foreignKeys = new ArrayList<>();
  private final List<RelationEntry> logicalRelations = new ArrayList<>();
  private final List<TriggerEntry> triggers = new ArrayList<>();
  private final List<ObjectKey> referencedTables = new ArrayList<>();
  private String json;

  private TestTables(String database, String schema, String name) {
    this.key = new ObjectKey(database, schema, name);
  }

  /** DB名testdb・スキーマsampleのテーブル */
  public static TestTables table(String name) {
    return new TestTables("testdb", "sample", name);
  }

  /** DB名・スキーマ名を指定したテーブル */
  public static TestTables table(String database, String schema, String name) {
    return new TestTables(database, schema, name);
  }

  public TestTables logicalName(String value) {
    this.logicalName = value;
    return this;
  }

  public TestTables type(String value) {
    this.type = value;
    return this;
  }

  public TestTables description(String value) {
    this.description = value;
    return this;
  }

  public TestTables remarks(String value) {
    this.remarks = value;
    return this;
  }

  public TestTables column(String name) {
    return column(name, null, null);
  }

  /** 型がintegerのカラムを、指定した順に足す */
  public TestTables columns(String... names) {
    for (final String name : names) {
      column(name);
    }
    return this;
  }

  public TestTables column(String name, String logicalName, String remarks) {
    return column(new ColumnEntry(name, logicalName, "integer", false, false, null, remarks));
  }

  public TestTables column(ColumnEntry column) {
    columns.add(column);
    return this;
  }

  /** 主キーを構成するカラム（複合主キーの場合は列の数だけ呼ぶ） */
  public TestTables primaryKey(String name) {
    return column(new ColumnEntry(name, null, "integer", true, true, null, null));
  }

  /** 同じスキーマのテーブルへの1カラムの外部キー */
  public TestTables foreignKey(String column, String referenceTable, String referenceColumn) {
    foreignKeys.add(
        new RelationEntry(
            key.name() + "_" + column + "_fkey",
            List.of(column),
            key.schema(),
            referenceTable,
            List.of(referenceColumn),
            "ONE_TO_MANY"));
    return this;
  }

  public TestTables foreignKey(RelationEntry relation) {
    foreignKeys.add(relation);
    return this;
  }

  /** 同じスキーマのテーブルへの1カラムの論理リレーション */
  public TestTables logicalRelation(String column, String referenceTable, String referenceColumn) {
    logicalRelations.add(
        new RelationEntry(
            column,
            List.of(column),
            key.schema(),
            referenceTable,
            List.of(referenceColumn),
            "OPTIONAL_ONE_TO_MANY"));
    return this;
  }

  /** 行ごとに実行するAFTER INSERTのトリガー */
  public TestTables trigger(String name, String function) {
    triggers.add(new TriggerEntry(name, "AFTER", List.of("INSERT"), "ROW", function));
    return this;
  }

  /** ビューが参照する、同じDB・スキーマのテーブル */
  public TestTables referencedTable(String name) {
    referencedTables.add(new ObjectKey(key.database(), key.schema(), name));
    return this;
  }

  /** スナップショットの1行（未指定の場合はschema・nameだけの行） */
  public TestTables json(String value) {
    this.json = value;
    return this;
  }

  public TableEntry build() {
    return new TableEntry(
        key,
        logicalName,
        type,
        description,
        remarks,
        columns,
        foreignKeys,
        logicalRelations,
        triggers,
        referencedTables,
        json != null
            ? json
            : "{\"schema\":\"" + key.schema() + "\",\"name\":\"" + key.name() + "\"}");
  }
}
