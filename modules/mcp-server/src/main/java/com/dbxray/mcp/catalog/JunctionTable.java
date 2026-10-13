package com.dbxray.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多対多をつなぐテーブル（中間テーブル）と、主キーを構成する関連
 *
 * @param key 中間テーブル
 * @param links 主キーを構成する関連（外部キー・論理リレーション）。2本以上あり、参照元の列は互いに重ならず、主キーの列を過不足なく覆う
 */
public record JunctionTable(ObjectKey key, List<Relation> links) {

  /** 多対多をつなぐために必要な、主キーを構成する関連の数 */
  static final int MIN_LINKS = 2;

  /** 関連の複製を保持し、外から変更できないようにする */
  public JunctionTable {
    links = List.copyOf(links);
  }

  /**
   * 中間テーブルがつなぐテーブルを返すメソッド
   *
   * @return 関連の順。同じテーブルを複数の関連で参照する自己多対多では、そのテーブルを1つだけ返す
   */
  public List<ObjectKey> parents() {
    return links.stream().map(Relation::to).distinct().toList();
  }

  /**
   * テーブルが中間テーブルか判定し、中間テーブルであれば求めるメソッド<br>
   * 区分が{@code table}で、主キーの列が、互いに重ならない2本以上の関連の参照元の列だけで構成される場合に、中間テーブルとする。
   * 主キー以外の列（属性）の有無・他のテーブルから参照されているかは問わない。
   * 同じ列の組を参照元とする関連は1本とみなし、外部キーを論理リレーションより優先する
   *
   * @param outgoing テーブルが持つ関連（外部キー、論理リレーションの順）
   */
  static Optional<JunctionTable> detect(TableEntry table, List<Relation> outgoing) {
    if (!TableType.TABLE.value().equals(table.type())) {
      return Optional.empty();
    }
    final Set<String> primaryKey =
        table.columns().stream()
            .filter(ColumnEntry::primaryKey)
            .map(column -> lowerCase(column.name()))
            .collect(Collectors.toSet());
    final Map<Set<String>, Relation> linksByColumns = new LinkedHashMap<>();
    for (final Relation relation : outgoing) {
      final Set<String> columns = lowerCases(relation.fromColumns());
      if (primaryKey.containsAll(columns)) {
        linksByColumns.putIfAbsent(columns, relation);
      }
    }
    final Set<String> linkedColumns =
        linksByColumns.keySet().stream().flatMap(Set::stream).collect(Collectors.toSet());
    final int linkedColumnCount = linksByColumns.keySet().stream().mapToInt(Set::size).sum();
    final boolean disjoint = linkedColumns.size() == linkedColumnCount;
    if (linksByColumns.size() < MIN_LINKS || !disjoint || !linkedColumns.equals(primaryKey)) {
      return Optional.empty();
    }
    return Optional.of(new JunctionTable(table.key(), List.copyOf(linksByColumns.values())));
  }

  private static Set<String> lowerCases(List<String> names) {
    return names.stream().map(JunctionTable::lowerCase).collect(Collectors.toSet());
  }

  private static String lowerCase(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
