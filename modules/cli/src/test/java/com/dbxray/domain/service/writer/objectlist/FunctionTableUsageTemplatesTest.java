package com.dbxray.domain.service.writer.objectlist;

import static com.dbxray.testsupport.MarkdownAssert.assertMarkdownEquals;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.SchemaResolution;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FunctionTableUsageTemplates の「利用しているテーブル」セクションのテスト */
class FunctionTableUsageTemplatesTest {

  private static final FunctionEntity FUNCTION =
      new FunctionEntity("db", "sample", "f", 1, 1, "FUNCTION", "", "", "plpgsql", "");

  private static final TableEntity SAMPLE_EMPLOYEE = table("sample", "employee", TableType.TABLE);

  private static final TableEntity SAMPLE_PROJECT =
      table("sample", "project", TableType.MATERIALIZED_VIEW);

  private static final TableEntity OTHER_PROJECT = table("other", "project", TableType.TABLE);

  private static final TableEntity SAMPLE_DEPARTMENT =
      table("sample", "department", TableType.VIEW);

  private static final String DISCLAIMER =
      "定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。";

  @Test
  @DisplayName("section: 抽出を行わない実行では、節を出さない")
  void testNotAnalyzed() {
    assertEquals("", section(FunctionTableUsage.notAnalyzed()));
  }

  @Test
  @DisplayName("section: 利用しているテーブルを、区分とC・R・U・Dの○で表にする")
  void testTable() {
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(
            List.of(
                new TableUsage(
                    List.of(SAMPLE_DEPARTMENT),
                    SchemaResolution.DETERMINED,
                    Set.of(CrudOperation.READ)),
                new TableUsage(
                    List.of(SAMPLE_EMPLOYEE),
                    SchemaResolution.DETERMINED,
                    Set.of(CrudOperation.CREATE, CrudOperation.UPDATE, CrudOperation.DELETE))),
            List.of(),
            false,
            false);

    assertMarkdownEquals(
        """
        ## 利用しているテーブル

        %s

        | No. | テーブル | 区分 | C | R | U | D |
        |:---|:---|:---|:---|:---|:---|:---|
        |1|sample.department|view||○|||
        |2|sample.employee|table|○||○|○|

        """
            .formatted(DISCLAIMER),
        section(usage));
  }

  @Test
  @DisplayName("section: スキーマが決まらない名前は(search_path)を付けて示し、表の後に候補のスキーマを並べる（区分は候補で揃うときだけ）")
  void testUndeterminedPostgres() {
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(
            List.of(
                new TableUsage(
                    List.of(SAMPLE_EMPLOYEE),
                    SchemaResolution.DETERMINED,
                    Set.of(CrudOperation.UPDATE)),
                new TableUsage(
                    List.of(SAMPLE_EMPLOYEE),
                    SchemaResolution.SEARCH_PATH,
                    Set.of(CrudOperation.READ)),
                new TableUsage(
                    List.of(SAMPLE_PROJECT, OTHER_PROJECT),
                    SchemaResolution.SEARCH_PATH,
                    Set.of(CrudOperation.READ))),
            List.of(),
            false,
            false);

    assertMarkdownEquals(
        """
        ## 利用しているテーブル

        %s

        | No. | テーブル | 区分 | C | R | U | D |
        |:---|:---|:---|:---|:---|:---|:---|
        |1|sample.employee|table|||○||
        |2|(search_path).employee|table||○|||
        |3|(search_path).project|||○|||

        (search_path) は、スキーマ修飾が無く、実行時のsearch_pathで決まる名前です。出力対象で同じ名前のテーブルがあるスキーマは次のとおりです。

        - employee: sample
        - project: sample, other

        """
            .formatted(DISCLAIMER),
        section(usage));
  }

  @Test
  @DisplayName("section: Oracleの実行者権限で決まらない名前は(current_user)、所有者のスキーマに無い名前は(synonym)を付けて示す")
  void testUndeterminedOracle() {
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(
            List.of(
                new TableUsage(
                    List.of(table("SCOTT", "EMP", TableType.TABLE)),
                    SchemaResolution.CURRENT_USER,
                    Set.of(CrudOperation.DELETE))),
            List.of(),
            false,
            false);

    final String section = section(usage);

    assertTrue(section.contains("|1|(current_user).EMP|table||||○|"), section);
    assertTrue(
        section.contains(
            "(current_user) は、スキーマ修飾が無く、実行者権限（AUTHID CURRENT_USER）のため実行するユーザーで決まる名前です。"),
        section);
    assertTrue(section.contains("- EMP: SCOTT"), section);

    final String synonym =
        section(
            FunctionTableUsage.analyzed(
                List.of(
                    new TableUsage(
                        List.of(table("DATA", "ORDERS", TableType.TABLE)),
                        SchemaResolution.SYNONYM,
                        Set.of(CrudOperation.UPDATE))),
                List.of(),
                false,
                false));
    assertTrue(synonym.contains("|1|(synonym).ORDERS|table|||○||"), synonym);
    assertTrue(
        synonym.contains("(synonym) は、スキーマ修飾が無く所有者のスキーマにも無いため、シノニム経由で参照している可能性がある名前です。"), synonym);
    assertTrue(synonym.contains("- ORDERS: DATA"), synonym);
  }

  @Test
  @DisplayName("section: 該当なし・動的SQL・字句を最後まで読めなかった・オーバーロードの本体をまとめた場合は、表の前にその旨の文を置く")
  void testNotes() {
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(
            List.of(),
            List.of(DynamicSqlKind.EXECUTE_IMMEDIATE, DynamicSqlKind.DBMS_SQL),
            true,
            true);

    assertMarkdownEquals(
        """
        ## 利用しているテーブル

        %s

        出力対象のテーブルへの参照は見つかりませんでした。

        動的SQL（EXECUTE IMMEDIATE・DBMS_SQL）を含むため、この一覧に無いテーブルを利用している可能性があります。

        定義本体の途中で閉じていない文字列・コメント等があり、一覧が欠けている可能性があります。

        同名のサブプログラムの本体を区別できなかったため、すべての本体からまとめて抽出しています。

        """
            .formatted(DISCLAIMER),
        section(usage));
  }

  @Test
  @DisplayName("section: 解析しなかった場合は、表の代わりに理由を示す")
  void testNotAnalyzable() {
    assertMarkdownEquals(
        """
        ## 利用しているテーブル

        言語がplpython3uのため、利用しているテーブルは抽出していません。

        """,
        section(FunctionTableUsage.unsupportedLanguage("plpython3u")));
    assertTrue(
        section(FunctionTableUsage.notAnalyzable(TableUsageStatus.NO_DEFINITION))
            .contains("定義本体を取得できなかったため、利用しているテーブルは抽出していません。"));
    assertTrue(
        section(FunctionTableUsage.notAnalyzable(TableUsageStatus.WRAPPED))
            .contains("定義本体がwrap（難読化）されているため、利用しているテーブルは抽出していません。"));
    assertTrue(
        section(FunctionTableUsage.notAnalyzable(TableUsageStatus.SUBPROGRAM_NOT_FOUND))
            .contains("パッケージ本体からこのサブプログラムの本体を見つけられなかったため、利用しているテーブルは抽出していません。"));
  }

  @Test
  @DisplayName("section: DB由来の名前の|・改行は、表のセルを崩さないようエスケープする")
  void testEscapesNames() {
    final TableEntity hostile = table("s|x", "t\nu", TableType.TABLE);
    final FunctionTableUsage usage =
        FunctionTableUsage.analyzed(
            List.of(
                new TableUsage(
                    List.of(hostile), SchemaResolution.DETERMINED, Set.of(CrudOperation.READ)),
                new TableUsage(
                    List.of(hostile), SchemaResolution.SEARCH_PATH, Set.of(CrudOperation.READ))),
            List.of(),
            false,
            false);

    final String section = section(usage);

    assertTrue(section.contains("|1|s\\|x.t<br>u|table||○|||"), section);
    assertTrue(section.contains("- t u: s|x"), section);
  }

  @Test
  @DisplayName("functionFile: 節を基本情報と定義の間に置く")
  void testFunctionFilePlacesSectionBeforeDefinition() {
    final String file =
        ObjectDefinitionTemplates.functionFile(
            new FunctionDefinitionContent(
                FUNCTION, FunctionTableUsage.analyzed(List.of(), List.of(), false, false)),
            new BaseInfoEntity("db", Dbms.POSTGRESQL, 16, LocalDate.EPOCH));

    final int baseInfo = file.indexOf("## 基本情報");
    final int usage = file.indexOf("## 利用しているテーブル");
    final int definition = file.indexOf("## 定義");
    assertTrue(baseInfo >= 0 && baseInfo < usage && usage < definition, file);
  }

  private static String section(FunctionTableUsage usage) {
    return FunctionTableUsageTemplates.section(new FunctionDefinitionContent(FUNCTION, usage));
  }

  private static TableEntity table(String schema, String name, TableType type) {
    return new TableEntity("db", schema, "", name, type, "");
  }
}
