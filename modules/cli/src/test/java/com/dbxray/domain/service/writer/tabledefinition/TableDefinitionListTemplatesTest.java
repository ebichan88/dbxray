package com.dbxray.domain.service.writer.tabledefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.testsupport.MarkdownAssert;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableDefinitionListTemplates の詳細テスト */
public class TableDefinitionListTemplatesTest {

  private static final String NL = System.lineSeparator();
  private static final String NL2 = NL + NL;

  private BaseInfoEntity baseInfo() {
    return new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));
  }

  private TableEntity newEntity(String schema, String physical, String logical) {
    return new TableEntity("TEST_DB", schema, logical, physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("fileHeader: 期待値と完全一致 (末尾はダブル改行)")
  void fileHeader_fullMatch() {
    String expected = "# テーブル一覧（DB名：TEST_DB）" + NL2;
    MarkdownAssert.assertMarkdownEquals(
        expected, TableDefinitionListTemplates.fileHeader(baseInfo()));
  }

  @Test
  @DisplayName("baseInfo: テーブル（基本情報）Markdown 全体一致")
  void baseInfo_fullMatch() {
    String expected =
        "## 基本情報"
            + NL
            + NL
            + "| RDBMS | データベース名 | 作成日 |"
            + NL
            + "|:---|:---|:---|"
            + NL
            + "|PostgreSQL 16|TEST_DB|2025/01/01|"
            + NL
            + NL;
    String actual = TableDefinitionListTemplates.baseInfo(baseInfo());
    MarkdownAssert.assertMarkdownEquals(expected, actual);
  }

  @Test
  @DisplayName("tableListTableHeader: 完全一致（見出しは含まず表ヘッダーのみ）")
  void tableListHeader_fullMatch() {
    String expected =
        """
                | No. | スキーマ名 | 論理テーブル名 | 物理テーブル名 | 区分 | Link |
                |:---|:---|:---|:---|:---|:---|
                """;
    MarkdownAssert.assertMarkdownEquals(
        expected, TableDefinitionListTemplates.tableListTableHeader());
  }

  @Test
  @DisplayName("tableListLine: 行番号・リンクを含む1行＋末尾改行")
  void tableListLine_single() {
    TableEntity e = newEntity("public", "orders", "受注");
    String expected = "|1|public|受注|orders|table|[■](./public/table/orders.md)|" + NL;
    MarkdownAssert.assertMarkdownEquals(expected, TableDefinitionListTemplates.tableListLine(1, e));
  }

  @Test
  @DisplayName("tableListLine: 論理テーブル名（DBコメント由来）に含まれる|・改行は表を崩さないようエスケープする")
  void tableListLine_escapesLogicalTableName() {
    TableEntity e = newEntity("public", "orders", "受注|管理\n(旧:注文)");
    String expected =
        "|1|public|受注\\|管理<br>(旧:注文)|orders|table|[■](./public/table/orders.md)|" + NL;
    MarkdownAssert.assertMarkdownEquals(expected, TableDefinitionListTemplates.tableListLine(1, e));
  }

  @Test
  @DisplayName("relatedDocuments: 空リスト → 空文字")
  void relatedDocuments_empty() {
    MarkdownAssert.assertMarkdownEquals(
        "", TableDefinitionListTemplates.relatedDocuments(baseInfo(), List.of()));
  }

  @Test
  @DisplayName("relatedDocuments: 指定された順に一覧へのリンクを列挙する")
  void relatedDocuments_entries() {
    String section =
        TableDefinitionListTemplates.relatedDocuments(
            baseInfo(), List.of(ListDocumentType.FUNCTION, ListDocumentType.TRIGGER));
    assertTrue(section.startsWith("## 関連ドキュメント"));
    assertTrue(section.contains("* [関数・プロシージャ一覧](./functionList_TEST_DB.md)"));
    assertTrue(section.contains("* [トリガー一覧](./triggerList_TEST_DB.md)"));
    assertTrue(section.indexOf("functionList") < section.indexOf("triggerList"), "指定された順が保持される");
  }
}
