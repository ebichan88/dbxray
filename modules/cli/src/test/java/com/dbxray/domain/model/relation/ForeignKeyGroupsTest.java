package com.dbxray.domain.model.relation;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.testsupport.ForeignKeyFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ForeignKeyGroups の連結成分分解・グループへのまとめ直しに関するテスト */
public class ForeignKeyGroupsTest {

  private ForeignKeyEntity fk(
      String schema, String table, String name, String refSchema, String refTable) {
    return ForeignKeyFixtures.physical(schema, table, name, refSchema, refTable);
  }

  @Test
  @DisplayName("connectedComponents: 外部キーがない場合は空のリストを返す")
  void testEmpty() {
    assertEquals(List.of(), ForeignKeyGroups.connectedComponents(List.of()));
  }

  @Test
  @DisplayName("connectedComponents: 繋がっていない2つのまとまりは別の成分になる")
  void testSeparateComponents() {
    var fk1 = fk("public", "orders", "fk1", "public", "customers");
    var fk2 = fk("public", "logs", "fk2", "public", "users");
    var components = ForeignKeyGroups.connectedComponents(List.of(fk1, fk2));
    assertEquals(2, components.size());
    // いずれの成分も2テーブル・外部キー1本
    assertTrue(components.stream().allMatch(component -> component.foreignKeys().size() == 1));
    assertTrue(components.stream().allMatch(component -> component.nodeCount() == 2));
  }

  @Test
  @DisplayName("connectedComponents: a→b と b→c は1つの成分に併合される")
  void testChainedComponent() {
    var fk1 = fk("public", "a", "fk1", "public", "b");
    var fk2 = fk("public", "b", "fk2", "public", "c");
    var components = ForeignKeyGroups.connectedComponents(List.of(fk1, fk2));
    assertEquals(1, components.size());
    assertEquals(2, components.get(0).foreignKeys().size());
    assertEquals(3, components.get(0).nodeCount());
  }

  @Test
  @DisplayName("connectedComponents: 共通の参照先を持つ外部キーは1つの成分に併合される")
  void testSharedReferenceComponent() {
    var fk1 = fk("public", "a", "fk1", "public", "hub");
    var fk2 = fk("public", "b", "fk2", "public", "hub");
    var fk3 = fk("public", "c", "fk3", "public", "hub");
    var components = ForeignKeyGroups.connectedComponents(List.of(fk1, fk2, fk3));
    assertEquals(1, components.size());
    assertEquals(4, components.get(0).nodeCount());
  }

  @Test
  @DisplayName("connectedComponents: 自己参照は1テーブルの成分になる")
  void testSelfReference() {
    var selfFk = fk("public", "categories", "fk_parent", "public", "categories");
    var components = ForeignKeyGroups.connectedComponents(List.of(selfFk));
    assertEquals(1, components.size());
    assertEquals(1, components.get(0).nodeCount());
  }

  @Test
  @DisplayName("connectedComponents: スキーマを跨ぐ外部キーは成分を跨いで併合する")
  void testCrossSchemaComponent() {
    var fk1 = fk("sales", "orders", "fk1", "master", "customers");
    var fk2 = fk("sales", "items", "fk2", "sales", "orders");
    var components = ForeignKeyGroups.connectedComponents(List.of(fk1, fk2));
    assertEquals(1, components.size());
    assertEquals(3, components.get(0).nodeCount());
  }

  @Test
  @DisplayName("connectedComponents: ノード数の降順で返す")
  void testOrderedByNodeCountDesc() {
    var small = fk("public", "x", "fk_small", "public", "y");
    var large1 = fk("public", "a", "fk_large1", "public", "b");
    var large2 = fk("public", "b", "fk_large2", "public", "c");
    var components = ForeignKeyGroups.connectedComponents(List.of(small, large1, large2));
    assertEquals(2, components.size());
    assertEquals(3, components.get(0).nodeCount());
    assertEquals(2, components.get(1).nodeCount());
  }

  @Test
  @DisplayName("connectedComponents: ノード数が同じ場合は先頭テーブル名順で安定して並ぶ")
  void testStableOrderForSameSize() {
    var zz = fk("public", "zz1", "fk_zz", "public", "zz2");
    var aa = fk("public", "aa1", "fk_aa", "public", "aa2");
    var components = ForeignKeyGroups.connectedComponents(List.of(zz, aa));
    assertEquals("fk_aa", components.get(0).foreignKeys().get(0).foreignKeyName());
    assertEquals("fk_zz", components.get(1).foreignKeys().get(0).foreignKeyName());
  }

  @Test
  @DisplayName("pack: ノード数の上限に収まる限り、複数の成分を1つのグループにまとめる")
  void testPackMergesComponentsWithinLimit() {
    var large =
        List.of(fk("public", "a", "fk1", "public", "b"), fk("public", "b", "fk2", "public", "c"));
    var small1 = List.of(fk("public", "x", "fk3", "public", "y"));
    var small2 = List.of(fk("public", "p", "fk4", "public", "q"));
    var groups =
        ForeignKeyGroups.pack(
            List.of(
                ForeignKeyGroup.of(large), ForeignKeyGroup.of(small1), ForeignKeyGroup.of(small2)),
            NodeLimit.of(5));
    // 3ノード + 2ノード で上限5に収まるため1つ目にまとまり、残りの2ノードは次のグループになる
    assertEquals(2, groups.size());
    assertEquals(5, groups.get(0).nodeCount());
    assertEquals(
        List.of("fk1", "fk2", "fk3"),
        groups.get(0).foreignKeys().stream().map(ForeignKeyEntity::foreignKeyName).toList());
    assertEquals(2, groups.get(1).nodeCount());
  }

  @Test
  @DisplayName("pack: 後の成分は、最後のグループだけでなく、収まる最初のグループへ戻って詰める")
  void testPackFillsEarliestGroupThatFits() {
    // 4ノード・3ノード・2ノードの成分を上限6で詰める。3ノードは4ノードのグループに収まらず2つ目のグループになるが、
    // 2ノードは1つ目のグループ（4+2=6）に収まるため、最後のグループ（3ノード）ではなく1つ目へ戻って詰める
    var four =
        List.of(
            fk("public", "a", "fk_a", "public", "b"),
            fk("public", "b", "fk_b", "public", "c"),
            fk("public", "c", "fk_c", "public", "d"));
    var three =
        List.of(fk("public", "p", "fk_p", "public", "q"), fk("public", "q", "fk_q", "public", "r"));
    var two = List.of(fk("public", "x", "fk_x", "public", "y"));

    var groups =
        ForeignKeyGroups.pack(
            List.of(ForeignKeyGroup.of(four), ForeignKeyGroup.of(three), ForeignKeyGroup.of(two)),
            NodeLimit.of(6));

    assertEquals(
        List.of(List.of("fk_a", "fk_b", "fk_c", "fk_x"), List.of("fk_p", "fk_q")),
        groups.stream()
            .map(
                group ->
                    group.foreignKeys().stream().map(ForeignKeyEntity::foreignKeyName).toList())
            .toList());
  }

  @Test
  @DisplayName("pack: 単独で上限を超える成分はそれ単独のグループになる")
  void testPackKeepsOversizedComponentAlone() {
    var oversized =
        List.of(fk("public", "a", "fk1", "public", "b"), fk("public", "b", "fk2", "public", "c"));
    var small = List.of(fk("public", "x", "fk3", "public", "y"));
    var groups =
        ForeignKeyGroups.pack(
            List.of(ForeignKeyGroup.of(oversized), ForeignKeyGroup.of(small)), NodeLimit.of(2));
    assertEquals(2, groups.size());
    assertEquals(3, groups.get(0).nodeCount());
    assertEquals(2, groups.get(1).nodeCount());
  }

  @Test
  @DisplayName("compose: 外部キーが無い場合は空のグループ1枚のSingleを返す")
  void testComposeEmptyReturnsSingle() {
    var composition = ForeignKeyGroups.compose(List.of(), NodeLimit.of(4));
    assertInstanceOf(ForeignKeyGroups.PageComposition.Single.class, composition);
    var single = (ForeignKeyGroups.PageComposition.Single) composition;
    assertEquals(0, single.group().nodeCount());
  }

  @Test
  @DisplayName("compose: ノード数が上限内の場合は分割せずSingleを返す")
  void testComposeWithinLimitReturnsSingle() {
    var fk1 = fk("public", "a", "fk1", "public", "hub");
    var fk2 = fk("public", "b", "fk2", "public", "hub");
    var composition = ForeignKeyGroups.compose(List.of(fk1, fk2), NodeLimit.of(80));
    assertInstanceOf(ForeignKeyGroups.PageComposition.Single.class, composition);
    var single = (ForeignKeyGroups.PageComposition.Single) composition;
    assertEquals(3, single.group().nodeCount());
    assertEquals(List.of(fk1, fk2), single.group().foreignKeys());
  }

  @Test
  @DisplayName("compose: 上限超過かつ独立したまとまりが複数ある場合はGroupedを返す")
  void testComposeOverflowWithMultipleComponentsReturnsGrouped() {
    // 2ノードずつの独立したまとまりを5組（ノード数10件）作り、上限4件で超過させる
    var foreignKeys =
        IntStream.rangeClosed(1, 5)
            .mapToObj(i -> fk("public", "child" + i, "fk" + i, "public", "parent" + i))
            .toList();

    var composition = ForeignKeyGroups.compose(foreignKeys, NodeLimit.of(4));

    assertInstanceOf(ForeignKeyGroups.PageComposition.Grouped.class, composition);
    var grouped = (ForeignKeyGroups.PageComposition.Grouped) composition;
    assertEquals(10, grouped.nodeCount());
    // 1グループあたり2まとまり(4ノード)まで詰め込まれるため、5まとまりは3グループになる
    assertEquals(3, grouped.groups().size());
    assertTrue(grouped.groups().stream().allMatch(g -> g.nodeCount() <= 4));
  }

  @Test
  @DisplayName("compose: 上限超過でも単一の巨大なまとまりしかない場合は分割してもSingleのまま（フォールバックはWriter側の役目）")
  void testComposeOverflowWithSingleComponentReturnsSingle() {
    // 全テーブルが1つのハブに繋がる構成のため、分割しても1つのまとまりにしかならない
    var foreignKeys =
        IntStream.rangeClosed(1, 10)
            .mapToObj(i -> fk("public", "t" + i, "fk" + i, "public", "hub"))
            .toList();

    var composition = ForeignKeyGroups.compose(foreignKeys, NodeLimit.of(4));

    assertInstanceOf(ForeignKeyGroups.PageComposition.Single.class, composition);
    var single = (ForeignKeyGroups.PageComposition.Single) composition;
    // 上限を超えたままであることが、呼び出し側が外部キー一覧へフォールバックする判断材料になる
    assertTrue(single.group().exceeds(NodeLimit.of(4)));
    assertEquals(11, single.group().nodeCount());
  }

  @Test
  @DisplayName("compose: 巨大なまとまりと小さなまとまりが混在する場合はGroupedを返し、各グループのノード数を保つ")
  void testComposeMixedComponentsReturnsGrouped() {
    var foreignKeys = new ArrayList<ForeignKeyEntity>();
    // 上限を超える巨大なまとまり（11ノード）
    IntStream.rangeClosed(1, 10)
        .forEach(i -> foreignKeys.add(fk("public", "t" + i, "fk" + i, "public", "hub")));
    // 巨大なまとまりに繋がっていない小さなまとまり
    foreignKeys.add(fk("public", "x", "fk_xy", "public", "y"));

    var composition = ForeignKeyGroups.compose(foreignKeys, NodeLimit.of(4));

    assertInstanceOf(ForeignKeyGroups.PageComposition.Grouped.class, composition);
    var grouped = (ForeignKeyGroups.PageComposition.Grouped) composition;
    assertEquals(13, grouped.nodeCount());
    assertEquals(2, grouped.groups().size());
    // 巨大なまとまりは単独グループのまま上限を超え続ける（Writer側で外部キー一覧へフォールバックする対象）
    assertEquals(11, grouped.groups().get(0).nodeCount());
    assertTrue(grouped.groups().get(0).exceeds(NodeLimit.of(4)));
    // 小さなまとまりは上限内に収まる
    assertEquals(2, grouped.groups().get(1).nodeCount());
    assertFalse(grouped.groups().get(1).exceeds(NodeLimit.of(4)));
  }

  @Test
  @DisplayName("compose: 上限なし（0以下）の場合はテーブル数に関わらずSingleを返す")
  void testComposeNoLimitReturnsSingle() {
    var foreignKeys =
        IntStream.rangeClosed(1, 50)
            .mapToObj(i -> fk("public", "t" + i, "fk" + i, "public", "hub"))
            .toList();

    var composition = ForeignKeyGroups.compose(foreignKeys, NodeLimit.of(0));

    assertInstanceOf(ForeignKeyGroups.PageComposition.Single.class, composition);
  }
}
