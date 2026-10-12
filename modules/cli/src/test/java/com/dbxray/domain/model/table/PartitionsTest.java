package com.dbxray.domain.model.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Partitions のパーティション表（根）のテーブルキーによるインデックス化・取得順の保持に関するテスト */
public class PartitionsTest {

  private PartitionEntity partition(String schema, String root, String name) {
    return new PartitionEntity(schema, root, schema, name, schema, root, "DEFAULT", "");
  }

  private TableEntity table(String schema, String name) {
    return new TableEntity("testdb", schema, "", name, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("belongingTo: 自テーブルのパーティションのみを、渡した順（階層順）を保って返す")
  void testBelongingToReturnsOwnPartitionsInOrder() {
    var p1 = partition("public", "sales", "sales_2026_01");
    var p2 = partition("public", "sales", "sales_2026_03");
    var p3 = partition("public", "sales", "sales_2026_03_a");
    var other = partition("public", "logs", "logs_2026_01");
    var partitions = Partitions.of(List.of(p1, other, p2, p3));

    assertEquals(List.of(p1, p2, p3), partitions.belongingTo(table("public", "sales")));
  }

  @Test
  @DisplayName("belongingTo: パーティションを持たないテーブルには空リストを返す")
  void testBelongingToReturnsEmptyForNonPartitionedTable() {
    var partitions = Partitions.of(List.of(partition("public", "sales", "sales_2026_01")));

    assertEquals(List.of(), partitions.belongingTo(table("public", "customer")));
  }

  @Test
  @DisplayName("belongingTo: 同名のパーティション表でもスキーマが異なれば別のキーとして扱う")
  void testBelongingToDistinguishesSameNameAcrossSchemas() {
    var publicPartition = partition("public", "sales", "sales_2026_01");
    var archivePartition = partition("archive", "sales", "sales_2020");
    var partitions = Partitions.of(List.of(publicPartition, archivePartition));

    assertEquals(List.of(publicPartition), partitions.belongingTo(table("public", "sales")));
    assertEquals(List.of(archivePartition), partitions.belongingTo(table("archive", "sales")));
  }
}
