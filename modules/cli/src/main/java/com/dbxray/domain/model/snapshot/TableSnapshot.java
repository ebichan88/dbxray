package com.dbxray.domain.model.snapshot;

import static com.dbxray.domain.model.snapshot.SnapshotValues.text;

import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.target.TableDefinitionContent;
import java.util.List;

/**
 * スキーマのスナップショットのうち、1テーブル分の情報を表すrecordクラス<br>
 * テーブル定義書1ファイル分と同じ情報（カラム・インデックス・制約・外部キー・論理リレーション・トリガー・手動付帯情報）を、Markdownの表示形式ではなく個別の項目として保持する。
 * 被参照側の外部キーと、テーブルを参照しているビューは、参照元の{@code foreignKeys}・{@code referencedTables}から導出できるため保持しない
 *
 * @param type 区分（table/view/materialized_view）
 * @param description テーブル説明（サイドカーYAML由来）
 * @param remarks テーブル備考（サイドカーYAML由来）
 * @param definition view/materialized viewのソース定義
 * @param partitionKey パーティション表のパーティションキー（例: {@code RANGE (sold_on)}）。個々のパーティションは含めない
 *     （パーティションを自動で追加する運用で、{@code --check}が追加のたびに差分を報告しないようにするため）
 * @param foreignKeys DBに実在する外部キー制約のリスト
 * @param logicalRelations サイドカーYAMLで宣言された論理リレーションのリスト
 * @param referencedTables view/materialized viewが参照するテーブル（ビューを含む）のリスト。項目の追加で既存の項目の並びが変わらないよう末尾に置く
 */
public record TableSnapshot(
    String schema,
    String name,
    String logicalName,
    String type,
    String description,
    String remarks,
    String definition,
    String partitionKey,
    List<Column> columns,
    List<Index> indexes,
    List<Constraint> constraints,
    List<Relation> foreignKeys,
    List<Relation> logicalRelations,
    List<Trigger> triggers,
    List<ViewReference> referencedTables) {

  /** テーブル定義書1ファイル分の内容からスナップショットを生成する */
  public static TableSnapshot of(TableDefinitionContent content) {
    final TableEntity table = content.table();
    final TableAnnotation annotation = content.annotation();
    return new TableSnapshot(
        table.schemaName(),
        table.physicalTableName(),
        text(table.logicalTableName()),
        table.tableType().getName(),
        text(annotation.description()),
        text(annotation.remarks()),
        text(table.definition()),
        text(table.partitionKey()),
        content.columns().stream().map(column -> Column.of(column, annotation)).toList(),
        content.indexes().stream().map(Index::of).toList(),
        content.constraints().stream().map(Constraint::of).toList(),
        content.foreignKeys().stream().map(Relation::of).toList(),
        content.logicalRelations().stream().map(Relation::of).toList(),
        content.triggers().stream().map(Trigger::of).toList(),
        content.referencedTables().stream().map(ViewReference::of).toList());
  }

  /**
   * ビューが参照するテーブル
   *
   * @param type 区分（table/view/materialized_view）
   */
  public record ViewReference(String schema, String name, String type) {

    /** ビューの参照から生成する */
    static ViewReference of(ViewReferenceEntity reference) {
      return new ViewReference(
          reference.referenceSchemaName(),
          reference.referenceTableName(),
          reference.referenceTableType().getName());
    }
  }

  /**
   * カラム情報
   *
   * @param remarks カラム備考（サイドカーYAML由来）
   */
  public record Column(
      String name,
      String logicalName,
      String type,
      String precisionScale,
      boolean primaryKey,
      boolean notNull,
      String defaultValue,
      String remarks) {

    /** カラム情報に、サイドカー由来のカラム備考を合わせて生成する */
    static Column of(ColumnEntity column, TableAnnotation annotation) {
      return new Column(
          column.physicalColumnName(),
          text(column.logicalColumnName()),
          column.columnType(),
          text(column.precisionScale()),
          column.primaryKey(),
          column.notNull(),
          text(column.defaultValue()),
          text(annotation.columnRemark(column.physicalColumnName())));
    }
  }

  /** インデックス情報 */
  public record Index(
      String name,
      String method,
      boolean unique,
      boolean primary,
      String definition,
      String remarks) {

    /** インデックス情報から生成する */
    static Index of(IndexEntity index) {
      return new Index(
          index.indexName(),
          text(index.indexMethod()),
          index.isUnique(),
          index.isPrimary(),
          text(index.indexDefinition()),
          text(index.remarks()));
    }
  }

  /**
   * 制約情報
   *
   * @param type 制約種別（CHECK/FOREIGN KEY/PRIMARY KEY/UNIQUE）
   */
  public record Constraint(String name, String type, String definition, String remarks) {

    /** 制約情報から生成する */
    static Constraint of(ConstraintEntity constraint) {
      return new Constraint(
          constraint.constraintName(),
          text(constraint.constraintType()),
          text(constraint.constraintDefinition()),
          text(constraint.remarks()));
    }
  }

  /**
   * 外部キー・論理リレーション情報（自テーブル → 参照先）
   *
   * @param name 外部キー名（論理リレーションの場合は関連名）
   */
  public record Relation(
      String name,
      List<String> columns,
      String referenceSchema,
      String referenceTable,
      List<String> referenceColumns,
      Cardinality cardinality) {

    /** 外部キー・論理リレーションから生成する */
    static Relation of(ForeignKeyEntity foreignKey) {
      return new Relation(
          foreignKey.foreignKeyName(),
          foreignKey.columnNames(),
          foreignKey.referenceSchemaName(),
          foreignKey.referenceTableName(),
          foreignKey.referenceColumnNames(),
          foreignKey.cardinality());
    }
  }

  /**
   * トリガー情報
   *
   * @param timing 実行タイミング（BEFORE/AFTER/INSTEAD OF）
   * @param events 対象イベント（INSERT/UPDATE/DELETE/TRUNCATE）のリスト
   * @param orientation 実行単位（ROW/STATEMENT）
   * @param function 実行される関数名（スキーマ修飾）
   * @param body 本体（Oracleのみ。PostgreSQLは本体を実行する関数が持つため空文字）
   */
  public record Trigger(
      String name,
      String timing,
      List<String> events,
      String orientation,
      String function,
      String definition,
      String body) {

    /** トリガー情報から生成する */
    static Trigger of(TriggerEntity trigger) {
      return new Trigger(
          trigger.triggerName(),
          text(trigger.timing()),
          trigger.events(),
          text(trigger.orientation()),
          text(trigger.functionName()),
          text(trigger.triggerDefinition()),
          text(trigger.body()));
    }
  }
}
