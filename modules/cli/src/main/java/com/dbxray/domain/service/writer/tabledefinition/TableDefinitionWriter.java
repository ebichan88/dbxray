package com.dbxray.domain.service.writer.tabledefinition;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.DiagramNeighborhood;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.domain.service.writer.PagedSectionWriter.PageLayout;
import com.dbxray.domain.service.writer.PagedSectionWriter.PagedSection;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** テーブル一覧・テーブル定義書を書き込むクラス */
public class TableDefinitionWriter {

  private static final Logger logger = LogManager.getLogger(TableDefinitionWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final PagedSectionWriter pagedSectionWriter;

  @Inject
  public TableDefinitionWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      PagedSectionWriter pagedSectionWriter) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.pagedSectionWriter = pagedSectionWriter;
  }

  /**
   * テーブル一覧の書き込み処理を行うメソッド<br>
   * 行数がMarkdownの表に表示できる最大件数を超える場合は、別ファイルへ分割し、本体ページにはリンクのみを掲載する
   *
   * @param outputRoot 出力先ベースディレクトリとデータベース基本情報
   * @param relatedDocuments 「関連ドキュメント」としてリンクを掲載する一覧の種別（掲載順）
   */
  public void writeTableDefinitionList(
      Tables tables, OutputRoot outputRoot, List<ListDocumentType> relatedDocuments) {
    fileRepository.createDirectory(outputPathResolver.resolveDatabaseDirectory(outputRoot));
    final PagedSection<TableEntity> section =
        new PagedSection<>(
            "テーブル情報",
            TableDefinitionListTemplates.tableListTableHeader(),
            tables.asList(),
            TableDefinitionListTemplates::tableListLine);
    final PageLayout layout =
        new PageLayout(
            TableDefinitionListTemplates.fileHeader(outputRoot.baseInfo()),
            outputPathResolver.resolveListFile(outputRoot, ListDocumentType.TABLE),
            ListDocumentType.TABLE.backLinkLabel());
    final String tableListSection = pagedSectionWriter.writePagedSection(section, layout);
    final List<String> contents =
        List.of(
            layout.fileHeader(), // ヘッダー
            TableDefinitionListTemplates.baseInfo(outputRoot.baseInfo()), // 基本情報
            TableDefinitionListTemplates.relatedDocuments(
                outputRoot.baseInfo(), relatedDocuments), // 関連ドキュメント
            tableListSection);
    fileRepository.writeFile(layout.file(), contents);
  }

  /**
   * テーブル定義の書き込み処理を行うメソッド
   *
   * @param content テーブル定義出力に必要な情報をまとめたレコード
   * @param neighborhood ER図に描く関連（描画距離以内のテーブルが持つ関連）
   * @param boxes ER図のテーブルの箱に表示する内容（関連テーブルの論理テーブル名・関連カラム）
   */
  public void writeTableDefinition(
      TableDefinitionContent content,
      DiagramNeighborhood neighborhood,
      DiagramBoxes boxes,
      OutputRoot outputRoot) {
    final Path directoryPath =
        outputPathResolver.resolveTableDefinitionDirectory(outputRoot, content.table());
    final Path filePath =
        outputPathResolver.resolveTableDefinitionFile(outputRoot, content.table());
    final List<String> contents =
        List.of(
            TableDefinitionTemplates.fileHeader(content.table()), // ヘッダー
            TableDefinitionTemplates.baseInfo(content.baseInfo()), // 基本情報
            TableDefinitionTemplates.tableExplanation(content.annotation()), // テーブル説明
            TableDefinitionTemplates.tableInfo(content.table(), content.annotation()), // テーブル情報
            TableDefinitionTemplates.viewpoints(content.viewpoints(), content.baseInfo()), // 所属する観点
            TableDefinitionTemplates.columns(content.columns(), content.annotation()), // カラム情報
            TableDefinitionTemplates.partitions(content.table(), content.partitions()), // パーティション情報
            TableDefinitionTemplates.view(content.table()), // View情報
            TableDefinitionTemplates.referencedTables(content.referencedTables()), // 参照するテーブル
            TableDefinitionTemplates.indexes(content.indexes()), // インデックス情報
            TableDefinitionTemplates.constraints(content.constraints()), // 制約情報
            TableDefinitionTemplates.foreignKeys(content.foreignKeys()), // 外部キー情報
            TableDefinitionTemplates.logicalRelations(content.logicalRelations()), // 論理リレーション情報
            TableDefinitionTemplates.incomingRelations(content.incomingRelations()), // 被参照情報
            TableDefinitionTemplates.referencingViews(content.referencingViews()), // 参照しているビュー
            TableDefinitionTemplates.triggers(content.triggers()), // トリガー情報
            TableDefinitionTemplates.erDiagram(
                content.table(), content.columns(), neighborhood, boxes), // ER図
            TableDefinitionTemplates.footer(content.baseInfo()) // フッター
            );
    fileRepository.createDirectory(directoryPath);
    fileRepository.writeFile(filePath, contents);
    logger.debug("exportTableDefinition complete. [filePath={}]", filePath.toString());
  }
}
