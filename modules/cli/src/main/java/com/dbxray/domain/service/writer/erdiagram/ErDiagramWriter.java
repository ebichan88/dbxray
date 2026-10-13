package com.dbxray.domain.service.writer.erdiagram;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.relation.ForeignKeyGroups;
import com.dbxray.domain.model.relation.ForeignKeyGroups.PageComposition;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.relation.RenderingPlan;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.domain.service.writer.PagedSectionWriter.PageLayout;
import com.dbxray.domain.service.writer.PagedSectionWriter.PagedSection;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** スキーマ別ER図（全体ER図）とその索引を書き込むクラス */
public class ErDiagramWriter {

  /** ER図の分割ページから本体ページへ戻るリンクの表示名 */
  private static final String ER_DIAGRAM_BACK_LABEL = "ER図へ";

  private static final Logger logger = LogManager.getLogger(ErDiagramWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final PagedSectionWriter pagedSectionWriter;

  @Inject
  public ErDiagramWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      PagedSectionWriter pagedSectionWriter) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.pagedSectionWriter = pagedSectionWriter;
  }

  /**
   * 1つの図にすべてのテーブルを載せるとMermaidが描画できる規模を超えるため、スキーマ単位に分割して出力し、それらへのリンクをまとめた索引ファイルを併せて出力する。
   * 利用する情報はテーブル一覧・外部キー一覧・テーブルの箱に表示する関連カラムのみで、テーブル詳細を必要としない。
   * テーブルが存在しない場合に出力しないことの判定は呼び出し側（出力する一覧の決定）が行う
   *
   * @param limit 1つの図に描画するノード数の上限
   */
  public void writeErDiagram(
      Tables tables,
      ForeignKeys foreignKeys,
      DiagramBoxes boxes,
      OutputRoot outputRoot,
      NodeLimit limit) {
    final Map<String, List<TableEntity>> tablesBySchema = tables.bySchema();
    // 外部キーのスキーマ単位のグループ化は1度だけ行う。スキーマごとに全件を走査すると
    // 外部キー数×スキーマ数の走査となり、対象範囲が広い場合に処理時間が膨らむ
    final Map<String, List<ForeignKeyEntity>> foreignKeysBySchema = foreignKeys.groupBySchema();
    // ER図には他スキーマのテーブルも箱として登場するため、スキーマを問わず全テーブルから引けるようにする
    final DiagramPages pages = new DiagramPages(tables, boxes, outputRoot, limit);
    tablesBySchema
        .keySet()
        .forEach(
            schemaName ->
                writeSchemaErDiagram(
                    schemaName, foreignKeysBySchema.getOrDefault(schemaName, List.of()), pages));
    writeErDiagramIndex(tablesBySchema, foreignKeys.crossSchema(), outputRoot);
  }

  /**
   * ノード数が上限を超える場合は、連結成分を1枚に収まる範囲でまとめ直したグループごとに分割して出力する
   *
   * @param relatedForeignKeys 当該スキーマのテーブルが関与する外部キー（他スキーマとの関連を含む）のリスト
   */
  private void writeSchemaErDiagram(
      String schemaName, List<ForeignKeyEntity> relatedForeignKeys, DiagramPages pages) {
    final OutputRoot outputRoot = pages.outputRoot();
    switch (ForeignKeyGroups.compose(relatedForeignKeys, pages.limit())) {
      case PageComposition.Single(ForeignKeyGroup group) ->
          writeErDiagramPage(
              new PageLayout(
                  ErDiagramTemplates.schemaFileHeader(schemaName, outputRoot.baseInfo()),
                  outputPathResolver.resolveErDiagramFile(outputRoot, schemaName),
                  ER_DIAGRAM_BACK_LABEL),
              group,
              ErDiagramTemplates.schemaFooter(outputRoot.baseInfo()),
              pages);
      case PageComposition.Grouped(List<ForeignKeyGroup> groups, int nodeCount) -> {
        for (int groupNumber = 1; groupNumber <= groups.size(); groupNumber++) {
          writeGroupErDiagram(schemaName, groupNumber, groups.get(groupNumber - 1), pages);
        }
        writeSchemaGroupIndex(schemaName, groups, nodeCount, pages);
      }
    }
  }

  /**
   * @param groupNumber グループ番号（1始まり）
   */
  private void writeGroupErDiagram(
      String schemaName, int groupNumber, ForeignKeyGroup group, DiagramPages pages) {
    final OutputRoot outputRoot = pages.outputRoot();
    writeErDiagramPage(
        new PageLayout(
            ErDiagramTemplates.groupFileHeader(schemaName, groupNumber, outputRoot.baseInfo()),
            outputPathResolver.resolveErDiagramGroupFile(outputRoot, schemaName, groupNumber),
            ER_DIAGRAM_BACK_LABEL),
        group,
        ErDiagramTemplates.groupFooter(schemaName, outputRoot.baseInfo()),
        pages);
  }

  /**
   * スキーマ全体のページとグループ別のページで本文の構成（図または代替の外部キー一覧）が同じため共通化する。
   * ER図を描画した場合は図中の箱の一覧を、描画を省略した場合は代替として外部キーの一覧を掲載する
   */
  private void writeErDiagramPage(
      PageLayout layout, ForeignKeyGroup group, String footer, DiagramPages pages) {
    final RenderingPlan plan = group.planRendering(pages.limit());
    final PagedSection<?> detail =
        switch (plan) {
          case RenderingPlan.Omit omit ->
              new PagedSection<>(
                  ErDiagramTemplates.foreignKeyHeading(),
                  ErDiagramTemplates.foreignKeyTableHeader(),
                  omit.group().foreignKeys(),
                  ErDiagramTemplates::foreignKeyTableLine);
          case RenderingPlan.Draw draw ->
              new PagedSection<>(
                  ErDiagramTemplates.diagramTableHeading(),
                  ErDiagramTemplates.diagramTableHeader(),
                  draw.group().nodes(),
                  (no, key) ->
                      ErDiagramTemplates.diagramTableLine(
                          no, key, pages.tables().find(key).orElse(null)));
        };
    final String detailSection = pagedSectionWriter.writePagedSection(detail, layout);
    final List<String> contents =
        List.of(
            layout.fileHeader(),
            ErDiagramTemplates.baseInfo(pages.outputRoot().baseInfo()),
            ErDiagramTemplates.erDiagram(plan, pages.boxes()), // ER図（描画結果または省略メッセージ）
            detailSection, // 掲載テーブル または 外部キー一覧
            footer);
    fileRepository.writeFile(layout.file(), contents);
    logger.debug("Wrote an ER diagram. [filePath={}]", layout.file());
  }

  private void writeSchemaGroupIndex(
      String schemaName, List<ForeignKeyGroup> groups, int nodeCount, DiagramPages pages) {
    final OutputRoot outputRoot = pages.outputRoot();
    final List<String> contents =
        List.of(
            ErDiagramTemplates.schemaFileHeader(schemaName, outputRoot.baseInfo()),
            ErDiagramTemplates.baseInfo(outputRoot.baseInfo()),
            ErDiagramTemplates.groupedMessage(nodeCount, pages.limit(), groups.size()), // 分割の説明
            ErDiagramTemplates.groupIndex(schemaName, groups, outputRoot.baseInfo()), // グループ一覧
            ErDiagramTemplates.schemaFooter(outputRoot.baseInfo()));
    fileRepository.writeFile(
        outputPathResolver.resolveErDiagramFile(outputRoot, schemaName), contents);
  }

  private void writeErDiagramIndex(
      Map<String, List<TableEntity>> tablesBySchema,
      List<ForeignKeyEntity> crossSchemaForeignKeys,
      OutputRoot outputRoot) {
    final PagedSection<ForeignKeyEntity> crossSchemaSection =
        new PagedSection<>(
            ErDiagramTemplates.crossSchemaForeignKeyHeading(),
            ErDiagramTemplates.foreignKeyTableHeader(),
            crossSchemaForeignKeys,
            ErDiagramTemplates::foreignKeyTableLine);
    final PageLayout layout =
        new PageLayout(
            ErDiagramTemplates.fileHeader(
                ListDocumentType.ER_DIAGRAM.title(), outputRoot.baseInfo()),
            outputPathResolver.resolveListFile(outputRoot, ListDocumentType.ER_DIAGRAM),
            ListDocumentType.ER_DIAGRAM.backLinkLabel());
    final String crossSchemaDetail =
        pagedSectionWriter.writePagedSection(crossSchemaSection, layout);
    final List<String> contents =
        List.of(
            layout.fileHeader(),
            ErDiagramTemplates.baseInfo(outputRoot.baseInfo()),
            ErDiagramTemplates.schemaIndex(outputRoot.baseInfo(), tablesBySchema), // スキーマ別ER図へのリンク
            crossSchemaDetail, // スキーマ跨ぎの外部キー
            ErDiagramTemplates.indexFooter(outputRoot.baseInfo()));
    fileRepository.writeFile(layout.file(), contents);
  }

  /**
   * 1スキーマ分のER図のページ（スキーマ全体・グループ別）を書くときに共通で使う値
   *
   * @param tables 出力対象のテーブル（図の箱のテーブル定義書へのリンクに使う。他スキーマのテーブルを含む）
   * @param boxes ER図のテーブルの箱に表示する内容
   * @param limit 1つの図に描画するノード数の上限
   */
  private record DiagramPages(
      Tables tables, DiagramBoxes boxes, OutputRoot outputRoot, NodeLimit limit) {}
}
