package com.dbxray.infrastructure.path;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.path.InsightLocations;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.path.SnapshotLocations;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 出力パス解決のデフォルト実装<br>
 * Markdownドキュメントのファイル名・配置は{@link DocumentLocations}、スナップショットのそれは{@link SnapshotLocations}の規則に従い、
 * 出力ベースディレクトリを起点に解決する。<br>
 * 解決したパスが起点のディレクトリの外を指さないことを確かめる（DB由来の名前は{@link
 * com.dbxray.domain.service.path.PathSegments}で置き換え済みのため、外を指すのは規則の不具合）
 */
public class DefaultOutputPathResolver implements OutputPathResolver {

  private static final String DEFAULT_OUTPUT_DIRECTORY = "./output";

  /** {@inheritDoc} */
  @Override
  public Path resolveBaseOutputDir(String outputPath) {
    return Optional.ofNullable(outputPath)
        .filter(Predicate.not(String::isBlank))
        .map(Paths::get)
        .orElse(Paths.get(DEFAULT_OUTPUT_DIRECTORY));
  }

  /** {@inheritDoc} */
  @Override
  public boolean isRemovableOutputDir(Path baseOutputDir) {
    final Path absolute = baseOutputDir.toAbsolutePath().normalize();
    final Path cwd = Path.of("").toAbsolutePath().normalize();
    final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    // startsWithはPathの要素単位で比べるため、カレント・ホーム自体とその上位のディレクトリだけが一致する。
    // 別のドライブのルート（Windows）はカレント・ホームの上位にならないため、ルートは別に判定する
    return absolute.getParent() != null && !cwd.startsWith(absolute) && !home.startsWith(absolute);
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveDatabaseDirectory(OutputRoot root) {
    return within(root.baseDir(), DocumentLocations.databaseDirectory(root.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveTableDefinitionDirectory(OutputRoot root, TableEntity table) {
    return resolveTableDefinitionFile(root, table).getParent();
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveTableDefinitionFile(OutputRoot root, TableEntity table) {
    return within(resolveDatabaseDirectory(root), DocumentLocations.tableDefinitionFile(table));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveListFile(OutputRoot root, ListDocumentType type) {
    return within(
        resolveDatabaseDirectory(root), DocumentLocations.listFile(type, root.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveErDiagramFile(OutputRoot root, String schemaName) {
    return within(
        resolveDatabaseDirectory(root),
        DocumentLocations.erDiagramFile(root.baseInfo().dbName(), schemaName));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveErDiagramGroupFile(OutputRoot root, String schemaName, int groupNo) {
    return within(
        resolveDatabaseDirectory(root),
        DocumentLocations.erDiagramGroupFile(root.baseInfo().dbName(), schemaName, groupNo));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveViewpointFile(OutputRoot root, Viewpoint viewpoint) {
    return within(
        resolveDatabaseDirectory(root),
        DocumentLocations.viewpointFile(root.baseInfo().dbName(), viewpoint));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveReadmeFile(OutputRoot root) {
    return within(resolveDatabaseDirectory(root), DocumentLocations.readmeFile());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolvePageFile(Path file, int pageIndex) {
    return file.resolveSibling(
        DocumentLocations.pageFile(String.valueOf(file.getFileName()), pageIndex));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSchemaObjectDirectory(
      OutputRoot root, String schemaName, ListDocumentType kind) {
    return within(
        resolveDatabaseDirectory(root), DocumentLocations.schemaObjectDirectory(schemaName, kind));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSchemaObjectFile(
      OutputRoot root, String schemaName, ListDocumentType kind, String name) {
    return within(
        resolveDatabaseDirectory(root), DocumentLocations.schemaObjectFile(schemaName, kind, name));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotDirectory(Path baseOutputDir) {
    return baseOutputDir.resolve(SnapshotLocations.snapshotDirectory());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotDatabaseFile(OutputRoot root) {
    return within(
        resolveSnapshotDirectory(root.baseDir()),
        SnapshotLocations.databaseFile(root.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotFile(OutputRoot root, String schemaName, SnapshotKind kind) {
    return within(
        resolveSnapshotDirectory(root.baseDir()),
        SnapshotLocations.objectFile(root.baseInfo().dbName(), schemaName, kind));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SnapshotKind> resolveSnapshotKind(Path snapshotFile) {
    return SnapshotLocations.kindOf(String.valueOf(snapshotFile.getFileName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveInsightsDirectory(Path baseOutputDir) {
    return baseOutputDir.resolve(InsightLocations.insightsDirectory());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveViewpointsInsightFile(OutputRoot root) {
    return within(
        resolveInsightsDirectory(root.baseDir()),
        InsightLocations.viewpointsFile(root.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveFunctionTableUsagesInsightFile(OutputRoot root, String schemaName) {
    return within(
        resolveInsightsDirectory(root.baseDir()),
        InsightLocations.functionTableUsagesFile(root.baseInfo().dbName(), schemaName));
  }

  /**
   * 起点のディレクトリから相対パスを解決し、起点の外を指さないことを確かめるメソッド
   *
   * @throws IllegalStateException 解決したパスが起点のディレクトリの外を指す場合
   */
  private static Path within(Path directory, String relativePath) {
    final Path resolved = directory.resolve(relativePath);
    // 相対パスのままnormalizeすると、出力先が"."の場合に空のパスになり比べられないため、絶対パスで比べる
    if (!resolved.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) {
      throw new IllegalStateException(
          "Resolved path points outside of the output directory. [directory="
              + directory
              + ", path="
              + resolved
              + "]");
    }
    return resolved;
  }
}
