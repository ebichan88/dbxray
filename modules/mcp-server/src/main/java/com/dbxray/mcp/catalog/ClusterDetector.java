package com.dbxray.mcp.catalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 関連（外部キー・論理リレーション）のつながりだけから、テーブルのまとまりを求めるクラス<br>
 * 関連でつながるテーブル（連結成分）を1つのまとまりとし、上限を超えるまとまりは、被参照の最も多いテーブルを共通のテーブル（ハブ）として
 * 除いて分け直すことを繰り返す。大きなDBでは共通のマスタを介して大半のテーブルが1つにつながるため。
 * 結果は範囲内のテーブルの集合だけで決まり、同じスナップショットからは常に同じまとまりを返す
 */
final class ClusterDetector {

  private final RelationGraph graph;
  private final Comparator<TableEntry> byIncomingThenKey;
  private final Map<ObjectKey, TableEntry> tables = new LinkedHashMap<>();
  private final Map<ObjectKey, Set<ObjectKey>> adjacency = new LinkedHashMap<>();

  /**
   * @param tables まとまりを求める範囲のテーブル（範囲外のテーブルとの関連は無いものとみなす）
   */
  ClusterDetector(List<TableEntry> tables, RelationGraph graph) {
    this.graph = graph;
    this.byIncomingThenKey =
        Comparator.comparingInt((TableEntry table) -> incoming(table.key()))
            .reversed()
            .thenComparing(TableEntry::key, ObjectKey.ORDER);
    tables.forEach(table -> this.tables.put(table.key(), table));
    for (final ObjectKey key : this.tables.keySet()) {
      final Set<ObjectKey> neighbors = new HashSet<>(graph.adjacentTables(key));
      neighbors.retainAll(this.tables.keySet());
      adjacency.put(key, neighbors);
    }
  }

  /**
   * まとまりを求めるメソッド
   *
   * @param maxClusterSize まとまりのテーブル数の上限（2以上）。ハブを除いても分けられない場合は超えることがある
   */
  TableClusters detect(int maxClusterSize) {
    final Set<ObjectKey> related = new HashSet<>();
    adjacency.forEach(
        (key, neighbors) -> {
          if (!neighbors.isEmpty()) {
            related.add(key);
          }
        });
    final Set<ObjectKey> hubs = new HashSet<>();
    final List<Set<ObjectKey>> components = new ArrayList<>();
    final Deque<Set<ObjectKey>> pending = new ArrayDeque<>(connectedComponents(related));
    while (!pending.isEmpty()) {
      final Set<ObjectKey> component = pending.poll();
      final TableEntry hub =
          component.stream().map(tables::get).min(byIncomingThenKey).orElseThrow();
      if (component.size() <= maxClusterSize
          || incoming(hub.key()) < TableClusters.HUB_MIN_INCOMING) {
        components.add(component);
        continue;
      }
      hubs.add(hub.key());
      final Set<ObjectKey> rest = new HashSet<>(component);
      rest.remove(hub.key());
      pending.addAll(connectedComponents(rest));
    }
    final List<TableCluster> clusters =
        groupHubOnlyTables(components, hubs).stream()
            .map(members -> toCluster(members, hubs))
            .sorted(
                Comparator.comparingInt((TableCluster cluster) -> cluster.tables().size())
                    .reversed()
                    .thenComparing(TableCluster::representative, byIncomingThenKey))
            .toList();
    return new TableClusters(
        clusters, sorted(hubs), (int) adjacency.values().stream().filter(Set::isEmpty).count());
  }

  /**
   * ハブを除いて孤立したテーブル（関連がハブとの間にしか無いテーブル）を、被参照の最も多いハブごとに1つのまとまりへ寄せる<br>
   * 1テーブルずつのまとまりとして返すより、同じマスタだけを参照するテーブル群として示す方が読み解きの手がかりになるため
   */
  private List<Set<ObjectKey>> groupHubOnlyTables(
      List<Set<ObjectKey>> components, Set<ObjectKey> hubs) {
    final List<Set<ObjectKey>> grouped = new ArrayList<>();
    final Map<ObjectKey, Set<ObjectKey>> byHub =
        new TreeMap<>(Comparator.comparing(tables::get, byIncomingThenKey));
    for (final Set<ObjectKey> component : components) {
      if (component.size() > 1) {
        grouped.add(component);
        continue;
      }
      final ObjectKey table = component.iterator().next();
      final ObjectKey primaryHub = sorted(adjacentHubs(Set.of(table), hubs)).get(0).key();
      byHub.computeIfAbsent(primaryHub, key -> new HashSet<>()).add(table);
    }
    grouped.addAll(byHub.values());
    return grouped;
  }

  private TableCluster toCluster(Set<ObjectKey> members, Set<ObjectKey> hubs) {
    return new TableCluster(sorted(members), sorted(adjacentHubs(members, hubs)));
  }

  private Set<ObjectKey> adjacentHubs(Set<ObjectKey> members, Set<ObjectKey> hubs) {
    final Set<ObjectKey> adjacent = new HashSet<>();
    for (final ObjectKey member : members) {
      for (final ObjectKey neighbor : adjacency.get(member)) {
        if (hubs.contains(neighbor)) {
          adjacent.add(neighbor);
        }
      }
    }
    return adjacent;
  }

  /** 指定したテーブルの中だけで関連をたどり、つながるテーブルの組に分ける */
  private List<Set<ObjectKey>> connectedComponents(Set<ObjectKey> nodes) {
    final List<Set<ObjectKey>> components = new ArrayList<>();
    final Set<ObjectKey> visited = new HashSet<>();
    for (final ObjectKey start : nodes) {
      if (!visited.add(start)) {
        continue;
      }
      final Set<ObjectKey> component = new HashSet<>(List.of(start));
      final Deque<ObjectKey> frontier = new ArrayDeque<>(List.of(start));
      while (!frontier.isEmpty()) {
        for (final ObjectKey neighbor : adjacency.get(frontier.poll())) {
          if (nodes.contains(neighbor) && visited.add(neighbor)) {
            component.add(neighbor);
            frontier.add(neighbor);
          }
        }
      }
      components.add(component);
    }
    return components;
  }

  private List<TableEntry> sorted(Set<ObjectKey> keys) {
    return keys.stream().map(tables::get).sorted(byIncomingThenKey).toList();
  }

  private int incoming(ObjectKey table) {
    return graph.counts(table).incoming();
  }
}
