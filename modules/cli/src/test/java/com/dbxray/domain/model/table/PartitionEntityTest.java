package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PartitionEntity の表示名（パーティション表のスキーマからの相対名）に関するテスト */
public class PartitionEntityTest {

  private PartitionEntity partition(
      String partitionSchema, String partitionName, String parentSchema, String parentName) {
    return new PartitionEntity(
        "public", "sales", partitionSchema, partitionName, parentSchema, parentName, "DEFAULT", "");
  }

  @Test
  @DisplayName("displayName: パーティション表と同じスキーマのパーティションは名前のみで表す")
  void testDisplayNameInSameSchema() {
    assertEquals(
        "sales_2026_01", partition("public", "sales_2026_01", "public", "sales").displayName());
  }

  @Test
  @DisplayName("displayName: パーティション表と異なるスキーマのパーティションはスキーマ修飾で表す")
  void testDisplayNameInOtherSchema() {
    assertEquals(
        "archive.sales_2020", partition("archive", "sales_2020", "public", "sales").displayName());
  }

  @Test
  @DisplayName("displayParentName: 親も、パーティション表と同じスキーマなら名前のみ、異なるスキーマならスキーマ修飾で表す")
  void testDisplayParentName() {
    assertEquals(
        "sales_2026_03",
        partition("public", "sales_2026_03_a", "public", "sales_2026_03").displayParentName());
    assertEquals(
        "archive.sales_2020",
        partition("archive", "sales_2020_q1", "archive", "sales_2020").displayParentName());
  }

  @Test
  @DisplayName("tableKey: 所属するパーティション表（根）のキーを返す")
  void testTableKeyIsRoot() {
    assertEquals(
        TableKey.of("public", "sales"),
        partition("archive", "sales_2020", "public", "sales").tableKey());
  }
}
