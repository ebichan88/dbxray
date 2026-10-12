package com.dbxray.domain.model.relation;

import com.dbxray.domain.model.table.SchemaTableKeyed;
import com.dbxray.domain.model.table.TableKey;
import java.util.List;

/**
 * 外部キー情報に関するrecordクラス<br>
 * DBに実在する外部キー制約（{@link RelationType#PHYSICAL}）と、
 * サイドカーYAMLで宣言された論理リレーション（{@link RelationType#LOGICAL}）の双方を表す。
 * 両者はER図では同じグラフ上に描画されるが、掲載セクションと線種によって区別される
 *
 * @param schemaName 参照元（子）スキーマ名
 * @param tableName 参照元（子）テーブル名
 * @param foreignKeyName 外部キー名（論理リレーションの場合は関連名）
 * @param columnNames 参照元（子）の列名のリスト（外部キーの定義順）
 * @param referenceSchemaName 参照先（親）スキーマ名
 * @param referenceTableName 参照先（親）テーブル名
 * @param referenceColumnNames 参照先（親）の列名のリスト（{@code columnNames}と同じ順）
 * @param relationType 関連の由来（物理／論理）
 */
public record ForeignKeyEntity(
    String schemaName,
    String tableName,
    String foreignKeyName,
    List<String> columnNames,
    String referenceSchemaName,
    String referenceTableName,
    List<String> referenceColumnNames,
    Cardinality cardinality,
    RelationType relationType)
    implements SchemaTableKeyed {

  /** 列名のリストは変更不可な複製として保持する */
  public ForeignKeyEntity {
    columnNames = List.copyOf(columnNames);
    referenceColumnNames = List.copyOf(referenceColumnNames);
  }

  /**
   * サイドカーYAML由来の論理リレーションを生成する静的ファクトリメソッド<br>
   * DBに外部キー制約が存在しないため、多重度は機械的に判定できない。
   * 呼び出し側でYAMLの明示指定を解決した上で渡すこと
   *
   * @param table 参照元（子）テーブル
   * @param columnNames 参照元（子）の列名のリスト
   * @param referenceTable 参照先（親）テーブル
   * @param referenceColumnNames 参照先（親）の列名のリスト
   * @return 論理リレーションを表すForeignKeyEntity
   */
  public static ForeignKeyEntity logical(
      TableKey table,
      String relationName,
      List<String> columnNames,
      TableKey referenceTable,
      List<String> referenceColumnNames,
      Cardinality cardinality) {
    return new ForeignKeyEntity(
        table.schema(),
        table.table(),
        relationName,
        columnNames,
        referenceTable.schema(),
        referenceTable.table(),
        referenceColumnNames,
        cardinality,
        RelationType.LOGICAL);
  }

  /**
   * サイドカーYAMLで宣言された論理リレーションの関連名を生成する静的メソッド<br>
   * 参照元（子）の列名をカンマで連結した名称とする（テーブル定義書のカラムリスト表示と同じ区切り方に揃えている）
   *
   * @param childColumnNames 参照元（子）の列名のリスト
   */
  public static String resolveLogicalRelationName(List<String> childColumnNames) {
    return String.join(",", childColumnNames);
  }

  /**
   * 参照先の スキーマ.テーブル 形式の名称を取得するメソッド
   *
   * @return 参照先の スキーマ.テーブル 形式の名称
   */
  public String getReferenceSchemaTableName() {
    return referenceTableKey().qualifiedName();
  }

  /** 参照先（親）テーブルのテーブルキーを取得するメソッド */
  public TableKey referenceTableKey() {
    return TableKey.of(referenceSchemaName, referenceTableName);
  }

  /**
   * サイドカーYAML由来の論理リレーションか判定するメソッド
   *
   * @return 論理リレーションの場合はtrue
   */
  public boolean isLogical() {
    return relationType == RelationType.LOGICAL;
  }
}
