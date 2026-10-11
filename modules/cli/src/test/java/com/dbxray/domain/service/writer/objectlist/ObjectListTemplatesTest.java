package com.dbxray.domain.service.writer.objectlist;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.table.TriggerEntity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ObjectListTemplates のセクション生成テスト */
public class ObjectListTemplatesTest {

  private final BaseInfoEntity base =
      new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));

  @Test
  @DisplayName("fileHeader: タイトルとDB名を含む")
  void testFileHeader() {
    String header = ObjectListTemplates.fileHeader("トリガー一覧", base);
    assertTrue(header.startsWith("# トリガー一覧（DB名：TEST_DB）"));
  }

  @Test
  @DisplayName("baseInfo: 基本情報の表を出力する")
  void testBaseInfo() {
    String section = ObjectListTemplates.baseInfo(base);
    assertTrue(section.startsWith("## 基本情報"));
    assertTrue(section.contains("|PostgreSQL 16|TEST_DB|2025/01/01|"));
  }

  @Test
  @DisplayName("triggerTableHeader: トリガー一覧の列定義を出力する")
  void testTriggerTableHeader() {
    assertTrue(
        ObjectListTemplates.triggerTableHeader()
            .startsWith("| No. | スキーマ名 | テーブル名 | トリガー名 | タイミング | イベント | 実行関数 |"));
  }

  @Test
  @DisplayName("functionTableHeader: 関数・プロシージャ一覧の列定義を出力する")
  void testFunctionTableHeader() {
    assertTrue(
        ObjectListTemplates.functionTableHeader()
            .startsWith("| No. | スキーマ名 | 種別 | 名前 | 引数 | 戻り値 | 言語 | Link |"));
  }

  @Test
  @DisplayName("sequenceTableHeader: シーケンス一覧の列定義を出力する")
  void testSequenceTableHeader() {
    assertTrue(
        ObjectListTemplates.sequenceTableHeader()
            .startsWith(
                "| No. | スキーマ名 | シーケンス名 | 増分 | 最小値 | 最大値 | キャッシュ | 開始値 | 循環 | 所有カラム | Link |"));
  }

  @Test
  @DisplayName("typeTableHeader: ユーザー定義型一覧の列定義を出力する")
  void testTypeTableHeader() {
    assertTrue(
        ObjectListTemplates.typeTableHeader().startsWith("| No. | スキーマ名 | 型名 | 種別 | 定義 | Link |"));
  }

  @Test
  @DisplayName("triggerListLine: 行番号付きで1行分を出力し、末尾に改行を付与する")
  void testTriggerListLine() {
    var trigger =
        new TriggerEntity(
            "public",
            "orders",
            "trg_orders",
            "BEFORE",
            List.of("INSERT"),
            "ROW",
            "public.f_orders",
            "",
            "");
    assertEquals(
        "|1|public|orders|trg_orders|BEFORE|INSERT|public.f_orders|" + System.lineSeparator(),
        ObjectListTemplates.triggerListLine(1, trigger));
  }

  @Test
  @DisplayName("functionListLine: 引数・戻り値の|をエスケープし、個別定義へのリンクを付ける")
  void testFunctionListLine() {
    var function =
        new FunctionEntity(
            "TEST_DB", "public", "concat", 2, 2, "FUNCTION", "a text|b", "text", "sql", "");
    assertEquals(
        "|2|public|FUNCTION|concat|a text\\|b|text|sql|[■](./public/function/concat_2.md)|"
            + System.lineSeparator(),
        ObjectListTemplates.functionListLine(2, function));
  }

  @Test
  @DisplayName("sequenceListLine: シーケンスの属性と個別定義へのリンクを出力する")
  void testSequenceListLine() {
    var sequence =
        new SequenceEntity(
            "TEST_DB", "public", "seq_orders", "10", "1", "999", "20", "1", true, "orders.id");
    assertEquals(
        "|1|public|seq_orders|10|1|999|20|1|○|orders.id|[■](./public/sequence/seq_orders.md)|"
            + System.lineSeparator(),
        ObjectListTemplates.sequenceListLine(1, sequence));
  }

  @Test
  @DisplayName("typeListLine: 定義の|をエスケープし、個別定義へのリンクを付ける")
  void testTypeListLine() {
    var type = new TypeEntity("TEST_DB", "public", "delimiter", "ENUM", "|, ,");
    assertEquals(
        "|1|public|delimiter|ENUM|\\|, ,|[■](./public/type/delimiter.md)|" + System.lineSeparator(),
        ObjectListTemplates.typeListLine(1, type));
  }

  @Test
  @DisplayName("footer: テーブル一覧へ戻るリンクを含む")
  void testFooter() {
    String footer = ObjectListTemplates.footer(base);
    assertTrue(footer.startsWith("___"));
    assertTrue(footer.contains("[テーブル一覧へ](./tableList_TEST_DB.md)"));
  }
}
