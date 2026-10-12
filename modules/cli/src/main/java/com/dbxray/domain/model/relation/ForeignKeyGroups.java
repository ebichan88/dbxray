package com.dbxray.domain.model.relation;

import com.dbxray.domain.model.table.TableKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部キーの繋がりからテーブルのまとまり（連結成分）を求めるクラス<br>
 * スキーマ単位のER図が1枚に収まらない場合に、外部キーで繋がったテーブルのまとまりごとに図を分割し、1枚に収まる範囲でまとめ直すために利用する。
 * 入力がコレクション全体ではなくスキーマ単位の部分集合となるため、{@link ForeignKeys}のメソッドではなく独立したクラスとしている
 */
public final class ForeignKeyGroups {

  private ForeignKeyGroups() {}

  /**
   * 1スキーマ分のER図として出力するページ構成<br>
   * {@link #compose}が1回で決定し、呼び出し側（Writer）はこの結果に応じて書き込み先を振り分けるだけでよい
   */
  public sealed interface PageComposition {

    /**
     * 分割せず1枚のページに収める構成<br>
     * {@code group}のノード数が上限を超える場合もあり得る（連結成分単独で上限を超え、これ以上分割できない場合）。
     * その場合、ページ側はER図の描画を省略し外部キー一覧にフォールバックする
     *
     * @param group ページに描画する外部キーのまとまり（スキーマ全体）
     */
    record Single(ForeignKeyGroup group) implements PageComposition {}

    /**
     * 連結成分を1枚に収まる範囲でまとめ直した、複数ページへの分割構成
     *
     * @param nodeCount 分割前のスキーマ全体のノード数（グループ索引ページの説明文に用いる）
     */
    record Grouped(List<ForeignKeyGroup> groups, int nodeCount) implements PageComposition {}
  }

  /**
   * スキーマ全体が上限に収まる場合、または分割しても連結成分が1つ以下にしかならない場合（分割してもスキーマページと
   * 同じ内容のグループページができるだけのため）は分割しない。それ以外は連結成分を1枚に収まる範囲でグループへまとめ直し、複数ページへ分割する
   *
   * @param relatedForeignKeys 当該スキーマのテーブルが関与する外部キー（他スキーマとの関連を含む）のリスト
   */
  public static PageComposition compose(
      List<ForeignKeyEntity> relatedForeignKeys, NodeLimit limit) {
    final ForeignKeyGroup schemaGroup = ForeignKeyGroup.of(relatedForeignKeys);
    if (!schemaGroup.exceeds(limit)) {
      return new PageComposition.Single(schemaGroup);
    }
    final List<ForeignKeyGroup> groups = pack(connectedComponents(relatedForeignKeys), limit);
    if (groups.size() <= 1) {
      return new PageComposition.Single(schemaGroup);
    }
    return new PageComposition.Grouped(groups, schemaGroup.nodeCount());
  }

  /**
   * 外部キーの両端のテーブルを同じまとまりとして併合するため、{@code a → b}と{@code b → c}は1つの連結成分になる。
   * 自己参照・スキーマ跨ぎの外部キーも他と同様に扱う（スキーマを跨ぐ成分は複数スキーマにまたがる）<br>
   * 出力順は、規模の大きいまとまりから確認できるようノード数の降順とし、同数の場合は再実行しても同じ結果になるよう先頭テーブルキーの昇順とする
   */
  static List<ForeignKeyGroup> connectedComponents(List<ForeignKeyEntity> foreignKeys) {
    if (foreignKeys.isEmpty()) {
      return List.of();
    }
    final Map<TableKey, TableKey> parents = new HashMap<>();
    foreignKeys.forEach(fk -> union(parents, fk.tableKey(), fk.referenceTableKey()));
    // 代表テーブルをキーとして外部キーを仕分ける
    final Map<TableKey, List<ForeignKeyEntity>> componentsByRoot = new LinkedHashMap<>();
    foreignKeys.forEach(
        fk ->
            componentsByRoot
                .computeIfAbsent(find(parents, fk.tableKey()), k -> new ArrayList<>())
                .add(fk));
    final Comparator<ForeignKeyGroup> ordering =
        Comparator.comparingInt(ForeignKeyGroup::nodeCount)
            .reversed()
            .thenComparing(ForeignKeyGroups::firstKeyText);
    return componentsByRoot.values().stream().map(ForeignKeyGroup::of).sorted(ordering).toList();
  }

  /**
   * 成分ごとに1ファイルとすると、2テーブルだけの極小の図が大量に生成されてしまうため、ノード数の上限に収まる限り複数の成分を同じ図にまとめる（貪欲法）。
   * まとめられた成分同士は線で繋がっていないため、1枚に並んでも関連を誤読するおそれはない。
   * 単独で上限を超える成分はそれ単独のグループとなり、当該グループは外部キー一覧にフォールバックする
   *
   * @param components 連結成分ごとのまとまりのリスト（ノード数の降順）
   */
  static List<ForeignKeyGroup> pack(List<ForeignKeyGroup> components, NodeLimit limit) {
    final List<PackedGroup> groups = new ArrayList<>();
    for (final ForeignKeyGroup component : components) {
      final Optional<PackedGroup> fitting =
          groups.stream().filter(group -> group.fits(component, limit)).findFirst();
      if (fitting.isPresent()) {
        fitting.get().add(component);
      } else {
        groups.add(new PackedGroup(component));
      }
    }
    return groups.stream().map(PackedGroup::toGroup).toList();
  }

  /** {@link #pack}でまとめ直している途中のグループ（外部キーと、そのノード数） */
  private static final class PackedGroup {

    private final List<ForeignKeyEntity> foreignKeys;
    private int nodeCount;

    private PackedGroup(ForeignKeyGroup first) {
      this.foreignKeys = new ArrayList<>(first.foreignKeys());
      this.nodeCount = first.nodeCount();
    }

    /** 成分を足しても、ノード数が上限を超えないか（成分同士はテーブルを共有しないため、ノード数は足し算になる） */
    private boolean fits(ForeignKeyGroup component, NodeLimit limit) {
      return !limit.isExceededBy(nodeCount + component.nodeCount());
    }

    private void add(ForeignKeyGroup component) {
      foreignKeys.addAll(component.foreignKeys());
      nodeCount += component.nodeCount();
    }

    private ForeignKeyGroup toGroup() {
      return ForeignKeyGroup.of(foreignKeys);
    }
  }

  /**
   * ノード数が同数の場合の並び順を一意に定めるために利用する
   *
   * @return 名称の辞書順で先頭となるテーブルの スキーマ.テーブル 形式の名称
   */
  private static String firstKeyText(ForeignKeyGroup component) {
    return component.nodes().stream()
        .map(TableKey::qualifiedName)
        .min(Comparator.naturalOrder())
        .orElseThrow();
  }

  /** 2つのテーブルを同じまとまりとして併合する（Union-Find） */
  private static void union(Map<TableKey, TableKey> parents, TableKey left, TableKey right) {
    final TableKey leftRoot = find(parents, left);
    final TableKey rightRoot = find(parents, right);
    if (!leftRoot.equals(rightRoot)) {
      parents.put(leftRoot, rightRoot);
    }
  }

  /**
   * テーブルが属するまとまりの代表テーブルを求める（Union-Find）<br>
   * 探索の過程で経路を圧縮し、繰り返し呼び出しても深い探索にならないようにする
   */
  private static TableKey find(Map<TableKey, TableKey> parents, TableKey key) {
    TableKey root = key;
    while (!root.equals(parents.computeIfAbsent(root, k -> k))) {
      root = parents.get(root);
    }
    // 経路圧縮
    TableKey current = key;
    while (!current.equals(root)) {
      final TableKey next = parents.get(current);
      parents.put(current, root);
      current = next;
    }
    return root;
  }
}
