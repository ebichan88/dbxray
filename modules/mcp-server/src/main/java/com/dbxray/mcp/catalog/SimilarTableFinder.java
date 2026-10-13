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
 * 重みは、そのカラム名を持つテーブルが少ないほど大きくする。全テーブルが持つ{@code id}・{@code created_at}のような
 * 汎用のカラムを共有しているだけで、似ているとみなさないため。
 * 重みは探す範囲（スキーマ・観点）ではなく、範囲のDBの区分が{@code table}の全テーブル（基準自身を除く）で決める。
 * 範囲を絞るとテーブルが減り、汎用のカラムの重みが下がらなくなるため。
 * 結果はスナップショットと条件だけで決まり、同じ条件には常に同じ結果を返す
 */
final class SimilarTableFinder {

  /** カラムが同じでない相手の類似度の上限。丸めて100になっても、カラムが同じ相手と区別できるようにする */
  private static final int MAX_SIMILARITY_UNLESS_IDENTICAL = 99;

  /** 比べる相手になりうるテーブル（区分が{@code table}）。カラム名の正規化を呼び出しのたびに繰り返さないよう、組み立て時に1回だけ行う */
  private final List<ComparedTable> candidates = new ArrayList<>();

  /** DB名ごとの、カラム名を持つテーブルの数 */
  private final Map<String, ColumnFrequencies> frequenciesByDatabase = new LinkedHashMap<>();

  SimilarTableFinder(List<TableEntry> tables) {
    for (final TableEntry table : tables) {
      if (TableType.TABLE.value().equals(table.type())) {
        final ComparedTable compared = new ComparedTable(table, columnsByName(table.columns()));
        candidates.add(compared);
        frequenciesByDatabase
            .computeIfAbsent(table.key().database(), database -> new ColumnFrequencies())
            .add(compared.columns().keySet());
      }
    }
  }

  /**
   * 基準に似ているテーブルを求めるメソッド
   *
   * @param filter 探す範囲。区分が{@code table}でないテーブルは条件にかかわらず比べない。基準自身は比べない
   * @param related 基準と関連で直接つながるテーブル（結果に印を付ける）
   * @param limit 返すテーブルの件数の上限（1以上）
   */
  SimilarTables find(
      SimilarTableQuery query, TableFilter filter, Set<ObjectKey> related, int limit) {
    final Map<String, ColumnEntry> base = columnsByName(query.columns());
    final ColumnFrequencies frequencies = frequenciesFor(query.base(), base.keySet(), filter);
    final List<SimilarTable> found = new ArrayList<>();
    for (final ComparedTable candidate : candidates) {
      if (filter.matches(candidate.table()) && !isBase(query, candidate.table())) {
        compare(query, base, candidate, frequencies, related.contains(candidate.table().key()))
            .ifPresent(found::add);
      }
    }
    found.sort(
        Comparator.comparingInt(SimilarTable::similarity)
            .reversed()
            .thenComparing(Comparator.comparingInt(SimilarTable::sharedColumns).reversed())
            .thenComparing(similar -> similar.table().key(), ObjectKey.ORDER));
    return new SimilarTables(found.size(), found.stream().limit(limit).toList());
  }

  private static boolean isBase(SimilarTableQuery query, TableEntry table) {
    return query.base() != null && query.base().key().equals(table.key());
  }

  /** 範囲のDBのテーブルのカラム名の数を求める。基準自身は数えない（カラム名で指定した場合と重みを揃えるため） */
  private ColumnFrequencies frequenciesFor(
      TableEntry base, Set<String> baseNames, TableFilter filter) {
    final ColumnFrequencies frequencies = new ColumnFrequencies();
    frequenciesByDatabase.forEach(
        (database, counted) -> {
          if (filter.matchesDatabase(database)) {
            frequencies.addAll(counted);
          }
        });
    if (base != null
        && TableType.TABLE.value().equals(base.type())
        && filter.matchesDatabase(base.key().database())) {
      frequencies.remove(baseNames);
    }
    return frequencies;
  }

  /**
   * 基準と1テーブルを比べるメソッド
   *
   * @return 共通のカラムが無い場合、条件を満たさない場合は空
   */
  private static Optional<SimilarTable> compare(
      SimilarTableQuery query,
      Map<String, ColumnEntry> base,
      ComparedTable candidate,
      ColumnFrequencies frequencies,
      boolean relatedToBase) {
    final Map<String, ColumnEntry> other = candidate.columns();
    final List<String> onlyInBase = new ArrayList<>();
    final List<String> onlyInOther = new ArrayList<>();
    final List<SimilarTable.TypeMismatch> typeMismatches = new ArrayList<>();
    int shared = 0;
    double sharedWeight = 0;
    double totalWeight = 0;
    for (final Map.Entry<String, ColumnEntry> entry : base.entrySet()) {
      final double weight = frequencies.weightOf(entry.getKey());
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
        totalWeight += frequencies.weightOf(entry.getKey());
      }
    }
    if (shared == 0) {
      return Optional.empty();
    }
    final SimilarityKind kind = SimilarityKind.of(!onlyInBase.isEmpty(), !onlyInOther.isEmpty());
    final int similarity =
        kind == SimilarityKind.IDENTICAL
            ? 100
            : Math.min(
                MAX_SIMILARITY_UNLESS_IDENTICAL,
                (int) Math.round(100 * sharedWeight / totalWeight));
    if (!query.accepts(shared, similarity)) {
      return Optional.empty();
    }
    return Optional.of(
        new SimilarTable(
            candidate.table(),
            similarity,
            kind,
            shared,
            onlyInBase,
            onlyInOther,
            typeMismatches,
            relatedToBase));
  }

  private static Optional<SimilarTable.TypeMismatch> typeMismatch(
      ColumnEntry base, ColumnEntry other) {
    if (base.type().isEmpty() || other.type().isEmpty()) {
      return Optional.empty();
    }
    return SimilarTable.TypeDifference.between(
            ColumnType.parse(base.type()), ColumnType.parse(other.type()))
        .map(
            difference ->
                new SimilarTable.TypeMismatch(base.name(), base.type(), other.type(), difference));
  }

  /** 正規化したカラム名から、カラムを引く。正規化後に同じ名前になるカラムは、先に現れたものを使う */
  private static Map<String, ColumnEntry> columnsByName(List<ColumnEntry> columns) {
    final Map<String, ColumnEntry> byName = new LinkedHashMap<>();
    for (final ColumnEntry column : columns) {
      byName.putIfAbsent(TextValues.normalize(column.name()), column);
    }
    return byName;
  }

  /** 比べる相手のテーブルと、正規化したカラム名から引くカラム */
  private record ComparedTable(TableEntry table, Map<String, ColumnEntry> columns) {}

  /** テーブルの数と、正規化したカラム名ごとの、そのカラム名を持つテーブルの数。カラム名の重みを求める */
  private static final class ColumnFrequencies {

    private int tables;
    private final Map<String, Integer> counts = new HashMap<>();

    void add(Set<String> names) {
      tables++;
      names.forEach(name -> counts.merge(name, 1, Integer::sum));
    }

    void remove(Set<String> names) {
      tables--;
      names.forEach(name -> counts.merge(name, -1, Integer::sum));
    }

    void addAll(ColumnFrequencies other) {
      tables += other.tables;
      other.counts.forEach((name, count) -> counts.merge(name, count, Integer::sum));
    }

    /**
     * {@code ln((N+1)/そのカラム名を持つテーブルの数)}（Nはテーブルの数）を返すメソッド。
     * 全テーブルが持つカラム名でも0にならないようNに1を足し、どのテーブルも持たないカラム名は1テーブルだけが持つ場合と同じにする
     */
    double weightOf(String name) {
      return Math.log((tables + 1.0) / Math.max(counts.getOrDefault(name, 0), 1));
    }
  }
}
