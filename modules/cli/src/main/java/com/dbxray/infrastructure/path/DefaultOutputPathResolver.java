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
 * 解決したパスが起点のディレクトリの外を指さないことを確かめる（DB由来の名前は{@link com.dbxray.domain.service.path.PathSegments}で置き換え済みのため、外を指すのは規則の不具合）
 */
public class DefaultOutputPathResolver implements OutputPathResolver {

  private static final String DEFAULT_OUTPUT_DIRECTORY = "./output";

  /** {@inheritDoc} */
  @Override
  public Path resolveOutputBaseDir(String outputPath) {
    return Optional.ofNullable(outputPath)
        .filter(Predicate.not(String::isBlank))
        .map(Paths::get)
        .orElse(Paths.get(DEFAULT_OUTPUT_DIRECTORY));
  }

  /** {@inheritDoc} */
  @Override
  public boolean isRemovableOutputDir(Path outputBaseDir) {
    final Path absolute = outputBaseDir.toAbsolutePath().normalize();
    final Path cwd = Path.of("").toAbsolutePath().normalize();
    final Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    // startsWithはPathの要素単位で比べるため、カレント・ホーム自体とその上位のディレクトリだけが一致する。
    // 別のドライブのルート（Windows）はカレント・ホームの上位にならないため、ルートは別に判定する
    return absolute.getParent() != null && !cwd.startsWith(absolute) && !home.startsWith(absolute);
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveDatabaseDirectory(OutputRoot outputRoot) {
    return within(
        outputRoot.baseDir(), DocumentLocations.databaseDirectory(outputRoot.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveTableDefinitionDirectory(OutputRoot outputRoot, TableEntity table) {
    return resolveTableDefinitionFile(outputRoot, table).getParent();
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveTableDefinitionFile(OutputRoot outputRoot, TableEntity table) {
    return within(
        resolveDatabaseDirectory(outputRoot), DocumentLocations.tableDefinitionFile(table));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveListFile(OutputRoot outputRoot, ListDocumentType type) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.listFile(type, outputRoot.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveErDiagramFile(OutputRoot outputRoot, String schemaName) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.erDiagramFile(outputRoot.baseInfo().dbName(), schemaName));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveErDiagramGroupFile(OutputRoot outputRoot, String schemaName, int groupNumber) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.erDiagramGroupFile(
            outputRoot.baseInfo().dbName(), schemaName, groupNumber));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveViewpointFile(OutputRoot outputRoot, Viewpoint viewpoint) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.viewpointFile(outputRoot.baseInfo().dbName(), viewpoint));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveReadmeFile(OutputRoot outputRoot) {
    return within(resolveDatabaseDirectory(outputRoot), DocumentLocations.readmeFile());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolvePageFile(Path file, int pageNumber) {
    return file.resolveSibling(
        DocumentLocations.pageFile(String.valueOf(file.getFileName()), pageNumber));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSchemaObjectDirectory(
      OutputRoot outputRoot, String schemaName, ListDocumentType kind) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.schemaObjectDirectory(schemaName, kind));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSchemaObjectFile(
      OutputRoot outputRoot, String schemaName, ListDocumentType kind, String name) {
    return within(
        resolveDatabaseDirectory(outputRoot),
        DocumentLocations.schemaObjectFile(schemaName, kind, name));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotDirectory(Path outputBaseDir) {
    return outputBaseDir.resolve(SnapshotLocations.snapshotDirectory());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotDatabaseFile(OutputRoot outputRoot) {
    return within(
        resolveSnapshotDirectory(outputRoot.baseDir()),
        SnapshotLocations.databaseFile(outputRoot.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveSnapshotFile(OutputRoot outputRoot, String schemaName, SnapshotKind kind) {
    return within(
        resolveSnapshotDirectory(outputRoot.baseDir()),
        SnapshotLocations.objectFile(outputRoot.baseInfo().dbName(), schemaName, kind));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SnapshotKind> resolveSnapshotKind(Path snapshotFile) {
    return SnapshotLocations.kindOf(String.valueOf(snapshotFile.getFileName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveInsightsDirectory(Path outputBaseDir) {
    return outputBaseDir.resolve(InsightLocations.insightsDirectory());
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveViewpointsInsightFile(OutputRoot outputRoot) {
    return within(
        resolveInsightsDirectory(outputRoot.baseDir()),
        InsightLocations.viewpointsFile(outputRoot.baseInfo().dbName()));
  }

  /** {@inheritDoc} */
  @Override
  public Path resolveFunctionTableUsagesInsightFile(OutputRoot outputRoot, String schemaName) {
    return within(
        resolveInsightsDirectory(outputRoot.baseDir()),
        InsightLocations.functionTableUsagesFile(outputRoot.baseInfo().dbName(), schemaName));
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
