package com.dbxray;

import com.dbxray.application.CheckDocumentDiffUsecase;
import com.dbxray.application.ExportSchemaUsecase;
import com.dbxray.application.SchemaExportPipeline;
import com.dbxray.domain.repository.CatalogRepository;
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
import com.dbxray.infrastructure.db.repository.OracleCatalogRepository;
import com.dbxray.infrastructure.db.repository.PostgresCatalogRepository;
import com.dbxray.infrastructure.db.type.DatabaseType;
import com.dbxray.infrastructure.file.repository.LocalFileRepository;
import com.dbxray.infrastructure.file.repository.SidecarYamlRepository;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.infrastructure.snapshot.JacksonSnapshotSerializer;
import com.dbxray.presentation.ExportSchemaController;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 依存関係を手で組み立てるクラス（composition root）<br>
 * 組み立ては2段階に分かれる。DB種別に依存しない部品（入力の検証に使う{@link #outputDirectoryValidator()}）は、
 * DBへ接続する前に取得できる。DB種別で実装が変わる部品（{@link CatalogRepository}と、それに依存するユースケース）は、
 * 接続してDB種別が分かってから{@link #controller}で組み立てる。
 * 全レイヤーの具象クラスを参照するため、すべてのレイヤーを参照できるエントリーポイントと同じパッケージに置く
 */
public final class Components {

  private final Clock clock;
  private final FileRepository fileRepository = new LocalFileRepository();
  private final SidecarRepository sidecarRepository = new SidecarYamlRepository();
  private final OutputPathResolver outputPathResolver = new DefaultOutputPathResolver();
  private final SnapshotSerializer snapshotSerializer = new JacksonSnapshotSerializer();

  public Components(Clock clock) {
    this.clock = clock;
  }

  /** ドキュメントの生成日を、実行環境のタイムゾーンでの日付とするインスタンスを生成するメソッド */
  static Components create() {
    return new Components(Clock.systemDefaultZone());
  }

  /** 出力先を検証するクラスを生成するメソッド（DB種別に依存しないため、DBへ接続する前に使える） */
  OutputDirectoryValidator outputDirectoryValidator() {
    return new OutputDirectoryValidator(outputPathResolver, fileRepository);
  }

  /**
   * 接続先のDB種別に応じた部品を組み立て、コントローラーを生成するメソッド
   *
   * @param databaseType 接続先DBの種別（{@link CatalogRepository}の実装の選択に用いる）
   * @param sqlSessionFactory 接続先DBのSqlSessionFactory（エントリーポイントで1回だけ生成したものを、リポジトリで使い回す）
   */
  public ExportSchemaController controller(
      DatabaseType databaseType, SqlSessionFactory sqlSessionFactory) {
    final SchemaExportPipeline pipeline =
        new SchemaExportPipeline(
            catalogRepository(databaseType, sqlSessionFactory),
            sidecarRepository,
            new ExportTargetConsistency(),
            clock);

    final PagedSectionWriter pagedSectionWriter =
        new PagedSectionWriter(fileRepository, outputPathResolver);
    final MarkdownExportSinkFactory markdownSinkFactory =
        new MarkdownExportSinkFactory(
            new TableDefinitionWriter(fileRepository, outputPathResolver, pagedSectionWriter),
            new ErDiagramWriter(fileRepository, outputPathResolver, pagedSectionWriter),
            new ObjectListWriter(fileRepository, outputPathResolver, pagedSectionWriter),
            new ViewpointWriter(fileRepository, outputPathResolver, pagedSectionWriter),
            new ReadmeWriter(fileRepository, outputPathResolver));
    final SnapshotExportSinkFactory snapshotSinkFactory =
        new SnapshotExportSinkFactory(
            new SchemaSnapshotWriter(fileRepository, outputPathResolver, snapshotSerializer));
    final InsightExportSinkFactory insightSinkFactory =
        new InsightExportSinkFactory(
            new InsightWriter(fileRepository, outputPathResolver, snapshotSerializer));
    final SnapshotDiff snapshotDiff =
        new SnapshotDiff(
            fileRepository, outputPathResolver, snapshotSerializer, new UnifiedDiffGenerator());

    return new ExportSchemaController(
        new ExportSchemaUsecase(
            pipeline,
            markdownSinkFactory,
            snapshotSinkFactory,
            insightSinkFactory,
            fileRepository,
            outputPathResolver),
        new CheckDocumentDiffUsecase(
            pipeline, snapshotSinkFactory, snapshotDiff, fileRepository, outputPathResolver),
        clock);
  }

  /** DB種別に対応する{@link CatalogRepository}の実装を生成するメソッド（DB種別を足したとき、ここの書き漏れをコンパイルで検知する） */
  private static CatalogRepository catalogRepository(
      DatabaseType databaseType, SqlSessionFactory sqlSessionFactory) {
    return switch (databaseType) {
      case POSTGRESQL -> new PostgresCatalogRepository(sqlSessionFactory);
      case ORACLE -> new OracleCatalogRepository(sqlSessionFactory);
    };
  }
}
