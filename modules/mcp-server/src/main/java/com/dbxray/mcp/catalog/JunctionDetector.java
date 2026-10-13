package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * テーブルが中間テーブルか判定するクラス<br>
 * 区分が{@code table}のテーブルで、主キーの列を過不足なく覆う2本以上の関連の組み合わせがあり、
 * その組み合わせのどの関連も、外すと主キーを覆えなくなる場合に、中間テーブルとする。
 * 主キーを覆う組み合わせが複数ある場合は、関連の数が最も多いものを採り、同数なら関連の並びで先のものを採る。
 * このため、主キーに含まれるだけの余分な関連（近道の外部キー等）があっても、複数の関連が列を共有していても（テナントIDを含む複合キー等）、
 * 構成する関連を取り出せる。
 * 主キー以外の列（属性）の有無・他のテーブルから参照されているかは問わない。
 * 同じ列の組を参照元とする関連は1本とみなし、外部キーを論理リレーションより優先する。
 * 参照先1行に対して参照元が高々1行の関連（1対1）は、多対多をつなぐ関連に数えない。
 * カラム名は大文字小文字を区別せずに比べるため、引用符付きで大文字小文字だけが異なる列が共存するDBでは、それらを同じ列として扱う
 */
final class JunctionDetector {

  /** 多対多をつなぐために必要な、主キーを構成する関連の数 */
  private static final int MIN_LINKS = 2;

  /** 関連の組み合わせを総当たりで探すため、主キーに収まる関連がこれを超えるテーブルは判定しない（組み合わせの数は2のべき乗で増える） */
  static final int MAX_CANDIDATES = 12;

  private JunctionDetector() {}

  /**
   * テーブルが中間テーブルか判定し、中間テーブルであれば求めるメソッド
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
    final List<Candidate> candidates = candidates(outgoing, primaryKey);
    if (candidates.size() > MAX_CANDIDATES) {
      return Optional.empty();
    }
    final List<Candidate> widest = new ArrayList<>();
    findWidestMinimalCover(candidates, primaryKey, 0, new ArrayList<>(), widest);
    if (widest.size() < MIN_LINKS) {
      return Optional.empty();
    }
    return Optional.of(
        new JunctionTable(table.key(), widest.stream().map(Candidate::relation).toList()));
  }

  /**
   * 主キーの列だけを参照元とする関連を、同じ列の組は1本にまとめ（外部キーを優先）、1対1の関連を除いて返す<br>
   * 1対1かどうかは、同じ列の組をまとめた後で判定する（外部キーが1対1なら、同じ列の論理リレーションが既定の1対多でも数えない）
   */
  private static List<Candidate> candidates(List<Relation> outgoing, Set<String> primaryKey) {
    final Map<Set<String>, Relation> byColumns = new LinkedHashMap<>();
    for (final Relation relation : outgoing) {
      final Set<String> columns = lowerCases(relation.fromColumns());
      if (primaryKey.containsAll(columns)) {
        byColumns.putIfAbsent(columns, relation);
      }
    }
    return byColumns.entrySet().stream()
        .filter(entry -> !entry.getValue().isOneToOne())
        .map(entry -> new Candidate(entry.getValue(), entry.getKey()))
        .toList();
  }

  /**
   * 候補の組み合わせを、候補の並びの辞書順に1つずつ調べ、主キーを過不足なく覆う組み合わせのうち最も多くの関連を持つものを求める<br>
   * 同数のものは先に見つかった（候補の並びの辞書順で先の）ものを残す
   *
   * @param from 組み合わせに加える候補の位置の下限
   * @param chosen 組み合わせに加えた候補
   * @param widest 見つかった組み合わせのうち最も関連が多いもの
   */
  private static void findWidestMinimalCover(
      List<Candidate> candidates,
      Set<String> primaryKey,
      int from,
      List<Candidate> chosen,
      List<Candidate> widest) {
    for (int next = from; next < candidates.size(); next++) {
      chosen.add(candidates.get(next));
      if (chosen.size() > widest.size() && isMinimalCover(chosen, primaryKey)) {
        widest.clear();
        widest.addAll(chosen);
      }
      findWidestMinimalCover(candidates, primaryKey, next + 1, chosen, widest);
      chosen.remove(chosen.size() - 1);
    }
  }

  /** 組み合わせが主キーを過不足なく覆い、どの関連にも他の関連に含まれない列がある（外すと覆えなくなる）か判定する */
  private static boolean isMinimalCover(List<Candidate> chosen, Set<String> primaryKey) {
    final Set<String> covered = new HashSet<>();
    chosen.forEach(candidate -> covered.addAll(candidate.columns()));
    return covered.equals(primaryKey)
        && chosen.stream().allMatch(candidate -> hasOwnColumn(chosen, candidate));
  }

  private static boolean hasOwnColumn(List<Candidate> chosen, Candidate candidate) {
    final Set<String> own = new HashSet<>(candidate.columns());
    chosen.stream()
        .filter(other -> !other.equals(candidate))
        .forEach(other -> own.removeAll(other.columns()));
    return !own.isEmpty();
  }

  private static Set<String> lowerCases(List<String> names) {
    return names.stream().map(JunctionDetector::lowerCase).collect(Collectors.toSet());
  }

  private static String lowerCase(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * 中間テーブルを構成する関連の候補
   *
   * @param columns 関連の参照元の列（小文字）
   */
  private record Candidate(Relation relation, Set<String> columns) {}
}
