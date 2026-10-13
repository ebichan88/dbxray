package com.dbxray.mcp.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * テーブル検索の検索語<br>
 * 空白区切りの語をすべて含むテーブルを一致とみなし（AND）、語ごとに最も強く一致した項目の点数を合計して並べる。
 * 日本語の論理名・説明にもそのまま効くよう、形態素解析はせず部分一致で比べる
 */
public final class SearchQuery {

  private static final int NAME_EXACT = 100;
  private static final int NAME_PREFIX = 60;
  private static final int NAME_PARTIAL = 40;
  private static final int LOGICAL_NAME_EXACT = 80;
  private static final int LOGICAL_NAME_PARTIAL = 30;
  private static final int COLUMN_NAME_EXACT = 25;
  private static final int COLUMN_PARTIAL = 12;
  private static final int DESCRIPTION = 8;
  private static final int REMARKS = 5;
  private static final int COLUMN_REMARKS = 4;

  private final List<String> terms;

  private SearchQuery(List<String> terms) {
    this.terms = terms;
  }

  /**
   * 検索語を組み立てるメソッド
   *
   * @param query 空白区切りの検索語。全角空白も区切りとみなす
   * @throws IllegalArgumentException 検索語が空の場合
   */
  public static SearchQuery of(String query) {
    final List<String> terms =
        Arrays.stream(TextValues.normalize(query).split("\\s+"))
            .filter(term -> !term.isEmpty())
            .toList();
    if (terms.isEmpty()) {
      throw new IllegalArgumentException("検索語が空です。");
    }
    return new SearchQuery(terms);
  }

  /**
   * テーブルが検索語に一致するか判定するメソッド
   *
   * @return 一致した場合はその強さと一致した項目。検索語のいずれかがどの項目にも含まれない場合は空
   */
  Optional<TableHit> match(TableEntry table) {
    int total = 0;
    final Set<String> matchedIn = new LinkedHashSet<>();
    for (final String term : terms) {
      final TermMatch termMatch = matchTerm(table, term);
      if (termMatch.score() == 0) {
        return Optional.empty();
      }
      total += termMatch.score();
      matchedIn.addAll(termMatch.matchedIn());
    }
    return Optional.of(new TableHit(table, total, List.copyOf(matchedIn)));
  }

  /** 1語について、一致した項目のうち最も強い点数と、一致した項目の一覧を求める */
  private static TermMatch matchTerm(TableEntry table, String term) {
    final TermMatch result = new TermMatch();
    final String name = TextValues.normalize(table.key().name());
    if (name.equals(term)) {
      result.add(NAME_EXACT, "name");
    } else if (name.startsWith(term)) {
      result.add(NAME_PREFIX, "name");
    } else if (name.contains(term)) {
      result.add(NAME_PARTIAL, "name");
    }
    final String logicalName = TextValues.normalize(table.logicalName());
    if (!logicalName.isEmpty() && logicalName.equals(term)) {
      result.add(LOGICAL_NAME_EXACT, "logicalName");
    } else if (logicalName.contains(term)) {
      result.add(LOGICAL_NAME_PARTIAL, "logicalName");
    }
    if (TextValues.normalize(table.description()).contains(term)) {
      result.add(DESCRIPTION, "description");
    }
    if (TextValues.normalize(table.remarks()).contains(term)) {
      result.add(REMARKS, "remarks");
    }
    for (final ColumnEntry column : table.columns()) {
      final String label = "column:" + column.name();
      final String columnName = TextValues.normalize(column.name());
      if (columnName.equals(term)) {
        result.add(COLUMN_NAME_EXACT, label);
      } else if (columnName.contains(term)
          || TextValues.normalize(column.logicalName()).contains(term)) {
        result.add(COLUMN_PARTIAL, label);
      } else if (TextValues.normalize(column.remarks()).contains(term)) {
        result.add(COLUMN_REMARKS, label);
      }
    }
    return result;
  }

  /** 1語の一致の集計 */
  private static final class TermMatch {
    private int score;
    private final List<String> matchedIn = new ArrayList<>();

    void add(int fieldScore, String field) {
      score = Math.max(score, fieldScore);
      matchedIn.add(field);
    }

    int score() {
      return score;
    }

    List<String> matchedIn() {
      return matchedIn;
    }
  }
}
