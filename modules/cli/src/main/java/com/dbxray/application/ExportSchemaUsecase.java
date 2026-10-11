package com.dbxray.application;

import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.export.InsightExportSinkFactory;
import com.dbxray.domain.service.export.MarkdownExportSinkFactory;
import com.dbxray.domain.service.export.SnapshotExportSinkFactory;
import com.dbxray.domain.service.path.OutputPathResolver;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DBドキュメント出力（通常実行）のユースケースクラス<br>
 * DBから取得したスキーマ情報を、Markdownのドキュメントとスキーマのスナップショットとして出力先へ書き出す。 取得・書き出しの段取りは{@link
 * SchemaExportPipeline}に委ねる
 */
public class ExportSchemaUsecase {

  private static final Logger logger = LogManager.getLogger(ExportSchemaUsecase.class);
  private final SchemaExportPipeline schemaExportPipeline;
  private final MarkdownExportSinkFactory markdownSinkFactory;
  private final SnapshotExportSinkFactory snapshotSinkFactory;
  private final InsightExportSinkFactory insightSinkFactory;
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;

  @Inject
  public ExportSchemaUsecase(
      SchemaExportPipeline schemaExportPipeline,
      MarkdownExportSinkFactory markdownSinkFactory,
      SnapshotExportSinkFactory snapshotSinkFactory,
      InsightExportSinkFactory insightSinkFactory,
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver) {
    this.schemaExportPipeline = schemaExportPipeline;
    this.markdownSinkFactory = markdownSinkFactory;
    this.snapshotSinkFactory = snapshotSinkFactory;
    this.insightSinkFactory = insightSinkFactory;
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
  }

  /** DBから取得したスキーマ情報を、Markdownのドキュメントとスキーマのスナップショットとして出力するメソッド */
  public void exportSchema(ExportSchemaRequest request) {
    final Path outputBaseDir = outputPathResolver.resolveBaseOutputDir(request.outputPath());
    final ExportTargets targets =
        schemaExportPipeline.fetchTargets(request.targetSelection(), request.sidecarPath());
    final DiagramBoxes diagramBoxes =
        schemaExportPipeline.fetchDiagramBoxes(targets, request.chunkSize());
    if (!request.previewFeatures().isEmpty()) {
      logger.info(
          "Preview features enabled. [features={}]",
          request.previewFeatures().stream()
              .sorted()
              .map(PreviewFeature::id)
              .collect(Collectors.joining(",")));
    }
    if (request.rmDist()) {
      // 削除は一括取得（サイドカーの読み込みを含む）に成功してから行う。
      // 取得に失敗した場合に、既存の出力だけが削除されて何も残らない状態にしないため
      removeOutputBaseDir(outputBaseDir);
    }
    schemaExportPipeline.export(
        targets,
        List.of(
            markdownSinkFactory.create(
                outputBaseDir,
                NodeLimit.of(request.erDiagramMaxNodes()),
                request.erDiagramDistance(),
                diagramBoxes,
                targets.foreignKeys()),
            snapshotSinkFactory.create(outputBaseDir),
            insightSinkFactory.create(outputBaseDir)),
        request.chunkSize(),
        request.previewFeatures());
  }

  /**
   * {@code --rm-dist}指定時に、出力先ベースディレクトリを書き込み前に削除するメソッド<br>
   * 削除されたテーブル等の残骸ファイルを残さないため、書き込み前にディレクトリごと削除する。
   * 削除してよいディレクトリか（既存のファイルを指していないか、ルート・ホームディレクトリ等でないか）は、 ユースケースを呼ぶ前に入口で検証済みであること
   */
  private void removeOutputBaseDir(Path outputBaseDir) {
    logger.info(
        "Removing existing output directory before export. [outputBaseDir={}]",
        outputBaseDir.toAbsolutePath().normalize());
    fileRepository.deleteDirectory(outputBaseDir);
  }
}
