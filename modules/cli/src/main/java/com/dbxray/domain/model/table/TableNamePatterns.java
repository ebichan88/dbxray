package com.dbxray.domain.model.table;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * テーブル名パターン（設定ファイルの{@code target.tables}・CLI引数の{@code --table}の記法）のリストを判定する値オブジェクト<br>
 * 出力対象の範囲（{@link com.dbxray.domain.model.target.TableScope}）と、
 * 観点に所属するテーブルの指定（サイドカーYAMLの{@code viewpoints}）で共通に用いる。以下の記法に対応する。
 *
 * <ul>
 *   <li>完全一致: {@code employee}
 *   <li>ワイルドカード（{@code *}は0文字以上の任意文字列）: {@code *_bk}, {@code *_20240101}
 *   <li>除外（先頭に{@code !}）: {@code !flyway_schema_history}
 *   <li>スキーマ修飾（同名テーブルが複数スキーマに存在する場合に対象スキーマを限定）: {@code sample.employee}
 * </ul>
 *
 * 除外パターンは包含パターンより常に優先される。包含パターンが1件も指定されていない場合は、除外パターンに一致しない限りすべてのテーブルが対象となる。
 * テーブル名の部分が空のパターン（{@code !}のみ、{@code sample.}等）と、スキーマ名の部分が空のパターン（{@code .employee}等）は、
 * どのテーブルにも一致しない書き誤りのため、黙って受け入れずに誤りとする
 */
public final class TableNamePatterns {

  private static final String EXCLUDE_PREFIX = "!";
  private static final String SCHEMA_TABLE_SEPARATOR = ".";
  private static final String WILDCARD = "*";

  private final List<Entry> includes;
  private final List<Entry> excludes;

  private TableNamePatterns(List<Entry> includes, List<Entry> excludes) {
    this.includes = includes;
    this.excludes = excludes;
  }

  /**
   * 各パターンは前後の空白を除去してから解釈する（除外の{@code !}の判定も空白を除去した後に行う）
   *
   * @throws IllegalArgumentException テーブル名またはスキーマ名の部分が空のパターンが含まれる場合（該当するパターンをすべて示す）
   */
  public static TableNamePatterns of(List<String> rawPatterns) {
    if (rawPatterns == null || rawPatterns.isEmpty()) {
      return new TableNamePatterns(List.of(), List.of());
    }
    final List<Entry> includes = new ArrayList<>();
    final List<Entry> excludes = new ArrayList<>();
    final List<String> invalidPatterns = new ArrayList<>();
    for (final String rawPattern : rawPatterns) {
      if (rawPattern == null || rawPattern.isBlank()) {
        continue;
      }
      final String stripped = rawPattern.strip();
      final boolean negate = stripped.startsWith(EXCLUDE_PREFIX);
      final String pattern = negate ? stripped.substring(EXCLUDE_PREFIX.length()) : stripped;
      Entry.parse(pattern)
          .ifPresentOrElse(
              entry -> (negate ? excludes : includes).add(entry),
              () -> invalidPatterns.add(stripped));
    }
    if (!invalidPatterns.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid table pattern: "
              + String.join(", ", invalidPatterns)
              + " (the table name and the schema name must not be empty)");
    }
    return new TableNamePatterns(List.copyOf(includes), List.copyOf(excludes));
  }

  /** 包含・除外のいずれのパターンも指定されていないか */
  public boolean isEmpty() {
    return includes.isEmpty() && excludes.isEmpty();
  }

  /** 包含パターンが無い場合は除外に一致しない全テーブルが対象となるため、観点のように「どのテーブルを含めるか」を明示させたい用途で、指定漏れを判定するために用いる */
  public boolean hasInclusion() {
    return !includes.isEmpty();
  }

  /** テーブルのリネーム・削除によって、パターンがDBと乖離していないかの気付きに用いる */
  public List<String> unmatchedInclusions(Tables tables) {
    return includes.stream()
        .filter(entry -> tables.stream().noneMatch(table -> entry.matches(TableKey.of(table))))
        .map(Entry::raw)
        .toList();
  }

  /** 除外パターンに一致する場合は常にfalse。包含パターンが1件もない場合、除外に一致しない限りtrue */
  public boolean matches(TableKey key) {
    if (excludes.stream().anyMatch(entry -> entry.matches(key))) {
      return false;
    }
    if (includes.isEmpty()) {
      return true;
    }
    return includes.stream().anyMatch(entry -> entry.matches(key));
  }

  /**
   * 1件分のパターン（スキーマ修飾の有無 + テーブル名のワイルドカードパターン）
   *
   * @param raw 指定されたパターン文字列（{@code !}を除く。一致しないパターンの報告に用いる）
   * @param schema スキーマ名。スキーマ修飾がない場合はnull（全スキーマが対象）
   */
  private record Entry(String raw, String schema, Pattern tablePattern) {

    /**
     * @return 解析結果。テーブル名またはスキーマ名の部分が空の場合は空
     */
    static Optional<Entry> parse(String pattern) {
      final int separatorIndex = pattern.indexOf(SCHEMA_TABLE_SEPARATOR);
      final String schema =
          separatorIndex >= 0 ? pattern.substring(0, separatorIndex).strip() : null;
      final String tablePart =
          (separatorIndex >= 0 ? pattern.substring(separatorIndex + 1) : pattern).strip();
      if (tablePart.isEmpty() || (schema != null && schema.isEmpty())) {
        return Optional.empty();
      }
      return Optional.of(new Entry(pattern, schema, toPattern(tablePart)));
    }

    boolean matches(TableKey key) {
      if (schema != null && !schema.equals(key.schema())) {
        return false;
      }
      return tablePattern.matcher(key.table()).matches();
    }

    private static Pattern toPattern(String glob) {
      final StringBuilder regex = new StringBuilder();
      for (int i = 0; i < glob.length(); i++) {
        final char c = glob.charAt(i);
        regex.append(c == WILDCARD.charAt(0) ? ".*" : Pattern.quote(String.valueOf(c)));
      }
      return Pattern.compile(regex.toString());
    }
  }
}
