package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 構造（カラム名）が似ているテーブルを求めるクラス<br>
 * カラム名（NFKC正規化＋小文字化）の集合を、重み付きJaccard係数で比べる。
 * 重みは、そのカラム名を持つテーブルが範囲内で少ないほど大きくする。全テーブルが持つ{@code id}・{@code created_at}のような
 * 汎用のカラムを共有しているだけで、似ているとみなさないため。
 * 結果は範囲内のテーブルの集合だけで決まり、同じスナップショットからは常に同じ結果を返す
 */
final class SimilarTableFinder {

  private final List<TableEntry> tables;

  /** 正規化したカラム名ごとの重み。範囲内のテーブルに無いカラム名は含まない */
  private final Map<String, Double> weights = new HashMap<>();

  /**
   * @param tables 基準と比べる相手のテーブル（範囲内の全テーブル）。重みはこの範囲で決まる
   */
  SimilarTableFinder(List<TableEntry> tables) {
    this.tables = List.copyOf(tables);
    final Map<String, Integer> frequencies = new HashMap<>();
    for (final TableEntry table : this.tables) {
      for (final String name : columnsByName(table.columns()).keySet()) {
        frequencies.merge(name, 1, Integer::sum);
      }
    }
    frequencies.forEach((name, frequency) -> weights.put(name, weightByFrequency(frequency)));
  }

  /**
   * 基準に似ているテーブルを求めるメソッド
   *
   * @param excluded 比べない相手のテーブル（基準自身等）
   * @param limit 返すテーブルの件数の上限（1以上）
   */
  SimilarTables find(SimilarTableQuery query, Set<ObjectKey> excluded, int limit) {
    final Map<String, ColumnEntry> base = columnsByName(query.columns());
    final List<SimilarTable> found = new ArrayList<>();
    for (final TableEntry table : tables) {
      if (!excluded.contains(table.key())) {
        compare(query, base, table).ifPresent(found::add);
      }
    }
    found.sort(
        Comparator.comparingInt(SimilarTable::similarity)
            .reversed()
            .thenComparing(Comparator.comparingInt(SimilarTable::sharedColumns).reversed())
            .thenComparing(similar -> similar.table().key(), ObjectKey.ORDER));
    return new SimilarTables(found.size(), found.stream().limit(limit).toList());
  }

  /**
   * 基準と1テーブルを比べるメソッド
   *
   * @return 共通のカラムが無い場合、条件を満たさない場合は空
   */
  private Optional<SimilarTable> compare(
      SimilarTableQuery query, Map<String, ColumnEntry> base, TableEntry table) {
    final Map<String, ColumnEntry> other = columnsByName(table.columns());
    final List<String> onlyInBase = new ArrayList<>();
    final List<String> onlyInOther = new ArrayList<>();
    final List<SimilarTable.TypeMismatch> typeMismatches = new ArrayList<>();
    int shared = 0;
    double sharedWeight = 0;
    double totalWeight = 0;
    for (final Map.Entry<String, ColumnEntry> entry : base.entrySet()) {
      final double weight = weightOfName(entry.getKey());
      totalWeight += weight;
      final ColumnEntry counterpart = other.get(entry.getKey());
      if (counterpart == null) {
        onlyInBase.add(entry.getValue().name());
        continue;
      }
      shared++;
      sharedWeight += weight;
      typeMismatch(entry.getValue(), counterpart).ifPresent(typeMismatches::add);
    }
    for (final Map.Entry<String, ColumnEntry> entry : other.entrySet()) {
      if (!base.containsKey(entry.getKey())) {
        onlyInOther.add(entry.getValue().name());
        totalWeight += weightOfName(entry.getKey());
      }
    }
    if (shared == 0) {
      return Optional.empty();
    }
    final int similarity = (int) Math.round(100 * sharedWeight / totalWeight);
    if (!query.accepts(shared, similarity)) {
      return Optional.empty();
    }
    return Optional.of(
        new SimilarTable(
            table,
            similarity,
            SimilarityKind.of(!onlyInBase.isEmpty(), !onlyInOther.isEmpty()),
            shared,
            onlyInBase,
            onlyInOther,
            typeMismatches));
  }

  private static Optional<SimilarTable.TypeMismatch> typeMismatch(
      ColumnEntry base, ColumnEntry other) {
    if (base.type().isEmpty()
        || other.type().isEmpty()
        || TextValues.normalize(base.type()).equals(TextValues.normalize(other.type()))) {
      return Optional.empty();
    }
    return Optional.of(new SimilarTable.TypeMismatch(base.name(), base.type(), other.type()));
  }

  /** カラムの重みを返す。範囲内のテーブルに無いカラム名は、1テーブルだけが持つ場合と同じ重みにする */
  private double weightOfName(String normalizedName) {
    final Double known = weights.get(normalizedName);
    return known != null ? known : weightByFrequency(0);
  }

  /**
   * 範囲内のテーブルのうち{@code frequency}個が持つカラム名の重みを求める。
   * 全テーブルが持つカラム名でも0にならないよう、テーブル数に1を足している
   */
  private double weightByFrequency(int frequency) {
    return Math.log((tables.size() + 1.0) / Math.max(frequency, 1));
  }

  /** 正規化したカラム名から、カラムを引く。正規化後に同じ名前になるカラムは、先に現れたものを使う */
  private static Map<String, ColumnEntry> columnsByName(List<ColumnEntry> columns) {
    final Map<String, ColumnEntry> byName = new LinkedHashMap<>();
    for (final ColumnEntry column : columns) {
      byName.putIfAbsent(TextValues.normalize(column.name()), column);
    }
    return byName;
  }
}
