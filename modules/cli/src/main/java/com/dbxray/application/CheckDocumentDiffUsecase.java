package com.dbxray.application;

import com.dbxray.domain.model.snapshot.DiffResult;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.export.SnapshotExportSinkFactory;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.snapshot.SnapshotDiff;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * DB vs ドキュメントの差分検知（{@code --check}モード）のユースケースクラス<br>
 * DBからの取得は通常実行と同じ{@link SchemaExportPipeline}で行い、差分の判定に不要なMarkdownの描画・ER図の生成は行わず、
 * スナップショットのみを一時ディレクトリへ生成して、コミット済みのスナップショットとオブジェクト単位で比較する
 */
public class CheckDocumentDiffUsecase {

  private static final String CHECK_TEMP_DIR_PREFIX = "dbxray-check-";
  private final SchemaExportPipeline schemaExportPipeline;
  private final SnapshotExportSinkFactory snapshotSinkFactory;
  private final SnapshotDiff snapshotDiff;
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;

  @Inject
  public CheckDocumentDiffUsecase(
      SchemaExportPipeline schemaExportPipeline,
      SnapshotExportSinkFactory snapshotSinkFactory,
      SnapshotDiff snapshotDiff,
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver) {
    this.schemaExportPipeline = schemaExportPipeline;
    this.snapshotSinkFactory = snapshotSinkFactory;
    this.snapshotDiff = snapshotDiff;
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
  }

  /**
   * DBの現状から生成したスキーマのスナップショットと、{@code outputPath}配下の{@code snapshot/}に既にコミット済みの
   * スナップショットを比較し、差分（＝ドキュメントの再生成・コミット忘れ）を検知するメソッド<br>
   * DBからの取得は{@link ExportSchemaUsecase#exportSchema}と共通で、スナップショットのみを生成して
   * 一時ディレクトリへ出力し、オブジェクト（テーブル・関数等）単位で比較する。Markdownの描画・ER図の生成は行わない
   *
   * @param request DB vs ドキュメントの差分検知の入力
   */
  public DiffResult checkDocumentDiff(CheckDocumentDiffRequest request) {
    final Path committedDir = outputPathResolver.resolveBaseOutputDir(request.outputPath());
    final Path generatedDir = fileRepository.createTempDirectory(CHECK_TEMP_DIR_PREFIX);
    try {
      final ExportTargets targets =
          schemaExportPipeline.fetchTargets(request.targetSelection(), request.sidecarPath());
      // プレビューの機能はスナップショットを変えないため、差分検知では有効にしない
      schemaExportPipeline.export(
          targets,
          List.of(snapshotSinkFactory.create(generatedDir)),
          request.chunkSize(),
          Set.of());
      return snapshotDiff.compare(
          outputPathResolver.resolveSnapshotDirectory(generatedDir),
          outputPathResolver.resolveSnapshotDirectory(committedDir));
    } finally {
      fileRepository.deleteDirectory(generatedDir);
    }
  }
}
