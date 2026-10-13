package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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

  /** 類似度の高い順。同じ場合は共通のカラムの多い順、DB名・スキーマ名・テーブル名の順 */
  private static final Comparator<SimilarTable> MOST_SIMILAR_FIRST =
      Comparator.comparingInt(SimilarTable::similarity)
          .reversed()
          .thenComparing(Comparator.comparingInt(SimilarTable::sharedColumns).reversed())
          .thenComparing(similar -> similar.table().key(), ObjectKey.ORDER);

  /** カラムが同じ相手の類似度（%） */
  private static final int FULL_SIMILARITY = 100;

  /** カラムが同じでない相手の類似度の上限。丸めて100になっても、カラムが同じ相手と区別できるようにする */
  private static final int MAX_SIMILARITY_UNLESS_IDENTICAL = FULL_SIMILARITY - 1;

  /** 比べる相手になりうるテーブル（区分が{@code table}）。カラム名の正規化を呼び出しのたびに繰り返さないよう、組み立て時に1回だけ行う */
  private final List<ComparedTable> candidates = new ArrayList<>();

  /** DB名ごとの、カラム名を持つテーブルの数 */
  private final Map<String, ColumnFrequencies> frequenciesByDatabase = new LinkedHashMap<>();

  SimilarTableFinder(List<TableEntry> tables) {
    for (final TableEntry table : tables) {
      if (isComparable(table)) {
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
    final Map<String, ColumnEntry> baseColumns = columnsByName(query.columns());
    final Comparison comparison =
        new Comparison(
            query,
            baseColumns,
            frequenciesFor(query.base(), baseColumns.keySet(), filter),
            related);
    final List<SimilarTable> found =
        candidates.stream()
            .filter(candidate -> filter.matches(candidate.table()))
            .filter(candidate -> !comparison.isBase(candidate.table()))
            .map(comparison::with)
            .flatMap(Optional::stream)
            .sorted(MOST_SIMILAR_FIRST)
            .toList();
    return new SimilarTables(found.size(), found.stream().limit(limit).toList());
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
    if (base != null && isComparable(base) && filter.matchesDatabase(base.key().database())) {
      frequencies.remove(baseNames);
    }
    return frequencies;
  }

  /**
   * 重み付きJaccard係数を類似度（%）にするメソッド
   *
   * @param ratio 共通のカラムの重みの合計が、両者のカラムの重みの合計に占める割合
   */
  private static int similarity(SimilarityKind kind, double ratio) {
    if (kind == SimilarityKind.IDENTICAL) {
      return FULL_SIMILARITY;
    }
    return Math.min(MAX_SIMILARITY_UNLESS_IDENTICAL, (int) Math.round(FULL_SIMILARITY * ratio));
  }

  /** 正規化したカラム名のうち、条件に当てはまるものを並び順のまま返す */
  private static List<String> namesIn(
      Map<String, ColumnEntry> columns, Predicate<String> condition) {
    return columns.keySet().stream().filter(condition).toList();
  }

  /** 正規化したカラム名を、テーブルでの元の名前にする */
  private static List<String> originalNames(
      List<String> normalizedNames, Map<String, ColumnEntry> columns) {
    return normalizedNames.stream().map(name -> columns.get(name).name()).toList();
  }

  /** 比べる相手・重みの数に入れる区分（{@code table}）か判定する */
  private static boolean isComparable(TableEntry table) {
    return TableType.TABLE.value().equals(table.type());
  }

  /** 正規化したカラム名から、カラムを引く。正規化後に同じ名前になるカラムは、先に現れたものを使う */
  private static Map<String, ColumnEntry> columnsByName(List<ColumnEntry> columns) {
    final Map<String, ColumnEntry> byName = new LinkedHashMap<>();
    for (final ColumnEntry column : columns) {
      byName.putIfAbsent(TextValues.normalize(column.name()), column);
    }
    return byName;
  }

  /**
   * 基準と、基準を相手と比べるのに使う値
   *
   * @param base 基準のカラム（キーは正規化したカラム名）
   * @param frequencies 重みを求めるカラム名の数
   * @param related 基準と関連で直接つながるテーブル
   */
  private record Comparison(
      SimilarTableQuery query,
      Map<String, ColumnEntry> base,
      ColumnFrequencies frequencies,
      Set<ObjectKey> related) {

    boolean isBase(TableEntry table) {
      return query.base() != null && query.base().key().equals(table.key());
    }

    /**
     * 基準と1テーブルを比べるメソッド
     *
     * @return 共通のカラムが無い場合、条件を満たさない場合は空
     */
    Optional<SimilarTable> with(ComparedTable candidate) {
      final Map<String, ColumnEntry> other = candidate.columns();
      final List<String> shared = namesIn(base, other::containsKey);
      if (shared.isEmpty()) {
        return Optional.empty();
      }
      final List<String> onlyInBase = namesIn(base, name -> !other.containsKey(name));
      final List<String> onlyInOther = namesIn(other, name -> !base.containsKey(name));
      final SimilarityKind kind = SimilarityKind.of(!onlyInBase.isEmpty(), !onlyInOther.isEmpty());
      final double sharedWeight = frequencies.weightOf(shared);
      final double unionWeight =
          sharedWeight + frequencies.weightOf(onlyInBase) + frequencies.weightOf(onlyInOther);
      final int similarity = similarity(kind, sharedWeight / unionWeight);
      if (!query.accepts(shared.size(), similarity)) {
        return Optional.empty();
      }
      return Optional.of(
          new SimilarTable(
              candidate.table(),
              similarity,
              kind,
              shared.size(),
              originalNames(onlyInBase, base),
              originalNames(onlyInOther, other),
              shared.stream()
                  .map(name -> SimilarTable.TypeMismatch.between(base.get(name), other.get(name)))
                  .flatMap(Optional::stream)
                  .toList(),
              related.contains(candidate.table().key())));
    }
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

    /** カラム名の重みの合計を返すメソッド */
    double weightOf(List<String> names) {
      return names.stream().mapToDouble(this::weightOf).sum();
    }
  }
}
