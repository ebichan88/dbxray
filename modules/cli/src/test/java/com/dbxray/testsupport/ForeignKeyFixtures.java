package com.dbxray.testsupport;

import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.table.TableKey;
import java.util.List;

/**
 * テストで用いる{@link ForeignKeyEntity}の生成ヘルパー<br>
 * 本番コードでは外部キーのカラムリストをSQLから、論理リレーションのそれをサイドカーYAMLから受け取るが、大半のテストはカラムリストを参照しない。ここで既定値を与えることで、
 * テスト側の関心（スキーマ・テーブル・多重度・由来）だけを記述できるようにする
 */
public final class ForeignKeyFixtures {

  /** テストが値を参照しないことを示すプレースホルダ（カラムリスト） */
  private static final List<String> UNUSED = List.of("unused");

  private ForeignKeyFixtures() {}

  /**
   * 多重度を既定（1対多）とする物理外部キーを生成する
   *
   * @param schema 参照元スキーマ名
   * @param table 参照元テーブル名
   * @param name 外部キー名
   * @param refSchema 参照先スキーマ名
   * @param refTable 参照先テーブル名
   * @return 物理外部キー
   */
  public static ForeignKeyEntity physical(
      String schema, String table, String name, String refSchema, String refTable) {
    return physical(schema, table, name, refSchema, refTable, Cardinality.ONE_TO_MANY);
  }

  /**
   * 多重度を指定して物理外部キーを生成する
   *
   * @param schema 参照元スキーマ名
   * @param table 参照元テーブル名
   * @param name 外部キー名
   * @param refSchema 参照先スキーマ名
   * @param refTable 参照先テーブル名
   * @param cardinality 多重度
   * @return 物理外部キー
   */
  public static ForeignKeyEntity physical(
      String schema,
      String table,
      String name,
      String refSchema,
      String refTable,
      Cardinality cardinality) {
    return new ForeignKeyEntity(
        schema,
        table,
        name,
        UNUSED,
        refSchema,
        refTable,
        UNUSED,
        cardinality,
        RelationType.PHYSICAL);
  }

  /**
   * 多重度を既定（1対多）とする論理リレーションを生成する
   *
   * @param schema 参照元スキーマ名
   * @param table 参照元テーブル名
   * @param name 関連名
   * @param refSchema 参照先スキーマ名
   * @param refTable 参照先テーブル名
   * @return 論理リレーション
   */
  public static ForeignKeyEntity logical(
      String schema, String table, String name, String refSchema, String refTable) {
    return logical(
        schema, table, name, UNUSED, refSchema, refTable, UNUSED, Cardinality.ONE_TO_MANY);
  }

  /**
   * カラムリスト・多重度を指定して論理リレーションを生成する
   *
   * @param schema 参照元スキーマ名
   * @param table 参照元テーブル名
   * @param name 関連名
   * @param columns 参照元のカラムリスト
   * @param refSchema 参照先スキーマ名
   * @param refTable 参照先テーブル名
   * @param refColumns 参照先のカラムリスト
   * @param cardinality 多重度
   * @return 論理リレーション
   */
  public static ForeignKeyEntity logical(
      String schema,
      String table,
      String name,
      List<String> columns,
      String refSchema,
      String refTable,
      List<String> refColumns,
      Cardinality cardinality) {
    return ForeignKeyEntity.logical(
        TableKey.of(schema, table),
        name,
        columns,
        TableKey.of(refSchema, refTable),
        refColumns,
        cardinality);
  }
}
