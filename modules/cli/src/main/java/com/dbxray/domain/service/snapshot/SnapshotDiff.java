package com.dbxray.domain.service.snapshot;

import com.dbxray.domain.model.snapshot.ContentDiff;
import com.dbxray.domain.model.snapshot.DiffResult;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.UnifiedDiffGenerator;
import com.dbxray.domain.service.path.OutputPathResolver;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * DBから生成したスキーマのスナップショットと、既にコミット済みのスナップショットを比較するドメインサービス<br>
 * スキーマ配下のオブジェクトのファイル（{@code tables.jsonl}等）は1行1オブジェクトのため、行をオブジェクト単位で突き合わせ、追加/削除/内容不一致をオブジェクト単位（例:
 * {@code table sample.employee}）で報告する。
 * それ以外のファイル（{@code database.json}等）はファイル単位で比較する。
 * 内容が一致しないものは、{@link SnapshotSerializer#formatForDiff}で差分表示用に整形した上で
 * {@link UnifiedDiffGenerator}によりunified diff形式の差分を付ける
 */
public class SnapshotDiff {

  /** 差分の対象の並び順（表示名順。同名の場合はファイル順） */
  private static final Comparator<Target> TARGET_ORDER =
      Comparator.comparing(Target::label).thenComparing(Target::file);

  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final SnapshotSerializer serializer;
  private final UnifiedDiffGenerator diffGenerator;

  @Inject
  public SnapshotDiff(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      SnapshotSerializer serializer,
      UnifiedDiffGenerator diffGenerator) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.serializer = serializer;
    this.diffGenerator = diffGenerator;
  }

  /** 生成したスナップショットのディレクトリと、コミット済みのスナップショットのディレクトリを比較する */
  public DiffResult compare(Path generatedDir, Path committedDir) {
    final Map<Target, List<String>> generated = index(generatedDir);
    final Map<Target, List<String>> committed = index(committedDir);
    return new DiffResult(
        labels(generated, target -> !committed.containsKey(target)),
        labels(committed, target -> !generated.containsKey(target)),
        contentDiffs(generated, committed));
  }

  /** 両方に存在するが内容が一致しないものについて、unified diff付きの差分を組み立てる */
  private List<ContentDiff> contentDiffs(
      Map<Target, List<String>> generated, Map<Target, List<String>> committed) {
    return generated.keySet().stream()
        .filter(
            target ->
                committed.containsKey(target)
                    && !generated.get(target).equals(committed.get(target)))
        .sorted(TARGET_ORDER)
        .map(
            target ->
                new ContentDiff(
                    target.label(),
                    diffGenerator.generate(
                        sideLabel("committed", target),
                        formatForDiff(committed.get(target)),
                        sideLabel("generated", target),
                        formatForDiff(generated.get(target)))))
        .toList();
  }

  private List<String> formatForDiff(List<String> rawLines) {
    return rawLines.stream().flatMap(line -> serializer.formatForDiff(line).stream()).toList();
  }

  /**
   * unified diffの{@code ---}/{@code +++}ヘッダに用いるラベルを組み立てるメソッド<br>
   * オブジェクト単位で比較するもの（{@code tables.jsonl}等）は{@code committed/相対パス (表示名)}、
   * ファイル単位で比較するもの（{@code database.json}等）は表示名がファイルパスそのものであるため{@code committed/相対パス}のみとする<br>
   * 相対パスは{@link Path#toString()}ではなく、OSに依らず常に{@code /}区切りで組み立てる（unified diffの慣習に合わせるため）
   */
  private String sideLabel(String side, Target target) {
    final String relativeFile = toSlashSeparatedPath(target.file());
    final String normalizedLabel = target.label().replace('\\', '/');
    final String suffix = relativeFile.equals(normalizedLabel) ? "" : " (" + target.label() + ")";
    return side + "/" + relativeFile + suffix;
  }

  /**
   * {@link Path#toString()}はWindows環境では{@code \}区切りとなるため、unified diffのヘッダのように
   * プラットフォームに依らない一貫した表記が必要な箇所ではこのメソッドを用いる
   */
  private static String toSlashSeparatedPath(Path path) {
    final StringBuilder builder = new StringBuilder();
    for (final Path part : path) {
      if (builder.length() > 0) {
        builder.append('/');
      }
      builder.append(part);
    }
    return builder.toString();
  }

  private Map<Target, List<String>> index(Path directory) {
    final Map<Target, List<String>> targets = new LinkedHashMap<>();
    for (final Path file : fileRepository.listFiles(directory)) {
      final Path relativePath = directory.relativize(file);
      final List<String> lines = fileRepository.readFile(file);
      final Optional<SnapshotKind> kind = outputPathResolver.resolveSnapshotKind(relativePath);
      if (kind.isEmpty()) {
        targets.put(new Target(relativePath, relativePath.toString()), lines);
        continue;
      }
      lines.stream()
          .filter(line -> !line.isBlank())
          .forEach(
              line ->
                  targets
                      .computeIfAbsent(
                          new Target(relativePath, label(kind.get(), line)),
                          target -> new ArrayList<>())
                      .add(line));
    }
    return targets;
  }

  /**
   * @return 表示名（例: {@code table sample.employee}）
   */
  private String label(SnapshotKind kind, String line) {
    return kind.label() + " " + kind.identify(serializer.deserialize(line));
  }

  private List<String> labels(Map<Target, List<String>> targets, Predicate<Target> condition) {
    return targets.keySet().stream()
        .filter(condition)
        .sorted(TARGET_ORDER)
        .map(Target::label)
        .toList();
  }

  /**
   * 差分の対象（オブジェクトまたはファイル）<br>
   * 同名のオブジェクトが別のファイル（別DB等）に存在しても取り違えないよう、所属するファイルも含めて識別する
   */
  private record Target(Path file, String label) {}
}
