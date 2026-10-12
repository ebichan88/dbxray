package com.dbxray.domain.service.writer.viewpoint;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.relation.RenderingPlan;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.domain.service.writer.PagedSectionWriter.PageLayout;
import com.dbxray.domain.service.writer.PagedSectionWriter.PagedSection;
import jakarta.inject.Inject;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 観点ページ（観点ごとのER図・所属テーブル）と観点一覧を書き込むクラス<br>
 * 利用する情報はテーブル一覧と関連の一覧のみで、テーブル詳細を必要としない（スキーマ別ER図と同じ）
 */
public class ViewpointWriter {

  /** 観点ページの分割ページから本体ページへ戻るリンクの表示名 */
  private static final String VIEWPOINT_BACK_LABEL = "観点へ";

  private static final Logger logger = LogManager.getLogger(ViewpointWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final PagedSectionWriter pagedSectionWriter;

  @Inject
  public ViewpointWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      PagedSectionWriter pagedSectionWriter) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.pagedSectionWriter = pagedSectionWriter;
  }

  /**
   * 観点ページと観点一覧の書き込み処理を行うメソッド<br>
   * 観点が1件も無い場合に出力しないことの判定は呼び出し側（出力する一覧の決定）が行う
   *
   * @param viewpoints サイドカーYAMLで宣言された観点
   * @param foreignKeys 出力対象のテーブル同士の関連（外部キー・論理リレーション）
   * @param boxes ER図のテーブルの箱に表示する内容
   * @param outputRoot 出力先ベースディレクトリとデータベース基本情報
   * @param limit 1つの図に描画するノード数の上限
   */
  public void writeViewpoints(
      Viewpoints viewpoints,
      Tables tables,
      ForeignKeys foreignKeys,
      DiagramBoxes boxes,
      OutputRoot outputRoot,
      NodeLimit limit) {
    // 観点ページと観点一覧（テーブル数）の双方が同じ出力内容を用いるため、観点ごとに1回だけ求める
    final List<ViewpointContent> contents =
        viewpoints.stream().map(viewpoint -> viewpoint.resolve(tables, foreignKeys)).toList();
    contents.forEach(content -> writeViewpointPage(content, boxes, outputRoot, limit));
    writeViewpointIndex(contents, outputRoot);
  }

  /**
   * 観点1つ分のページを書き込むメソッド<br>
   * 所属テーブル同士の関連をER図に描き、所属テーブルと、観点外のテーブルとの関連を一覧で掲載する。
   * ER図の描画を省略した場合は、代替として所属テーブル同士の関連を一覧で掲載する。<br>
   * 行数の多い表の分割は所属テーブルの一覧のみで行う（分割ページのファイル名は本体ページから決まるため、1ページで分割できる表は1つに限られる）。
   * 関連の一覧は、人が選んだテーブルのまとまりに関わるものに限られ、分割が必要になる規模にはならない想定とする
   *
   * @param outputRoot 出力先ベースディレクトリとデータベース基本情報
   * @param limit 1つの図に描画するノード数の上限
   */
  private void writeViewpointPage(
      ViewpointContent content, DiagramBoxes boxes, OutputRoot outputRoot, NodeLimit limit) {
    final PageLayout layout =
        new PageLayout(
            ViewpointTemplates.fileHeader(content.viewpoint(), outputRoot.baseInfo()),
            outputPathResolver.resolveViewpointFile(outputRoot, content.viewpoint()),
            VIEWPOINT_BACK_LABEL);
    final String tableSection =
        content.tables().isEmpty()
            ? ViewpointTemplates.noTables()
            : pagedSectionWriter.writePagedSection(
                new PagedSection<>(
                    ViewpointTemplates.tableHeading(),
                    ViewpointTemplates.tableHeader(),
                    content.tables(),
                    ViewpointTemplates::tableLine),
                layout);
    final RenderingPlan plan = content.relations().planRendering(limit);
    final List<String> contents =
        List.of(
            layout.fileHeader(),
            ViewpointTemplates.baseInfo(outputRoot.baseInfo()),
            ViewpointTemplates.description(content.viewpoint()), // 説明
            ViewpointTemplates.erDiagram(plan, boxes), // ER図（描画結果または省略メッセージ）
            plan instanceof RenderingPlan.Omit
                ? ViewpointTemplates.relations(plan.group().foreignKeys())
                : "", // ER図の代替の関連一覧
            tableSection, // 所属テーブル
            ViewpointTemplates.outsideRelations(content.outsideRelations()), // 観点外のテーブルとの関連
            ViewpointTemplates.footer(outputRoot.baseInfo()));
    fileRepository.writeFile(layout.file(), contents);
    logger.debug("Wrote a viewpoint document. [filePath={}]", layout.file());
  }

  /**
   * 観点一覧を書き込むメソッド
   *
   * @param contents 観点ごとの出力内容（宣言順）
   * @param outputRoot 出力先ベースディレクトリとデータベース基本情報
   */
  private void writeViewpointIndex(List<ViewpointContent> contents, OutputRoot outputRoot) {
    final PageLayout layout =
        new PageLayout(
            ViewpointTemplates.indexFileHeader(outputRoot.baseInfo()),
            outputPathResolver.resolveListFile(outputRoot, ListDocumentType.VIEWPOINT),
            ListDocumentType.VIEWPOINT.backLinkLabel());
    final String indexSection =
        pagedSectionWriter.writePagedSection(
            new PagedSection<>(
                ViewpointTemplates.indexHeading(),
                ViewpointTemplates.indexHeader(),
                contents,
                (no, content) -> ViewpointTemplates.indexLine(no, content, outputRoot.baseInfo())),
            layout);
    final List<String> fileContents =
        List.of(
            layout.fileHeader(),
            ViewpointTemplates.baseInfo(outputRoot.baseInfo()),
            indexSection, // 観点一覧
            ViewpointTemplates.indexFooter(outputRoot.baseInfo()));
    fileRepository.writeFile(layout.file(), fileContents);
  }
}
