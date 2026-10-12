package com.dbxray.domain.service.writer.tabledefinition;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.row;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.writer.objectlist.ObjectListTemplates;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;
import java.util.List;

/** テーブル定義一覧書き込みに利用するMarkdownのテンプレートを扱うクラス */
public class TableDefinitionListTemplates {

  /** テーブル定義一覧ヘッダー */
  public static String fileHeader(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.titledFileHeader(ListDocumentType.TABLE.getTitle(), baseInfo);
  }

  /** 基本情報セクション */
  public static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /**
   * 関連ドキュメントセクション<br>
   * トリガー・関数/プロシージャ・シーケンス・ユーザー定義型など、存在するオブジェクト一覧へのリンクを列挙する
   *
   * @param types リンクを掲載する一覧の種別（掲載順）
   * @return 関連ドキュメントセクション文字列。typesが空の場合は空文字列
   */
  public static String relatedDocuments(BaseInfoEntity baseInfo, List<ListDocumentType> types) {
    if (types == null || types.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("## 関連ドキュメント").append(LINE_SEPARATOR_DOUBLE);
    types.forEach(
        type ->
            sb.append(
                    String.format(
                        "* [%s](%s)  ",
                        type.getTitle(),
                        DocumentLocations.linkFromDatabaseRoot(
                            DocumentLocations.listFile(type, baseInfo.dbName()))))
                .append(LINE_SEPARATOR));
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * テーブル一覧セクションの表ヘッダー<br>
   * 見出しは{@link PagedSectionTemplates#heading(String)}で付与するため含まない。
   * 行数が多い場合に呼び出し側がページ単位で切り出して書き込めるようにするためで、
   * トリガー・関数/プロシージャなどの一覧（{@link ObjectListTemplates}）と同じ方針である
   */
  public static String tableListTableHeader() {
    return """
                | No. | スキーマ名 | 論理テーブル名 | 物理テーブル名 | 区分 | Link |
                |:---|:---|:---|:---|:---|:---|
                """;
  }

  /**
   * テーブル一覧セクション（テーブル情報1行分）<br>
   * 論理テーブル名はDBコメント由来の自由記述文字列（{@code |}・改行を含みうる）のためエスケープする
   *
   * @param no 行番号（1始まり）
   */
  public static String tableListLine(int no, TableEntity table) {
    return row(
            no,
            table.schemaName(),
            MarkdownTemplateSupport.escapeTableCell(table.logicalTableName()),
            table.physicalTableName(),
            table.tableType().getName(),
            tableDefinitionLink(table))
        + LINE_SEPARATOR;
  }

  /**
   * テーブル定義書への相対パスをMarkdownのリンク記法で表す文字列を生成するメソッド<br>
   * テーブル一覧はデータベース単位ディレクトリ直下に配置されるため、同ディレクトリからの相対パスで参照する
   *
   * @return テーブル定義書へのリンク文字列
   */
  private static String tableDefinitionLink(TableEntity table) {
    return MarkdownTemplateSupport.linkCell(
        DocumentLocations.linkFromDatabaseRoot(DocumentLocations.tableDefinitionFile(table)));
  }
}
