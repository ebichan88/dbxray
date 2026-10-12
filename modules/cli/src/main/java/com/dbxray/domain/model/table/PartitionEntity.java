package com.dbxray.domain.model.table;

/**
 * パーティション表の下位のパーティション1件に関するrecordクラス<br>
 * 多段パーティションの中間のパーティション表も1件として持つ。{@link SchemaTableKeyed}としてのスキーマ名・テーブル名は、
 * このパーティションが属するパーティション表（根）を指す
 *
 * @param partitionSchemaName このパーティションのスキーマ名（根と異なるスキーマに置かれることがある）
 * @param parentSchemaName 直接の親（根、または多段パーティションの中間のパーティション表）のスキーマ名
 * @param bound パーティション境界（例: {@code FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')}、DEFAULTパーティションは{@code DEFAULT}）
 * @param partitionKey このパーティション自身がパーティション表である場合のパーティションキー（末端のパーティションは空文字）
 */
public record PartitionEntity(
    String schemaName,
    String tableName,
    String partitionSchemaName,
    String partitionName,
    String parentSchemaName,
    String parentName,
    String bound,
    String partitionKey)
    implements SchemaTableKeyed {

  /**
   * パーティションの名称を、根のスキーマからの相対で取得するメソッド
   *
   * @return 根と同じスキーマなら名前のみ、異なるスキーマなら {@code スキーマ.名前}
   */
  public String getDisplayName() {
    return relativeName(partitionSchemaName, partitionName);
  }

  /**
   * 直接の親の名称を、根のスキーマからの相対で取得するメソッド
   *
   * @return 根と同じスキーマなら名前のみ、異なるスキーマなら {@code スキーマ.名前}
   */
  public String getDisplayParentName() {
    return relativeName(parentSchemaName, parentName);
  }

  private String relativeName(String schema, String name) {
    return schema.equals(schemaName) ? name : TableKey.of(schema, name).qualifiedName();
  }
}
