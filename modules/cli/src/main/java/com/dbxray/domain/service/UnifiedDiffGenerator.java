package com.dbxray.domain.service;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2つの行リストから、unified diff形式（{@code diff -u}やgitと同じ表記）の差分を生成するクラス<br>
 * Myers法（最短の編集手順を求めるアルゴリズム）で編集距離を求め、前後{@value #CONTEXT_LINES}行の文脈を付けたhunkを組み立てる。 外部ライブラリに依存しない
 */
public class UnifiedDiffGenerator {

  /** hunkに含める、変更行の前後の文脈行数 */
  static final int CONTEXT_LINES = 3;

  /**
   * Myers法で編集距離を求める際の上限<br>
   * 超えた場合は全行削除+全行追加のhunkにフォールバックする（出力は正しいままだが、最小の差分ではなくなる）。 比較対象は1オブジェクト分（数十〜数百行）のため、通常はこの上限に達しない
   */
  static final int MAX_EDIT_DISTANCE = 1000;

  @Inject
  public UnifiedDiffGenerator() {}

  /**
   * unified diff形式の差分を生成するメソッド<br>
   * 内容が同じ場合は空リストを返す
   *
   * @param committedLabel コミット済み側（{@code ---}）のラベル
   * @param generatedLabel 生成側（{@code +++}）のラベル
   */
  public List<String> generate(
      String committedLabel,
      List<String> committed,
      String generatedLabel,
      List<String> generated) {
    if (committed.equals(generated)) {
      return List.of();
    }
    final List<String> lines = new ArrayList<>();
    lines.add("--- " + committedLabel);
    lines.add("+++ " + generatedLabel);
    for (final Hunk hunk : buildHunks(diff(committed, generated))) {
      lines.add(hunk.header());
      hunk.ops().forEach(op -> lines.add(op.render()));
    }
    return lines;
  }

  /** 先頭・末尾の共通行を除いてからMyers法を適用することで、差分が局所的な場合の探索範囲を小さく抑える */
  private List<Op> diff(List<String> a, List<String> b) {
    final int maxPrefix = Math.min(a.size(), b.size());
    int prefix = 0;
    while (prefix < maxPrefix && a.get(prefix).equals(b.get(prefix))) {
      prefix++;
    }
    final int maxSuffix = Math.min(a.size(), b.size()) - prefix;
    int suffix = 0;
    while (suffix < maxSuffix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
      suffix++;
    }

    final List<Op> ops = new ArrayList<>();
    for (int i = 0; i < prefix; i++) {
      ops.add(new Op(OpType.EQUAL, a.get(i)));
    }
    ops.addAll(myers(a.subList(prefix, a.size() - suffix), b.subList(prefix, b.size() - suffix)));
    for (int i = a.size() - suffix; i < a.size(); i++) {
      ops.add(new Op(OpType.EQUAL, a.get(i)));
    }
    return ops;
  }

  /**
   * Myers法（最短の編集手順を求めるアルゴリズム）で編集手順を求めるメソッド
   *
   * @param a 変更前の行リスト（先頭・末尾の共通行を除いたもの）
   * @param b 変更後の行リスト（先頭・末尾の共通行を除いたもの）
   */
  private List<Op> myers(List<String> a, List<String> b) {
    final int n = a.size();
    final int m = b.size();
    if (n == 0) {
      return b.stream().map(line -> new Op(OpType.INSERT, line)).toList();
    }
    if (m == 0) {
      return a.stream().map(line -> new Op(OpType.DELETE, line)).toList();
    }

    final int max = n + m;
    final int offset = max;
    final int[] v = new int[2 * max + 1];
    final List<int[]> trace = new ArrayList<>();
    final int cutoff = Math.min(max, MAX_EDIT_DISTANCE);
    int distance = -1;
    outer:
    for (int d = 0; d <= cutoff; d++) {
      trace.add(v.clone());
      for (int k = -d; k <= d; k += 2) {
        final int x;
        if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
          x = v[offset + k + 1];
        } else {
          x = v[offset + k - 1] + 1;
        }
        int y = x - k;
        int nx = x;
        int ny = y;
        while (nx < n && ny < m && a.get(nx).equals(b.get(ny))) {
          nx++;
          ny++;
        }
        v[offset + k] = nx;
        if (nx >= n && ny >= m) {
          distance = d;
          break outer;
        }
      }
    }

    if (distance < 0) {
      // 編集距離が上限を超えた場合は、全行削除+全行追加へフォールバックする
      final List<Op> fallback = new ArrayList<>();
      a.forEach(line -> fallback.add(new Op(OpType.DELETE, line)));
      b.forEach(line -> fallback.add(new Op(OpType.INSERT, line)));
      return fallback;
    }
    return backtrack(a, b, trace, distance, offset);
  }

  /**
   * Myers法の探索過程（{@code trace}）から、末尾から先頭へ辿って編集手順を復元するメソッド
   *
   * @param trace 各編集距離{@code d}の探索開始時点でのv配列のスナップショット
   * @param offset v配列上の{@code k=0}に対応するオフセット
   */
  private List<Op> backtrack(
      List<String> a, List<String> b, List<int[]> trace, int distance, int offset) {
    int x = a.size();
    int y = b.size();
    final List<Op> ops = new ArrayList<>();
    for (int d = distance; d >= 0; d--) {
      final int[] v = trace.get(d);
      final int k = x - y;
      final int prevK;
      if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
        prevK = k + 1;
      } else {
        prevK = k - 1;
      }
      final int prevX = v[offset + prevK];
      final int prevY = prevX - prevK;
      while (x > prevX && y > prevY) {
        ops.add(new Op(OpType.EQUAL, a.get(x - 1)));
        x--;
        y--;
      }
      if (d > 0) {
        if (x == prevX) {
          ops.add(new Op(OpType.INSERT, b.get(y - 1)));
        } else {
          ops.add(new Op(OpType.DELETE, a.get(x - 1)));
        }
      }
      x = prevX;
      y = prevY;
    }
    Collections.reverse(ops);
    return ops;
  }

  /**
   * 変更箇所（連続する非EQUAL区間）の間が{@code 2 * CONTEXT_LINES}行以内の場合は1つのhunkへ結合する<br>
   * {@code ops}は変更箇所を1つ以上含むこと（内容が異なる行リスト同士の編集手順は必ず含む）
   */
  private List<Hunk> buildHunks(List<Op> ops) {
    final List<int[]> clusters = findClusters(ops);
    final List<Hunk> hunks = new ArrayList<>();
    int groupStart = clusters.get(0)[0];
    int groupEnd = clusters.get(0)[1];
    for (int i = 1; i < clusters.size(); i++) {
      final int[] cluster = clusters.get(i);
      if (cluster[0] - groupEnd <= 2 * CONTEXT_LINES) {
        groupEnd = cluster[1];
      } else {
        hunks.add(toHunk(ops, groupStart, groupEnd));
        groupStart = cluster[0];
        groupEnd = cluster[1];
      }
    }
    hunks.add(toHunk(ops, groupStart, groupEnd));
    return hunks;
  }

  /** 編集手順のうち、非EQUAL（変更箇所）が連続する区間（{@code [開始, 終了)}）のリストを求める */
  private List<int[]> findClusters(List<Op> ops) {
    final List<int[]> clusters = new ArrayList<>();
    int i = 0;
    while (i < ops.size()) {
      if (ops.get(i).type() == OpType.EQUAL) {
        i++;
        continue;
      }
      final int start = i;
      while (i < ops.size() && ops.get(i).type() != OpType.EQUAL) {
        i++;
      }
      clusters.add(new int[] {start, i});
    }
    return clusters;
  }

  /**
   * @param groupStart 変更箇所の開始位置（{@code ops}内のインデックス）
   */
  private Hunk toHunk(List<Op> ops, int groupStart, int groupEnd) {
    final int start = Math.max(0, groupStart - CONTEXT_LINES);
    final int end = Math.min(ops.size(), groupEnd + CONTEXT_LINES);
    final List<Op> hunkOps = ops.subList(start, end);

    int aBefore = 0;
    int bBefore = 0;
    for (int i = 0; i < start; i++) {
      final OpType type = ops.get(i).type();
      if (type != OpType.INSERT) {
        aBefore++;
      }
      if (type != OpType.DELETE) {
        bBefore++;
      }
    }
    int aCount = 0;
    int bCount = 0;
    for (final Op op : hunkOps) {
      if (op.type() != OpType.INSERT) {
        aCount++;
      }
      if (op.type() != OpType.DELETE) {
        bCount++;
      }
    }
    final int aStart = aCount == 0 ? aBefore : aBefore + 1;
    final int bStart = bCount == 0 ? bBefore : bBefore + 1;
    return new Hunk(aStart, aCount, bStart, bCount, hunkOps);
  }

  /** 編集手順1件分の種別 */
  private enum OpType {
    /** 変更前後で一致する行（文脈として表示） */
    EQUAL,
    /** 変更前にのみ存在する行 */
    DELETE,
    /** 変更後にのみ存在する行 */
    INSERT
  }

  /** 差分の1行（行の種別と内容） */
  private record Op(OpType type, String line) {

    String render() {
      return switch (type) {
        case EQUAL -> " " + line;
        case DELETE -> "-" + line;
        case INSERT -> "+" + line;
      };
    }
  }

  /**
   * unified diffの1ハンク（変更箇所と前後の文脈行のまとまり）
   *
   * @param aStart 変更前側の開始行番号（1始まり。該当行が0件の場合はその直前の行番号）
   * @param bStart 変更後側の開始行番号（1始まり。該当行が0件の場合はその直前の行番号）
   */
  private record Hunk(int aStart, int aCount, int bStart, int bCount, List<Op> ops) {

    String header() {
      return "@@ -" + formatRange(aStart, aCount) + " +" + formatRange(bStart, bCount) + " @@";
    }

    /** 行数が1の場合は開始行番号のみとする（GNU diff・gitと同じ表記） */
    private static String formatRange(int start, int count) {
      return count == 1 ? String.valueOf(start) : start + "," + count;
    }
  }
}
