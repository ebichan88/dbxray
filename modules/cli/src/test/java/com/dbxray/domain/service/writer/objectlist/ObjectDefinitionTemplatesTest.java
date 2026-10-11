package com.dbxray.domain.service.writer.objectlist;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ObjectDefinitionTemplates の個別ファイル生成テスト */
public class ObjectDefinitionTemplatesTest {

  private final BaseInfoEntity base =
      new BaseInfoEntity("TEST_DB", Dbms.POSTGRESQL, 16, LocalDate.of(2025, 1, 1));

  @Test
  @DisplayName("functionFile: 見出し・SQLコードブロック・一覧リンクを含む")
  void testFunctionFile() {
    var f =
        new FunctionEntity(
            "TEST_DB",
            "public",
            "f_add",
            1,
            1,
            "",
            "",
            "",
            "",
            "CREATE OR REPLACE FUNCTION public.f_add(a integer) RETURNS integer ...");
    String file =
        ObjectDefinitionTemplates.functionFile(
            FunctionDefinitionContent.withoutTableUsage(f), base);
    assertTrue(file.startsWith("# f_add"));
    assertTrue(file.contains("```sql"));
    assertTrue(file.contains("CREATE OR REPLACE FUNCTION public.f_add"));
    assertTrue(file.contains("[関数・プロシージャ一覧へ](../../functionList_TEST_DB.md)"));
    assertFalse(file.contains("## 利用しているテーブル"), "抽出を行わない実行では節を出さない");
  }

  @Test
  @DisplayName("sequenceFile: 見出し・プロパティ表・一覧リンクを含む")
  void testSequenceFile() {
    var s =
        new SequenceEntity(
            "TEST_DB",
            "public",
            "seq_orders",
            "1",
            "1",
            "9223372036854775807",
            "1",
            "1",
            false,
            "orders.id");
    String file = ObjectDefinitionTemplates.sequenceFile(s, base);
    assertTrue(file.startsWith("# seq_orders"));
    assertTrue(file.contains("## シーケンス情報"));
    assertTrue(file.contains("orders.id"));
    assertTrue(file.contains("[シーケンス一覧へ](../../sequenceList_TEST_DB.md)"));
  }

  @Test
  @DisplayName("typeFile: 見出し・定義表・一覧リンクを含む")
  void testTypeFile() {
    var t = new TypeEntity("TEST_DB", "public", "mood", "ENUM", "sad, ok, happy");
    String file = ObjectDefinitionTemplates.typeFile(t, base);
    assertTrue(file.startsWith("# mood"));
    assertTrue(file.contains("## 定義"));
    assertTrue(file.contains("|ENUM|sad, ok, happy|"));
    assertTrue(file.contains("[ユーザー定義型一覧へ](../../typeList_TEST_DB.md)"));
  }

  @Test
  @DisplayName("typeFile: 定義に含まれる|は表を崩さないようエスケープする")
  void testTypeFileEscapesPipe() {
    var t = new TypeEntity("TEST_DB", "public", "delimiter", "ENUM", "|, ;");
    String file = ObjectDefinitionTemplates.typeFile(t, base);
    assertTrue(file.contains("|ENUM|\\|, ;|"));
  }
}
