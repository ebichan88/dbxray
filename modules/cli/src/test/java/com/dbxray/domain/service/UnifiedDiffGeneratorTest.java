package com.dbxray.domain.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UnifiedDiffGenerator のunified diff生成に関するテスト */
public class UnifiedDiffGeneratorTest {

  private final UnifiedDiffGenerator generator = new UnifiedDiffGenerator();

  /** hunkのヘッダ。行数が1の場合は{@code ,行数}を省略する */
  private static final Pattern HUNK_HEADER =
      Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

  private static final int RANDOM_CASES = 5_000;

  private List<String> lines(String... values) {
    return List.of(values);
  }

  @Test
  @DisplayName("generate: 内容が完全に同じ場合は空リストを返す")
  void testNoDifferenceReturnsEmptyList() {
    List<String> result =
        generator.generate("committed", lines("a", "b", "c"), "generated", lines("a", "b", "c"));

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("generate: 中間の1行が変更された場合、前後3行の文脈付きhunkを1つ生成する")
  void testSingleLineChangeInMiddle() {
    List<String> committed = IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(4, "X"); // 5行目(0始まりで4番目)を変更

    List<String> result = generator.generate("committed/t1", committed, "generated/t1", generated);

    assertEquals(
        List.of(
            "--- committed/t1",
            "+++ generated/t1",
            "@@ -2,7 +2,7 @@",
            " 2",
            " 3",
            " 4",
            "-5",
            "+X",
            " 6",
            " 7",
            " 8"),
        result);
  }

  @Test
  @DisplayName("generate: 行数が1のhunkはヘッダで件数を省略する")
  void testHunkHeaderOmitsCountWhenOne() {
    List<String> result = generator.generate("committed", lines("a"), "generated", lines("b"));

    assertEquals(List.of("--- committed", "+++ generated", "@@ -1 +1 @@", "-a", "+b"), result);
  }

  @Test
  @DisplayName("generate: 先頭への追加は、コミット済み側の行数0(0,0)として報告される")
  void testInsertionAtStartOfEmptyCommitted() {
    List<String> result =
        generator.generate("committed", List.of(), "generated", lines("x", "y", "z"));

    assertEquals(
        List.of("--- committed", "+++ generated", "@@ -0,0 +1,3 @@", "+x", "+y", "+z"), result);
  }

  @Test
  @DisplayName("generate: ファイル末尾の変更は、文脈行数が不足する分だけ切り詰められる")
  void testChangeAtEndOfFileClipsTrailingContext() {
    List<String> result =
        generator.generate("committed", lines("a", "b", "c"), "generated", lines("a", "b", "X"));

    assertEquals(
        List.of("--- committed", "+++ generated", "@@ -1,3 +1,3 @@", " a", " b", "-c", "+X"),
        result);
  }

  @Test
  @DisplayName("generate: 変更箇所の間が2*文脈行数(6行)以内の場合は1つのhunkへ結合する")
  void testNearbyChangesAreMergedIntoOneHunk() {
    List<String> committed = IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(3, "X4"); // 4行目を変更
    generated.set(6, "X7"); // 7行目を変更(間の5,6行目=2行はそのまま。6行以内なので結合される)

    List<String> result = generator.generate("committed", committed, "generated", generated);

    long hunkHeaderCount = result.stream().filter(line -> line.startsWith("@@")).count();
    assertEquals(1, hunkHeaderCount, "hunkは1つに結合されるはず: " + result);
    assertTrue(result.contains("-4"));
    assertTrue(result.contains("+X4"));
    assertTrue(result.contains("-7"));
    assertTrue(result.contains("+X7"));
  }

  @Test
  @DisplayName("generate: 変更箇所の間が2*文脈行数(6行)を超える場合は2つのhunkに分かれる")
  void testDistantChangesProduceSeparateHunks() {
    List<String> committed = IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(4, "X5"); // 5行目を変更
    generated.set(15, "X16"); // 16行目を変更(間の6〜15行目=10行はそのまま。6行を超えるので分かれる)

    List<String> result = generator.generate("committed", committed, "generated", generated);

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -2,7 +2,7 @@",
            " 2",
            " 3",
            " 4",
            "-5",
            "+X5",
            " 6",
            " 7",
            " 8",
            "@@ -13,7 +13,7 @@",
            " 13",
            " 14",
            " 15",
            "-16",
            "+X16",
            " 17",
            " 18",
            " 19"),
        result);
  }

  @Test
  @DisplayName("generate: 先頭・末尾の共通行は編集手順の探索対象から除かれても正しく前後の文脈に含まれる")
  void testCommonPrefixAndSuffixAreTrimmedButStillUsedAsContext() {
    // 変更箇所の前後3行(文脈)より内側に共通行が多く存在するケース
    List<String> committed = IntStream.rangeClosed(1, 100).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(49, "CHANGED"); // 50行目のみ変更

    List<String> result = generator.generate("committed", committed, "generated", generated);

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -47,7 +47,7 @@",
            " 47",
            " 48",
            " 49",
            "-50",
            "+CHANGED",
            " 51",
            " 52",
            " 53"),
        result);
  }

  @Test
  @DisplayName("generate: 編集距離が上限を超える場合は全行削除+全行追加のhunkにフォールバックし、結果は正しいまま返す")
  void testFallsBackToFullReplacementWhenEditDistanceExceedsLimit() {
    // 共通する行が1つも無い大きなリスト同士(編集距離 = 行数の合計 > 上限)を比較する
    List<String> committed =
        IntStream.rangeClosed(1, 501).mapToObj(i -> "committed-line-" + i).toList();
    List<String> generated =
        IntStream.rangeClosed(1, 501).mapToObj(i -> "generated-line-" + i).toList();

    List<String> result = generator.generate("committed", committed, "generated", generated);

    assertEquals("--- committed", result.get(0));
    assertEquals("+++ generated", result.get(1));
    assertEquals("@@ -1,501 +1,501 @@", result.get(2));
    assertEquals(2 + 1 + 501 + 501, result.size());
    // 削除側(committed)が先に、追加側(generated)が後にまとまって出力される
    assertTrue(result.subList(3, 504).stream().allMatch(line -> line.startsWith("-committed-")));
    assertTrue(result.subList(504, 1005).stream().allMatch(line -> line.startsWith("+generated-")));
  }

  @Test
  @DisplayName("generate: 途中の行の削除は、変更後の側の行数が1つ少ないhunkになる")
  void testDeletionInMiddle() {
    List<String> committed = IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.remove("5");

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -2,7 +2,6 @@",
            " 2",
            " 3",
            " 4",
            "-5",
            " 6",
            " 7",
            " 8"),
        generator.generate("committed", committed, "generated", generated));
  }

  @Test
  @DisplayName("generate: 途中への行の追加は、変更後の側の行数が1つ多いhunkになる")
  void testInsertionInMiddle() {
    List<String> committed = IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.add(5, "X"); // 5行目の後に追加

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -3,6 +3,7 @@",
            " 3",
            " 4",
            " 5",
            "+X",
            " 6",
            " 7",
            " 8"),
        generator.generate("committed", committed, "generated", generated));
  }

  @Test
  @DisplayName("generate: すべての行の削除は、生成側の行数0(0,0)として報告される")
  void testDeletionOfAllLines() {
    List<String> result = generator.generate("committed", lines("a", "b"), "generated", List.of());

    assertEquals(List.of("--- committed", "+++ generated", "@@ -1,2 +0,0 @@", "-a", "-b"), result);
  }

  @Test
  @DisplayName("generate: 変更箇所の間がちょうど2*文脈行数(6行)の場合は、前後の文脈が接するため1つのhunkへ結合する")
  void testChangesSixLinesApartAreMerged() {
    List<String> committed = IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(3, "X4"); // 4行目を変更
    generated.set(10, "X11"); // 11行目を変更(間の5〜10行目=6行はそのまま)

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -1,14 +1,14 @@",
            " 1",
            " 2",
            " 3",
            "-4",
            "+X4",
            " 5",
            " 6",
            " 7",
            " 8",
            " 9",
            " 10",
            "-11",
            "+X11",
            " 12",
            " 13",
            " 14"),
        generator.generate("committed", committed, "generated", generated));
  }

  @Test
  @DisplayName("generate: 変更箇所の間が7行の場合は、文脈に含めない行が残るため2つのhunkに分かれる")
  void testChangesSevenLinesApartAreSeparated() {
    List<String> committed = IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList();
    List<String> generated = new ArrayList<>(committed);
    generated.set(3, "X4"); // 4行目を変更
    generated.set(11, "X12"); // 12行目を変更(間の5〜11行目=7行はそのまま。8行目はどちらの文脈にも含めない)

    assertEquals(
        List.of(
            "--- committed",
            "+++ generated",
            "@@ -1,7 +1,7 @@",
            " 1",
            " 2",
            " 3",
            "-4",
            "+X4",
            " 5",
            " 6",
            " 7",
            "@@ -9,7 +9,7 @@",
            " 9",
            " 10",
            " 11",
            "-12",
            "+X12",
            " 13",
            " 14",
            " 15"),
        generator.generate("committed", committed, "generated", generated));
  }

  @Test
  @DisplayName("generate: ランダムな行リスト同士でも、差分を当てると生成側に戻り、ヘッダの位置・行数はhunkの行と一致し、変更行の数は最小になる")
  void testRandomListsRoundTrip() {
    final Random random = new Random(20261012L);
    for (int i = 0; i < RANDOM_CASES; i++) {
      // 独立に作った短いリスト（共通する行が散らばる）と、一部を変えた長いリスト（hunkが分かれる）の両方を試す
      final List<String> committed = randomLines(random, i % 2 == 0 ? 10 : 40);
      final List<String> generated = i % 2 == 0 ? randomLines(random, 10) : edit(committed, random);
      final String context = "committed=" + committed + ", generated=" + generated;

      final List<String> diff = generator.generate("committed", committed, "generated", generated);

      if (committed.equals(generated)) {
        assertEquals(List.of(), diff, context);
        continue;
      }
      assertEquals(List.of("--- committed", "+++ generated"), diff.subList(0, 2), context);
      final List<String> hunks = diff.subList(2, diff.size());
      assertEquals(generated, applyDiff(committed, hunks, context), context);
      final long changedLines =
          hunks.stream().filter(line -> line.startsWith("-") || line.startsWith("+")).count();
      assertEquals(
          committed.size() + generated.size() - 2L * lcsLength(committed, generated),
          changedLines,
          context);
    }
  }

  /** 3種類の行からなる、0行以上{@code maxSize}行以下のリスト（同じ行が繰り返し現れるようにする） */
  private static List<String> randomLines(Random random, int maxSize) {
    return IntStream.range(0, random.nextInt(maxSize + 1))
        .mapToObj(i -> String.valueOf((char) ('a' + random.nextInt(3))))
        .toList();
  }

  /** 1〜3か所の行を追加・削除・置換したリスト */
  private static List<String> edit(List<String> lines, Random random) {
    final List<String> edited = new ArrayList<>(lines);
    final int edits = 1 + random.nextInt(3);
    for (int i = 0; i < edits; i++) {
      final int operation = random.nextInt(3);
      if (operation == 0 || edited.isEmpty()) {
        edited.add(random.nextInt(edited.size() + 1), "x");
      } else if (operation == 1) {
        edited.remove(random.nextInt(edited.size()));
      } else {
        edited.set(random.nextInt(edited.size()), "y");
      }
    }
    return edited;
  }

  /**
   * hunkを先頭から順に当てた結果を返す<br>
   * 当てる途中で、ヘッダの開始位置・行数がhunkの行と一致すること、文脈行・削除行が変更前の該当する行と一致することも確かめる
   */
  private static List<String> applyDiff(
      List<String> committed, List<String> hunks, String context) {
    final List<String> result = new ArrayList<>();
    int position = 0;
    int i = 0;
    while (i < hunks.size()) {
      final Matcher header = HUNK_HEADER.matcher(hunks.get(i));
      assertTrue(header.matches(), () -> "hunkのヘッダではない: " + context);
      final int committedCount = countOf(header.group(2));
      final int generatedCount = countOf(header.group(4));
      // 行数が0の場合、開始位置は追加・削除する位置の直前の行を指す
      final int start = Integer.parseInt(header.group(1)) - (committedCount == 0 ? 0 : 1);
      assertTrue(start >= position, () -> "hunkが重なっている: " + context);
      result.addAll(committed.subList(position, start));
      position = start;
      assertEquals(
          result.size() + (generatedCount == 0 ? 0 : 1),
          Integer.parseInt(header.group(3)),
          () -> "生成側の開始位置: " + context);
      int committedLines = 0;
      int generatedLines = 0;
      for (i++; i < hunks.size() && !hunks.get(i).startsWith("@@"); i++) {
        final char kind = hunks.get(i).charAt(0);
        final String text = hunks.get(i).substring(1);
        assertTrue(kind == ' ' || kind == '-' || kind == '+', context);
        if (kind != '+') {
          assertEquals(committed.get(position++), text, context);
          committedLines++;
        }
        if (kind != '-') {
          result.add(text);
          generatedLines++;
        }
      }
      assertEquals(committedCount, committedLines, () -> "コミット済み側の行数: " + context);
      assertEquals(generatedCount, generatedLines, () -> "生成側の行数: " + context);
    }
    result.addAll(committed.subList(position, committed.size()));
    return result;
  }

  private static int countOf(String count) {
    return count == null ? 1 : Integer.parseInt(count);
  }

  /** 最長共通部分列の長さ（変更行の数の最小値は、両側の行数の合計からこの2倍を引いたもの） */
  private static int lcsLength(List<String> a, List<String> b) {
    final int[][] lengths = new int[a.size() + 1][b.size() + 1];
    for (int i = 1; i <= a.size(); i++) {
      for (int j = 1; j <= b.size(); j++) {
        lengths[i][j] =
            a.get(i - 1).equals(b.get(j - 1))
                ? lengths[i - 1][j - 1] + 1
                : Math.max(lengths[i - 1][j], lengths[i][j - 1]);
      }
    }
    return lengths[a.size()][b.size()];
  }
}
