package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * テーブル（ビューを含む）の検索・一覧・名前の解決と、カラム・トリガーの逆引きを担うクラス<br>
 * 関数・シーケンス・型を使うトリガー・カラムの相互参照も、テーブルを走査して求めるためここで担う
 */
public final class TableCatalog {

  private static final Comparator<TableEntry> BY_KEY =
      Comparator.comparing(TableEntry::key, ObjectKey.ORDER);

  private final List<TableEntry> tables;
  private final RelationGraph relations;

  TableCatalog(List<TableEntry> tables, RelationGraph relations) {
    this.tables = List.copyOf(tables);
    this.relations = relations;
  }

  /**
   * 全テーブルを返すメソッド
   *
   * @return 組み立て時に渡された順のテーブル
   */
  public List<TableEntry> all() {
    return tables;
  }

  /** テーブルを一覧にするメソッド */
  public List<TableEntry> list(TableFilter filter, TableOrder order) {
    final Comparator<TableEntry> comparator =
        switch (order) {
          case NAME -> BY_KEY;
          case INCOMING -> byCountDescending(RelationCounts::incoming);
          case OUTGOING -> byCountDescending(RelationCounts::outgoing);
          case IMPACT -> byCountDescending(RelationCounts::impact);
        };
    return tables.stream().filter(filter::matches).sorted(comparator).toList();
  }

  /**
   * テーブルを検索するメソッド<br>
   * 絞り込みは、総数・{@code limit}に正しく反映されるよう検索・並べ替えの前に適用する
   *
   * @param limit 返す件数の上限（1以上）
   * @return 一致の強い順（同点の場合はDB名・スキーマ名・テーブル名の順）の検索結果
   */
  public SearchResult search(SearchQuery query, TableFilter filter, int limit) {
    final List<TableHit> hits =
        tables.stream()
            .filter(filter::matches)
            .map(query::match)
            .flatMap(Optional::stream)
            .sorted(
                Comparator.comparingInt(TableHit::score)
                    .reversed()
                    .thenComparing(TableHit::table, BY_KEY))
            .toList();
    return new SearchResult(hits.size(), hits.stream().limit(limit).toList());
  }

  /**
   * 名前で指定されたテーブルを解決するメソッド<br>
   * テーブル名は大文字小文字を区別せず完全一致で比べる。見つからない場合は、テーブル名を検索語にした検索の上位を候補として返す
   */
  public Lookup<TableEntry> lookup(ObjectReference reference) {
    return ObjectCatalog.lookup(tables, reference, this::suggestionsFor);
  }

  /**
   * カラムを名前（物理名・論理名）で逆引きするメソッド
   *
   * @return 当てはまりの強い順（同じ強さの場合はDB名・スキーマ名・テーブル名・カラムの並び順）
   */
  public List<ColumnHit> findColumns(ColumnQuery query, SearchScope scope) {
    final List<ScoredColumn> found = new ArrayList<>();
    final List<TableEntry> inScope =
        tables.stream().filter(table -> scope.matches(table.key())).sorted(BY_KEY).toList();
    for (final TableEntry table : inScope) {
      for (final ColumnEntry column : table.columns()) {
        final int score = query.score(column);
        if (score > 0) {
          found.add(
              new ScoredColumn(score, new ColumnHit(table, column, referencesOf(table, column))));
        }
      }
    }
    return found.stream()
        .sorted(Comparator.comparingInt(ScoredColumn::score).reversed())
        .map(ScoredColumn::hit)
        .toList();
  }

  /**
   * 構造（カラム名）が似ているテーブルを探すメソッド<br>
   * 比べる相手は範囲内の区分が{@code table}のテーブル。基準が既存のテーブルの場合は、基準自身と、基準と関連（外部キー・論理リレーション）で
   * 直接つながるテーブルを除く。親子は互いのキーを持つため、構造の類似ではなく関連の結果として似て見えるため。
   * 求め方は{@link SimilarTableFinder}を参照
   *
   * @param limit 返すテーブルの件数の上限（1以上）
   */
  public SimilarTables findSimilar(SimilarTableQuery query, SearchScope scope, int limit) {
    final TableFilter inScope = TableFilter.of(scope).withType(TableType.TABLE);
    final Set<ObjectKey> excluded = new HashSet<>();
    if (query.base() != null) {
      excluded.add(query.base());
      excluded.addAll(relations.adjacentTables(query.base()));
    }
    return new SimilarTableFinder(tables.stream().filter(inScope::matches).toList())
        .find(query, excluded, limit);
  }

  /**
   * テーブルのトリガーを一覧にするメソッド
   *
   * @return DB名・スキーマ名・テーブル名の順（同じテーブルのトリガーはスナップショットの並び順）
   */
  public List<TableTrigger> triggers(SearchScope scope) {
    return tables.stream()
        .filter(table -> scope.matches(table.key()))
        .sorted(BY_KEY)
        .flatMap(
            table -> table.triggers().stream().map(trigger -> new TableTrigger(table, trigger)))
        .toList();
  }

  /**
   * テーブル（ビューを含む）を参照しているビューを求めるメソッド
   *
   * @return DB名・スキーマ名・テーブル名の順
   */
  public List<TableEntry> viewsReferencing(TableEntry table) {
    return tables.stream()
        .filter(view -> view.referencedTables().contains(table.key()))
        .sorted(BY_KEY)
        .toList();
  }

  /**
   * 関数を実行するトリガーを求めるメソッド
   *
   * @return DB名・スキーマ名・テーブル名の順
   */
  public List<TableTrigger> triggersCalling(FunctionOverloads function) {
    return triggers(SearchScope.ALL).stream()
        .filter(
            found ->
                SqlNames.sameObject(
                    SqlNames.functionOfTrigger(found.trigger().function(), found.table().key()),
                    function.key()))
        .toList();
  }

  /**
   * シーケンスを採番（デフォルト値の{@code nextval}）に使うカラムを求めるメソッド
   *
   * @return DB名・スキーマ名・テーブル名・カラムの並び順
   */
  public List<TableColumn> columnsUsing(SequenceEntry sequence) {
    return columnsWhere(
        (table, column) ->
            SqlNames.sequenceOfDefault(column.defaultValue(), table.key())
                .filter(key -> SqlNames.sameObject(key, sequence.key()))
                .isPresent());
  }

  /**
   * ユーザー定義型をカラムの型（配列を含む）に使うカラムを求めるメソッド
   *
   * @return DB名・スキーマ名・テーブル名・カラムの並び順
   */
  public List<TableColumn> columnsUsing(TypeEntry type) {
    return columnsWhere(
        (table, column) ->
            !column.type().isEmpty()
                && SqlNames.sameObject(
                    SqlNames.typeOfColumn(column.type(), table.key()), type.key()));
  }

  private Comparator<TableEntry> byCountDescending(ToIntFunction<RelationCounts> count) {
    return Comparator.comparingInt((TableEntry table) -> count.applyAsInt(relations.counts(table)))
        .reversed()
        .thenComparing(BY_KEY);
  }

  /** カラムが外部キー・論理リレーションで参照している先を、関連の定義順に返す */
  private List<ColumnHit.ColumnReference> referencesOf(TableEntry table, ColumnEntry column) {
    final List<ColumnHit.ColumnReference> references = new ArrayList<>();
    for (final Relation relation : relations.outgoing(table.key())) {
      final int position = relation.fromColumns().indexOf(column.name());
      if (position >= 0 && position < relation.toColumns().size()) {
        references.add(
            new ColumnHit.ColumnReference(
                relation.to(), relation.toColumns().get(position), relation.kind()));
      }
    }
    return references;
  }

  private List<TableColumn> columnsWhere(BiPredicate<TableEntry, ColumnEntry> condition) {
    return tables.stream()
        .sorted(BY_KEY)
        .flatMap(
            table ->
                table.columns().stream()
                    .filter(column -> condition.test(table, column))
                    .map(column -> new TableColumn(table, column)))
        .toList();
  }

  /** 見つからなかったテーブル名に似た名前のテーブルを、DB・スキーマの絞り込みを外して探す */
  private List<TableEntry> suggestionsFor(ObjectReference reference) {
    if (reference.name().isBlank()) {
      return List.of();
    }
    return search(SearchQuery.of(reference.name()), TableFilter.ALL, ObjectCatalog.MAX_SUGGESTIONS)
        .hits()
        .stream()
        .map(TableHit::table)
        .toList();
  }

  /** 逆引きで当てはまったカラムと、当てはまりの強さ */
  private record ScoredColumn(int score, ColumnHit hit) {}
}
