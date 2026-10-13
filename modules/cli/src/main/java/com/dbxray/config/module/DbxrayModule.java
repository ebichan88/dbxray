package com.dbxray.config.module;

import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.repository.SidecarRepository;
import com.dbxray.domain.service.UnifiedDiffGenerator;
import com.dbxray.domain.service.export.InsightExportSinkFactory;
import com.dbxray.domain.service.export.MarkdownExportSinkFactory;
import com.dbxray.domain.service.export.SnapshotExportSinkFactory;
import com.dbxray.domain.service.insight.InsightWriter;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.snapshot.SchemaSnapshotWriter;
import com.dbxray.domain.service.snapshot.SnapshotDiff;
import com.dbxray.domain.service.snapshot.SnapshotSerializer;
import com.dbxray.domain.service.target.ExportTargetConsistency;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.domain.service.writer.erdiagram.ErDiagramWriter;
import com.dbxray.domain.service.writer.objectlist.ObjectListWriter;
import com.dbxray.domain.service.writer.readme.ReadmeWriter;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionWriter;
import com.dbxray.domain.service.writer.viewpoint.ViewpointWriter;
import com.dbxray.infrastructure.file.repository.LocalFileRepository;
import com.dbxray.infrastructure.file.repository.SidecarYamlRepository;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.infrastructure.snapshot.JacksonSnapshotSerializer;
import com.google.inject.AbstractModule;
import java.time.Clock;

/**
 * DB種別に依存しない依存関係を束縛するモジュール<br>
 * DBへ接続する前（入力の検証の時点）に組み立てるDIコンテナの束縛を定義する。ファイル入出力・出力先パスの解決・ドメインサービス等、
 * 接続先のDB種別で実装が変わらないものはここに束縛する。
 * DB種別が決まってから束縛するもの（{@link com.dbxray.domain.repository.CatalogRepository}と、それに依存するユースケース）は、
 * このモジュールで組み立てたコンテナの子として{@link DatabaseDependentModule}で束縛する
 */
public class DbxrayModule extends AbstractModule {

  /** {@inheritDoc} */
  @Override
  protected void configure() {
    bind(FileRepository.class).to(LocalFileRepository.class);
    bind(SidecarRepository.class).to(SidecarYamlRepository.class);
    bind(OutputPathResolver.class).to(DefaultOutputPathResolver.class);
    bind(SnapshotSerializer.class).to(JacksonSnapshotSerializer.class);
    // ドキュメントの生成日は実行環境のタイムゾーンでの日付とする
    bind(Clock.class).toInstance(Clock.systemDefaultZone());
    bind(PagedSectionWriter.class);
    bind(TableDefinitionWriter.class);
    bind(ErDiagramWriter.class);
    bind(ObjectListWriter.class);
    bind(ViewpointWriter.class);
    bind(ReadmeWriter.class);
    bind(SchemaSnapshotWriter.class);
    bind(SnapshotDiff.class);
    bind(UnifiedDiffGenerator.class);
    bind(ExportTargetConsistency.class);
    bind(InsightWriter.class);
    bind(MarkdownExportSinkFactory.class);
    bind(SnapshotExportSinkFactory.class);
    bind(InsightExportSinkFactory.class);
  }
}
