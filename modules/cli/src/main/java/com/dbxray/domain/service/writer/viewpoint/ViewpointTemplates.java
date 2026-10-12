package com.dbxray.domain.service.writer.viewpoint;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.HORIZON;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RenderingPlan;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.writer.erdiagram.ErDiagramTemplates;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;
import java.util.List;

/**
 * 観点ページ・観点一覧の書き込みに利用するMarkdownのテンプレートを扱うクラス<br>
 * ER図の描画と、テーブル・関連の表の1行分は、スキーマ別ER図（{@link ErDiagramTemplates}）と同じものを用いる。
 * 観点は人が選んだテーブルのまとまりのため、スキーマ別ER図と異なり連結成分によるグループ分割は行わない
 */
public class ViewpointTemplates {

  /** 観点ページのファイルヘッダー */
  public static String fileHeader(Viewpoint viewpoint, BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.fileHeader(
        String.format("観点：%s（DB名：%s）", viewpoint.name(), baseInfo.dbName()));
  }

  /** 基本情報セクション */
  public static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /**
   * 説明セクション
   *
   * @return 説明が未指定の場合は空文字（セクションごと省く）
   */
  public static String description(Viewpoint viewpoint) {
    if (viewpoint.description().isEmpty()) {
      return "";
    }
    return "## 説明" + LINE_SEPARATOR_DOUBLE + viewpoint.description() + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * ER図セクション（Mermaid記法）<br>
   * 所属テーブル同士の関連のみを描画する。描画の内容・上限を超えた場合の扱いはスキーマ別ER図と同じ（{@link ErDiagramTemplates#erDiagram}）
   */
  public static String erDiagram(RenderingPlan plan, DiagramBoxes boxes) {
    if (plan.group().foreignKeys().isEmpty()) {
      return "## ER図"
          + LINE_SEPARATOR_DOUBLE
          + "所属テーブル同士の関連（外部キー・論理リレーション）はありません。"
          + LINE_SEPARATOR_DOUBLE;
    }
    return ErDiagramTemplates.erDiagram(plan, boxes);
  }

  /** 所属テーブルセクションの見出し */
  public static String tableHeading() {
    return "所属テーブル";
  }

  /** 所属テーブルセクションの表ヘッダー */
  public static String tableHeader() {
    return ErDiagramTemplates.diagramTableHeader();
  }

  /**
   * 所属テーブルセクションの1行分<br>
   * 図中のノードからテーブル定義書への導線を、スキーマ別ER図の掲載テーブルと同じくこの一覧で代替する
   */
  public static String tableLine(int no, TableEntity table) {
    return ErDiagramTemplates.diagramTableLine(no, TableKey.of(table), table);
  }

  /** 所属するテーブルが出力対象に1つも無い場合に、テーブル一覧の代わりに出すセクション */
  public static String noTables() {
    return PagedSectionTemplates.heading(tableHeading())
        + "出力対象のテーブルのうち、この観点に所属するものはありません。"
        + LINE_SEPARATOR_DOUBLE;
  }

  /** 所属テーブル同士の関連の一覧セクション（ER図の描画を省略した場合の代替掲載） */
  public static String relations(List<ForeignKeyEntity> relations) {
    return relationSection(ErDiagramTemplates.foreignKeyHeading(), relations);
  }

  /**
   * 観点外のテーブルとの関連の一覧セクション<br>
   * 観点のER図には所属テーブル同士の関連しか描かないため、観点の外にあるテーブルとのつながりを一覧で補う
   *
   * @param outsideRelations 片端だけが所属テーブルの関連のリスト
   */
  public static String outsideRelations(List<ForeignKeyEntity> outsideRelations) {
    return relationSection("観点外のテーブルとの関連", outsideRelations);
  }

  /**
   * 観点ページのフッター<br>
   * 観点一覧・テーブル一覧へ戻る導線を置く
   */
  public static String footer(BaseInfoEntity baseInfo) {
    return HORIZON
        + LINE_SEPARATOR_DOUBLE
        + String.format(
            "[%s](%s) / [%s](%s)",
            ListDocumentType.VIEWPOINT.getBackLinkLabel(),
            MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.VIEWPOINT, baseInfo),
            ListDocumentType.TABLE.getBackLinkLabel(),
            MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.TABLE, baseInfo))
        + LINE_SEPARATOR;
  }

  /** 観点一覧のファイルヘッダー */
  public static String indexFileHeader(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.titledFileHeader(
        ListDocumentType.VIEWPOINT.getTitle(), baseInfo);
  }

  /** 観点一覧セクションの見出し */
  public static String indexHeading() {
    return "観点情報";
  }

  /** 観点一覧セクションの表ヘッダー */
  public static String indexHeader() {
    return """
                | No. | 観点名 | 説明 | テーブル数 | Link |
                |:---|:---|:---|:---|:---|
                """;
  }

  /**
   * 観点一覧セクションの1行分<br>
   * 説明は一覧の表を読みやすく保つため、先頭の1行のみを掲載する（全文は観点ページに掲載する）
   */
  public static String indexLine(int no, ViewpointContent content, BaseInfoEntity baseInfo) {
    final Viewpoint viewpoint = content.viewpoint();
    return MarkdownTemplateSupport.row(
            no,
            MarkdownTemplateSupport.escapeTableCell(viewpoint.name()),
            MarkdownTemplateSupport.escapeTableCell(
                viewpoint.description().lines().findFirst().orElse("")),
            content.tables().size(),
            MarkdownTemplateSupport.linkCell(
                DocumentLocations.linkFromDatabaseRoot(
                    DocumentLocations.viewpointFile(baseInfo.dbName(), viewpoint))))
        + LINE_SEPARATOR;
  }

  /** 観点一覧のフッター */
  public static String indexFooter(BaseInfoEntity baseInfo) {
    return PagedSectionTemplates.backOnlyFooter(
        MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.TABLE, baseInfo),
        ListDocumentType.TABLE.getBackLinkLabel());
  }

  /**
   * @return 関連が無い場合は空文字
   */
  private static String relationSection(String heading, List<ForeignKeyEntity> relations) {
    if (relations.isEmpty()) {
      return "";
    }
    final StringBuilder sb =
        new StringBuilder(PagedSectionTemplates.heading(heading))
            .append(ErDiagramTemplates.foreignKeyTableHeader());
    for (int i = 0; i < relations.size(); i++) {
      sb.append(ErDiagramTemplates.foreignKeyTableLine(i + 1, relations.get(i)));
    }
    return sb.append(LINE_SEPARATOR).toString();
  }
}
