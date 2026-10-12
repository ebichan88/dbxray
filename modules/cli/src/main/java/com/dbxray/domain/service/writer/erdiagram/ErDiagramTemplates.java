package com.dbxray.domain.service.writer.erdiagram;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.HORIZON;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.DiagramColumn;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.relation.RenderingPlan;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionListTemplates;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionTemplates;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.domain.service.writer.template.MermaidSupport;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;
import java.util.List;
import java.util.Map;

/**
 * スキーマ単位のER図（全体ER図）書き込みに利用するMarkdownのテンプレートを扱うクラス<br>
 * テーブル単位のER図（{@link TableDefinitionTemplates#erDiagram}）とは異なり、すべてのテーブルの箱に関連をつなぐカラムだけを表示する。
 * カラムは関連カラム（{@link DiagramBoxes}）を用いるため、テーブル詳細のチャンク分割取得の影響を受けない<br>
 * 表のセクションはヘッダーと1行分を個別に生成できるようにしている。
 * 行数が多い場合に呼び出し側がページ単位で切り出して書き込めるようにするためで、
 * テーブル一覧（{@link TableDefinitionListTemplates}）と同じ方針である
 */
public class ErDiagramTemplates {

  /** ER図ファイルヘッダー */
  public static String fileHeader(String title, BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.titledFileHeader(title, baseInfo);
  }

  /** スキーマ別ER図ページのファイルヘッダー */
  public static String schemaFileHeader(String schemaName, BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.fileHeader(
        String.format("ER図（DB名：%s / スキーマ名：%s）", baseInfo.dbName(), schemaName));
  }

  /**
   * グループ別ER図ページのファイルヘッダー
   *
   * @param groupNo グループ番号（1始まり）
   */
  public static String groupFileHeader(String schemaName, int groupNo, BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.fileHeader(
        String.format("ER図（DB名：%s / スキーマ名：%s / グループ%d）", baseInfo.dbName(), schemaName, groupNo));
  }

  /** 基本情報セクション */
  public static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /** ER図索引セクション（スキーマ別ER図へのリンク一覧） */
  public static String schemaIndex(
      BaseInfoEntity baseInfo, Map<String, List<TableEntity>> tablesBySchema) {
    StringBuilder sb =
        new StringBuilder("## スキーマ別ER図")
            .append(LINE_SEPARATOR_DOUBLE)
            .append("| No. | スキーマ名 | テーブル数 | Link |")
            .append(LINE_SEPARATOR)
            .append("|:---|:---|:---|:---|")
            .append(LINE_SEPARATOR);
    int no = 0;
    for (final Map.Entry<String, List<TableEntity>> schema : tablesBySchema.entrySet()) {
      no++;
      sb.append(
              MarkdownTemplateSupport.escapeTableRow(
                  String.format(
                      "| %d | %s | %d | %s |",
                      no,
                      schema.getKey(),
                      schema.getValue().size(),
                      MarkdownTemplateSupport.linkCell(
                          DocumentLocations.linkFromDatabaseRoot(
                              DocumentLocations.erDiagramFile(
                                  baseInfo.dbName(), schema.getKey()))))))
          .append(LINE_SEPARATOR);
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * ER図セクション（Mermaid記法）<br>
   * 外部キーによる関連を持つテーブルのみをノードとして描画する。
   * 関連を持たないテーブルを含めるとノード数が膨らみ図が読めなくなるため描画対象から除外する（全テーブルはテーブル一覧
   * {@code tableList_{DB名}.md}側に掲載されている）。<br>
   * テーブルの箱には、図に描画する関連をつなぐカラムだけを表示する。<br>
   * 描画を省略する計画（{@link RenderingPlan.Omit}）の場合はMermaidの描画を諦め、その旨のメッセージのみを返す
   * （代替として掲載する外部キー一覧は呼び出し側が組み立てる）
   */
  public static String erDiagram(RenderingPlan plan, DiagramBoxes boxes) {
    final ForeignKeyGroup group = plan.group();
    StringBuilder sb = new StringBuilder("## ER図").append(LINE_SEPARATOR_DOUBLE);
    if (group.foreignKeys().isEmpty()) {
      return sb.append("外部キーによる関連を持つテーブルはありません。").append(LINE_SEPARATOR_DOUBLE).toString();
    }
    if (plan instanceof RenderingPlan.Omit(ForeignKeyGroup omitted, NodeLimit limit)) {
      return sb.append(
              String.format(
                  "ER図に描画するテーブル数が%d件となり、上限（erDiagramMaxNodes = %d件）を超えるため描画を省略しました。",
                  omitted.nodeCount(), limit.value()))
          .append(LINE_SEPARATOR)
          .append("代わりに外部キーによる関連を一覧で掲載します。")
          .append(LINE_SEPARATOR_DOUBLE)
          .toString();
    }
    final Map<TableKey, List<DiagramColumn>> columns = boxes.relationColumnsOf(group.foreignKeys());
    return sb.append(
            MermaidSupport.erDiagramCodeBlock(
                group.nodes(),
                group.foreignKeys(),
                boxes,
                key -> columns.getOrDefault(key, List.of())))
        .toString();
  }

  /** ER図をグループに分割した場合の、スキーマページに掲載する説明セクション */
  public static String groupedMessage(int nodeCount, NodeLimit limit, int groupCount) {
    return new StringBuilder("## ER図")
        .append(LINE_SEPARATOR_DOUBLE)
        .append(
            String.format(
                "ER図に描画するテーブル数が%d件となり、上限（erDiagramMaxNodes = %d件）を超えるため、",
                nodeCount, limit.value()))
        .append(LINE_SEPARATOR)
        .append(String.format("外部キーで繋がったテーブルのまとまりごとに%d個のグループへ分割しました。", groupCount))
        .append(LINE_SEPARATOR_DOUBLE)
        .toString();
  }

  /**
   * グループ一覧セクション（スキーマのER図を分割したグループごとのページへのリンク）
   *
   * @param groups 分割したグループ（グループ番号の順）
   */
  public static String groupIndex(
      String schemaName, List<ForeignKeyGroup> groups, BaseInfoEntity baseInfo) {
    final StringBuilder sb =
        new StringBuilder(PagedSectionTemplates.heading("グループ一覧"))
            .append(
                """
                | No. | テーブル数 | 外部キー数 | 主なテーブル | Link |
                |:---|:---|:---|:---|:---|
                """);
    for (int groupNo = 1; groupNo <= groups.size(); groupNo++) {
      final ForeignKeyGroup group = groups.get(groupNo - 1);
      sb.append(
          groupIndexLine(
              groupNo,
              group.nodeCount(),
              group.foreignKeys().size(),
              group.mainTable(),
              DocumentLocations.linkFromDatabaseRoot(
                  DocumentLocations.erDiagramGroupFile(baseInfo.dbName(), schemaName, groupNo))));
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * グループ一覧セクションの1行分
   *
   * @param no グループ番号（1始まり）
   * @param mainTable 当該グループで最も多くの外部キーが接続するテーブル
   */
  public static String groupIndexLine(
      int no, int tableCount, int fkCount, TableKey mainTable, String href) {
    return MarkdownTemplateSupport.escapeTableRow(
            String.format(
                "| %d | %d | %d | %s | %s |",
                no,
                tableCount,
                fkCount,
                mainTable.qualifiedName(),
                MarkdownTemplateSupport.linkCell(href)))
        + LINE_SEPARATOR;
  }

  /**
   * グループ別ER図ページのフッター<br>
   * スキーマ全体のER図（グループ一覧）へ戻る導線を加える
   */
  public static String groupFooter(String schemaName, BaseInfoEntity baseInfo) {
    return HORIZON
        + LINE_SEPARATOR_DOUBLE
        + String.format(
            "[スキーマのER図へ](%s) / ",
            DocumentLocations.linkFromDatabaseRoot(
                DocumentLocations.erDiagramFile(baseInfo.dbName(), schemaName)))
        + backLinks(baseInfo)
        + LINE_SEPARATOR;
  }

  /** 掲載テーブルセクションの見出し */
  public static String diagramTableHeading() {
    return "ER図に掲載しているテーブル";
  }

  /** 掲載テーブルセクションの表ヘッダー */
  public static String diagramTableHeader() {
    return """
                | No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
                |:---|:---|:---|:---|:---|:---|
                """;
  }

  /**
   * 掲載テーブルセクションの1行分<br>
   * Mermaidの{@code click}構文はGitHub上では無効化されるため、図中のノードからの導線をこの一覧で代替する
   *
   * @param table テーブル情報。出力対象範囲外で定義書が存在しない場合はnull
   */
  public static String diagramTableLine(int no, TableKey key, TableEntity table) {
    // 出力対象範囲外のテーブルを参照している場合、定義書が存在しないためリンクを張らない
    if (table == null) {
      return MarkdownTemplateSupport.escapeTableRow(
              String.format("| %d | %s | %s |  |  | - |", no, key.schema(), key.table()))
          + LINE_SEPARATOR;
    }
    return MarkdownTemplateSupport.escapeTableRow(
            String.format(
                "| %d | %s | %s | %s | %s | %s |",
                no,
                table.schemaName(),
                table.physicalTableName(),
                MarkdownTemplateSupport.escapeTableCell(table.logicalTableName()),
                table.tableType().getName(),
                MarkdownTemplateSupport.linkCell(
                    DocumentLocations.linkFromDatabaseRoot(
                        DocumentLocations.tableDefinitionFile(table)))))
        + LINE_SEPARATOR;
  }

  /** 外部キー一覧セクションの見出し（ER図の描画を省略した場合の代替掲載） */
  public static String foreignKeyHeading() {
    return "外部キー一覧";
  }

  /**
   * スキーマ跨ぎ外部キーセクションの見出し<br>
   * スキーマ単位にER図を分割すると、スキーマをまたぐ関連は双方の図に現れて全体像が追いにくいため、索引ページに一覧としてまとめて掲載する
   */
  public static String crossSchemaForeignKeyHeading() {
    return "スキーマ跨ぎの外部キー";
  }

  /** 外部キー一覧セクションの表ヘッダー */
  public static String foreignKeyTableHeader() {
    return """
                | No. | 参照元 | 外部キー名 | 参照先 |
                |:---|:---|:---|:---|
                """;
  }

  /** 外部キー一覧セクションの1行分 */
  public static String foreignKeyTableLine(int no, ForeignKeyEntity fk) {
    return MarkdownTemplateSupport.escapeTableRow(
            String.format(
                "| %d | %s | %s | %s |",
                no, fk.getSchemaTableName(), fk.foreignKeyName(), fk.getReferenceSchemaTableName()))
        + LINE_SEPARATOR;
  }

  /** スキーマ別ER図ページのフッター */
  public static String schemaFooter(BaseInfoEntity baseInfo) {
    return HORIZON + LINE_SEPARATOR_DOUBLE + backLinks(baseInfo) + LINE_SEPARATOR;
  }

  /** ER図索引ページのフッター */
  public static String indexFooter(BaseInfoEntity baseInfo) {
    return PagedSectionTemplates.backOnlyFooter(
        MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.TABLE, baseInfo),
        ListDocumentType.TABLE.getBackLinkLabel());
  }

  /** ER図一覧・テーブル一覧へ戻るリンクを並べた文字列を生成するメソッド */
  private static String backLinks(BaseInfoEntity baseInfo) {
    return String.format(
        "[%s](%s) / [%s](%s)",
        ListDocumentType.ER_DIAGRAM.getBackLinkLabel(),
        MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.ER_DIAGRAM, baseInfo),
        ListDocumentType.TABLE.getBackLinkLabel(),
        MarkdownTemplateSupport.listLinkFromDatabaseRoot(ListDocumentType.TABLE, baseInfo));
  }
}
