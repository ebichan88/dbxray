package com.dbxray.mcp.catalog;

import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.RelatedTables.RelationAtDepth;
import com.dbxray.mcp.catalog.TableImpact.FunctionImpact;
import com.dbxray.mcp.catalog.TableImpact.IndirectReference;
import com.dbxray.mcp.catalog.TableImpact.SequenceUse;
import com.dbxray.mcp.catalog.TableImpact.TypeUse;
import com.dbxray.mcp.catalog.TableImpact.UnverifiedFunction;
import com.dbxray.mcp.catalog.TableImpact.ViewReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * テーブルの影響範囲を、各窓口の情報から集める規則を持つクラス<br>
 * 関連・ビュー・トリガー・関数・シーケンス・型は、いずれも既存の窓口が持つ情報を組み合わせて求める（スナップショットに無い値を足さない）。
 * 結果は同じスナップショットからは常に同じになり、呼び出しのたびに求める
 */
final class ImpactAnalyzer {

  /** 関数の操作を並べる順 */
  private static final List<String> OPERATION_ORDER = List.of("C", "R", "U", "D");

  private static final Comparator<Relation> DIRECT_ORDER =
      Comparator.comparing(Relation::from, ObjectKey.ORDER)
          .thenComparing(Relation::kind)
          .thenComparing(Relation::name);

  private final TableCatalog tables;
  private final RelationGraph relations;
  private final ObjectCatalog<FunctionOverloads> functions;
  private final FunctionTableUsages usages;
  private final ObjectCatalog<TypeEntry> types;

  ImpactAnalyzer(
      TableCatalog tables,
      RelationGraph relations,
      ObjectCatalog<FunctionOverloads> functions,
      FunctionTableUsages usages,
      ObjectCatalog<TypeEntry> types) {
    this.tables = tables;
    this.relations = relations;
    this.functions = functions;
    this.usages = usages;
    this.types = types;
  }

  /** テーブルの影響範囲と依存先を求めるメソッド */
  TableImpact analyze(TableEntry table) {
    final RelatedTables incoming =
        relations.relatedTables(table, RelationCounts.IMPACT_DEPTH, Direction.INCOMING);
    final List<Relation> direct = directReferences(incoming);
    final List<ViewReference> views = views(table);
    return new TableImpact(
        table,
        direct,
        indirectReferences(table, incoming, direct),
        views,
        table.triggers(),
        functionImpact(table, views),
        sequences(table),
        types(table));
  }

  private static List<Relation> directReferences(RelatedTables incoming) {
    return incoming.relations().stream()
        .filter(found -> found.depth() == 1)
        .map(RelationAtDepth::relation)
        .sorted(DIRECT_ORDER)
        .toList();
  }

  /**
   * 段1に現れないテーブルを、最小の段数と、その段を見つけた関連の参照先とともに求める<br>
   * 関連は見つけた順に並ぶため、テーブルを最初に見つけた関連が最小の段数になる
   */
  private static List<IndirectReference> indirectReferences(
      TableEntry table, RelatedTables incoming, List<Relation> direct) {
    final Set<ObjectKey> seen = new HashSet<>(List.of(table.key()));
    direct.forEach(relation -> seen.add(relation.from()));
    final List<IndirectReference> indirect = new ArrayList<>();
    for (final RelationAtDepth found : incoming.relations()) {
      final Relation relation = found.relation();
      if (found.depth() > 1 && seen.add(relation.from())) {
        indirect.add(new IndirectReference(relation.from(), found.depth(), relation.to()));
      }
    }
    indirect.sort(
        Comparator.comparingInt(IndirectReference::depth)
            .thenComparing(IndirectReference::table, ObjectKey.ORDER));
    return indirect;
  }

  /** 自テーブルを参照するビューを、ビューを参照するビューも含めて、段ごとに求める。循環していても、同じビューは1度しか数えない */
  private List<ViewReference> views(TableEntry table) {
    final List<ViewReference> found = new ArrayList<>();
    final Set<ObjectKey> visited = new HashSet<>(List.of(table.key()));
    List<TableEntry> frontier = List.of(table);
    while (!frontier.isEmpty()) {
      final Map<ObjectKey, ViewReference> level = new LinkedHashMap<>();
      for (final TableEntry parent : frontier) {
        final ObjectKey via = parent.key().equals(table.key()) ? null : parent.key();
        for (final TableEntry view : tables.viewsReferencing(parent)) {
          if (visited.add(view.key())) {
            level.put(view.key(), new ViewReference(view, via));
          }
        }
      }
      final List<ViewReference> ordered =
          level.values().stream()
              .sorted(Comparator.comparing(reference -> reference.view().key(), ObjectKey.ORDER))
              .toList();
      found.addAll(ordered);
      frontier = ordered.stream().map(ViewReference::view).toList();
    }
    return found;
  }

  private FunctionImpact functionImpact(TableEntry table, List<ViewReference> views) {
    final String database = table.key().database();
    final List<FunctionOverloads> inDatabase =
        functions.all().stream()
            .filter(function -> function.key().database().equals(database))
            .sorted(Comparator.comparing(FunctionOverloads::key, ObjectKey.ORDER))
            .toList();
    final List<Target> targets = new ArrayList<>();
    targets.add(new Target(table.key(), null));
    views.forEach(view -> targets.add(new Target(view.view().key(), view.view().key())));
    final List<FunctionUse> uses = new ArrayList<>();
    final Set<FunctionEntry> matched = new HashSet<>();
    for (final FunctionOverloads function : inDatabase) {
      for (final Target target : targets) {
        useOf(function, target)
            .ifPresent(
                found -> {
                  uses.add(found.use());
                  found.matches().forEach(match -> matched.add(match.overload()));
                });
      }
    }
    return new FunctionImpact(
        coverage(inDatabase, database), uses, unverified(inDatabase, matched));
  }

  /**
   * 関数が対象のテーブルを使っているか求めるメソッド（同名のオーバーロードは1つにまとめる）<br>
   * 一致したオーバーロードの中に、確定で一致したものと候補でだけ一致したものが混ざる場合は、候補としては扱わず、
   * 候補のオーバーロードの操作も合算する（その操作は確定のように見える）
   */
  private Optional<FoundUse> useOf(FunctionOverloads function, Target target) {
    final List<OverloadMatch> matches =
        function.overloads().stream()
            .map(overload -> matchOf(overload, target.key()))
            .flatMap(Optional::stream)
            .toList();
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    final boolean possible = matches.stream().allMatch(OverloadMatch::possible);
    return Optional.of(
        new FoundUse(
            new FunctionUse(
                function.key(),
                kindOf(matches),
                matches.stream().map(match -> match.overload().arguments()).toList(),
                mergedOperations(matches.stream().map(OverloadMatch::operations).toList()),
                possible,
                possible ? matches.get(0).schemaResolution() : "",
                matches.stream()
                    .flatMap(match -> match.usage().dynamicSql().stream())
                    .distinct()
                    .toList(),
                target.via(),
                tables.triggersCalling(function)),
            matches));
  }

  /**
   * オーバーロード1つが対象のテーブルを使っているか求めるメソッド<br>
   * 同じテーブルへの一致が確定と候補の両方にまたがる場合は、確定を採る
   */
  private Optional<OverloadMatch> matchOf(FunctionEntry overload, ObjectKey target) {
    return usages
        .find(overload)
        .flatMap(
            usage -> {
              final List<UsedTable> confirmed =
                  usedTables(usage, target, UsedTable.Match.CONFIRMED);
              if (!confirmed.isEmpty()) {
                return Optional.of(
                    new OverloadMatch(overload, usage, false, "", operationsOf(confirmed)));
              }
              final List<UsedTable> candidates =
                  usedTables(usage, target, UsedTable.Match.CANDIDATE);
              return candidates.isEmpty()
                  ? Optional.empty()
                  : Optional.of(
                      new OverloadMatch(
                          overload,
                          usage,
                          true,
                          candidates.get(0).schemaResolution(),
                          operationsOf(candidates)));
            });
  }

  private static List<UsedTable> usedTables(
      FunctionTableUsageEntry usage, ObjectKey target, UsedTable.Match match) {
    return usage.tables().stream().filter(used -> used.matches(target) == match).toList();
  }

  private static List<String> operationsOf(List<UsedTable> usedTables) {
    return mergedOperations(usedTables.stream().map(UsedTable::operations).toList());
  }

  /** 操作を重複なく、C・R・U・Dの順に並べる */
  private static List<String> mergedOperations(Collection<List<String>> operations) {
    final Set<String> merged = new LinkedHashSet<>();
    operations.forEach(merged::addAll);
    return OPERATION_ORDER.stream().filter(merged::contains).toList();
  }

  /** 一致したオーバーロードの種別が1つに定まる場合はその値。異なる場合・未設定の場合は空文字 */
  private static String kindOf(List<OverloadMatch> matches) {
    final List<String> kinds =
        matches.stream()
            .map(match -> match.overload().kind())
            .filter(kind -> !kind.isEmpty())
            .distinct()
            .toList();
    return kinds.size() == 1 ? kinds.get(0) : "";
  }

  private FunctionCoverage coverage(List<FunctionOverloads> inDatabase, String database) {
    int total = 0;
    int analyzed = 0;
    int unsupported = 0;
    int noInsight = 0;
    int stale = 0;
    int withDynamicSql = 0;
    int incomplete = 0;
    for (final FunctionOverloads function : inDatabase) {
      for (final FunctionEntry overload : function.overloads()) {
        total++;
        final Optional<FunctionTableUsageEntry> usage = usages.find(overload);
        if (usage.isEmpty()) {
          if (usages.freshnessOf(overload) == FunctionTableUsages.Freshness.STALE) {
            stale++;
          } else {
            noInsight++;
          }
        } else if (usage.get().status().equals(FunctionTableUsageEntry.ANALYZED)) {
          analyzed++;
          withDynamicSql += usage.get().dynamicSql().isEmpty() ? 0 : 1;
          incomplete += usage.get().incomplete() ? 1 : 0;
        } else {
          unsupported++;
        }
      }
    }
    final long triggersWithoutFunction =
        tables.triggers(SearchScope.ALL).stream()
            .filter(found -> found.table().key().database().equals(database))
            .filter(found -> found.trigger().function().isEmpty())
            .count();
    return new FunctionCoverage(
        total,
        analyzed,
        unsupported,
        noInsight,
        stale,
        withDynamicSql,
        incomplete,
        (int) triggersWithoutFunction);
  }

  /**
   * 自テーブルを使うかどうか確かめきれない関数を、オーバーロード単位で求める<br>
   * 自テーブルかそのビューを使うと分かったオーバーロード（{@code matched}）は含めない
   */
  private List<UnverifiedFunction> unverified(
      List<FunctionOverloads> inDatabase, Set<FunctionEntry> matched) {
    final List<UnverifiedFunction> found = new ArrayList<>();
    for (final FunctionOverloads function : inDatabase) {
      for (final FunctionEntry overload : function.overloads()) {
        if (!matched.contains(overload)) {
          reasonOf(overload)
              .ifPresent(
                  reason ->
                      found.add(
                          new UnverifiedFunction(function.key(), overload.arguments(), reason)));
        }
      }
    }
    found.sort(
        Comparator.comparing(UnverifiedFunction::reason)
            .thenComparing(UnverifiedFunction::function, ObjectKey.ORDER)
            .thenComparing(UnverifiedFunction::arguments));
    return found;
  }

  /**
   * オーバーロードの参考情報から、確かめきれない理由を求める<br>
   * 参考情報が無い・古い場合は空。関数を1つずつ読んでも対処にならず（出力し直す）、全関数が該当しうるため、件数（{@link FunctionCoverage}）だけで伝える
   */
  private Optional<UnverifiedFunction.Reason> reasonOf(FunctionEntry overload) {
    final Optional<FunctionTableUsageEntry> usage = usages.find(overload);
    if (usage.isEmpty()) {
      return Optional.empty();
    }
    if (!usage.get().status().equals(FunctionTableUsageEntry.ANALYZED)) {
      return Optional.of(UnverifiedFunction.Reason.UNSUPPORTED);
    }
    if (!usage.get().dynamicSql().isEmpty()) {
      return Optional.of(UnverifiedFunction.Reason.DYNAMIC_SQL);
    }
    return usage.get().incomplete()
        ? Optional.of(UnverifiedFunction.Reason.INCOMPLETE)
        : Optional.empty();
  }

  /** デフォルト値の{@code nextval}で使うシーケンスを、シーケンスの順に求める */
  private static List<SequenceUse> sequences(TableEntry table) {
    final Map<ObjectKey, List<String>> bySequence = new TreeMap<>(ObjectKey.ORDER);
    for (final ColumnEntry column : table.columns()) {
      SqlNames.sequenceOfDefault(column.defaultValue(), table.key())
          .ifPresent(
              sequence ->
                  bySequence
                      .computeIfAbsent(sequence, key -> new ArrayList<>())
                      .add(column.name()));
    }
    return bySequence.entrySet().stream()
        .map(entry -> new SequenceUse(entry.getKey(), entry.getValue()))
        .toList();
  }

  /** カラムの型に使われている、スナップショットにあるユーザー定義型を、型の順に求める */
  private List<TypeUse> types(TableEntry table) {
    final Map<TypeEntry, List<String>> byType = new LinkedHashMap<>();
    for (final ColumnEntry column : table.columns()) {
      if (column.type().isEmpty()) {
        continue;
      }
      final ObjectKey key = SqlNames.typeOfColumn(column.type(), table.key());
      types.all().stream()
          .filter(type -> SqlNames.sameObject(type.key(), key))
          .findFirst()
          .ifPresent(
              type -> byType.computeIfAbsent(type, found -> new ArrayList<>()).add(column.name()));
    }
    return byType.entrySet().stream()
        .map(entry -> new TypeUse(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(use -> use.type().key(), ObjectKey.ORDER))
        .toList();
  }

  /**
   * 関数の逆引きの対象
   *
   * @param key 対象のテーブルまたはビュー
   * @param via 対象がビューの場合、そのビュー。自テーブルの場合はnull
   */
  private record Target(ObjectKey key, ObjectKey via) {}

  /** 関数が対象を使っている、という結果と、その元になったオーバーロードの一致 */
  private record FoundUse(FunctionUse use, List<OverloadMatch> matches) {}

  /** オーバーロード1つが対象のテーブルを使っている、という一致 */
  private record OverloadMatch(
      FunctionEntry overload,
      FunctionTableUsageEntry usage,
      boolean possible,
      String schemaResolution,
      List<String> operations) {}
}
