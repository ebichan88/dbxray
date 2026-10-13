package com.dbxray.mcp.catalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 中間テーブルの判定結果と、中間テーブルを介した多対多の関係を引く窓口<br>
 * 判定は組み立て時に全テーブルについて1回だけ行う。スナップショットに無い親テーブルは、多対多の相手にしない
 */
public final class JunctionTables {

  private static final Comparator<ManyToMany> BY_TABLE_THEN_VIA =
      Comparator.comparing(ManyToMany::table, ObjectKey.ORDER)
          .thenComparing(ManyToMany::via, ObjectKey.ORDER);

  private final Map<ObjectKey, JunctionTable> junctions = new HashMap<>();
  private final Map<ObjectKey, List<ManyToMany>> manyToMany = new HashMap<>();

  JunctionTables(List<TableEntry> tables, RelationGraph relations) {
    for (final TableEntry table : tables) {
      JunctionDetector.detect(table, relations.outgoing(table.key()))
          .ifPresent(junction -> junctions.put(junction.key(), junction));
    }
    final Set<ObjectKey> existing =
        tables.stream().map(TableEntry::key).collect(Collectors.toSet());
    collectPartners(existing).forEach((owner, found) -> manyToMany.put(owner, List.copyOf(found)));
  }

  /**
   * テーブルが中間テーブルの場合に、その構成を返すメソッド
   *
   * @return 中間テーブルでない場合は空
   */
  public Optional<JunctionTable> find(TableEntry table) {
    return Optional.ofNullable(junctions.get(table.key()));
  }

  /**
   * テーブルのER上の区分を返すメソッド
   *
   * @return 区分を持たない（通常の）テーブルは空
   */
  public Optional<ErRole> roleOf(TableEntry table) {
    return find(table).map(junction -> ErRole.JUNCTION);
  }

  /**
   * テーブルと、中間テーブルを介して多対多の関係にあるテーブルを求めるメソッド<br>
   * 起点のテーブルが同じ中間テーブルの複数の外部キーで参照される自己多対多では、起点自身が相手になる
   *
   * @return 相手のテーブル・中間テーブルの順（DB名・スキーマ名・テーブル名の順）。多対多の関係にあるテーブルが無い場合は空のリスト
   */
  public List<ManyToMany> manyToMany(TableEntry table) {
    return manyToMany.getOrDefault(table.key(), List.of());
  }

  /**
   * 中間テーブルごとに、つなぐテーブルを互いの相手として集める
   *
   * @param existing スナップショットにあるテーブル。これに無いテーブルは、起点にも相手にもしない
   */
  private Map<ObjectKey, Set<ManyToMany>> collectPartners(Set<ObjectKey> existing) {
    final Map<ObjectKey, Set<ManyToMany>> partners = new HashMap<>();
    for (final JunctionTable junction : junctions.values()) {
      final List<ObjectKey> linkTargets = junction.links().stream().map(Relation::to).toList();
      // 自己多対多では同じテーブルが2本の関連の参照先になるため、テーブルではなく関連の位置で比べる
      for (int ownerIndex = 0; ownerIndex < linkTargets.size(); ownerIndex++) {
        for (int partnerIndex = 0; partnerIndex < linkTargets.size(); partnerIndex++) {
          final ObjectKey owner = linkTargets.get(ownerIndex);
          final ObjectKey partner = linkTargets.get(partnerIndex);
          if (ownerIndex != partnerIndex
              && existing.contains(owner)
              && existing.contains(partner)) {
            partners
                .computeIfAbsent(owner, key -> new TreeSet<>(BY_TABLE_THEN_VIA))
                .add(new ManyToMany(partner, junction.key()));
          }
        }
      }
    }
    return partners;
  }
}
