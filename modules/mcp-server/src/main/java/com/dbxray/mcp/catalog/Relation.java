package com.dbxray.mcp.catalog;

import java.util.List;
import java.util.Set;

/**
 * テーブル間の1つの関連（参照元 → 参照先）
 *
 * @param from 参照元（外部キー・論理リレーションを持つ側）
 * @param to 参照先。スナップショットに含まれないテーブルの場合もある
 * @param cardinality 多重度（{@code ONE_TO_MANY}等）。参照先（親）から見た参照元（子）の数を表す。未設定の場合は空文字
 * @param name 外部キー名（論理リレーションの場合は関連名）
 */
public record Relation(
    ObjectKey from,
    List<String> fromColumns,
    ObjectKey to,
    List<String> toColumns,
    RelationKind kind,
    String cardinality,
    String name) {

  private static final Set<String> ONE_TO_ONE_CARDINALITIES =
      Set.of("ONE_TO_ONE", "OPTIONAL_ONE_TO_ONE");

  /**
   * 参照先1行に対して参照元が高々1行の関連か判定するメソッド
   *
   * @return 多重度が{@code ONE_TO_ONE}・{@code OPTIONAL_ONE_TO_ONE}の場合はtrue。未設定の場合はfalse
   */
  public boolean isOneToOne() {
    return ONE_TO_ONE_CARDINALITIES.contains(cardinality);
  }

  /**
   * 関連の、指定したテーブルとは反対側のテーブルを返すメソッド
   *
   * @param side 関連の参照元または参照先。自己参照の場合はそのテーブル自身を返す
   */
  public ObjectKey otherSide(ObjectKey side) {
    return from.equals(side) ? to : from;
  }

  /** テーブルが持つ外部キー・論理リレーションから組み立てるメソッド */
  static Relation of(ObjectKey from, RelationEntry entry, RelationKind kind) {
    final String referenceSchema =
        entry.referenceSchema().isEmpty() ? from.schema() : entry.referenceSchema();
    return new Relation(
        from,
        entry.columns(),
        new ObjectKey(from.database(), referenceSchema, entry.referenceTable()),
        entry.referenceColumns(),
        kind,
        entry.cardinality(),
        entry.name());
  }
}
