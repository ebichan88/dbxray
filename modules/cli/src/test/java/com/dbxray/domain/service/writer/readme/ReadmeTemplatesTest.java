package com.dbxray.domain.service.writer.readme;

import static com.dbxray.testsupport.MarkdownAssert.assertMarkdownEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.testsupport.ExportTargetsFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ReadmeTemplates の集計セクションのテスト */
class ReadmeTemplatesTest {

  @Test
  @DisplayName("metrics: スキーマが1つの場合は合計の行を付けず、数・論理名あり／全体・関連の状況の3つの表を出す")
  void metricsOfSingleSchema() {
    final TableEntity orders = table("sample", "orders", "受注");
    final DatabaseMetrics.Builder builder =
        DatabaseMetrics.builder(
            ExportTargetsFixtures.of(
                List.of(orders, table("sample", "items", "")),
                List.of(ForeignKeyFixtures.physical("sample", "items", "fk", "sample", "orders")),
                List.of(function("sample", "calc")),
                EnumSet.allOf(OutputObjectType.class)));
    builder.collectColumns(
        new TableDetail(
            orders,
            List.of(new ColumnEntity("sample", "orders", "受注ID", "id", "int", "", true, true, "")),
            List.of(),
            List.of()));

    assertMarkdownEquals(
        """
        ## 集計

        出力対象（設定の`target`で絞り込んだ範囲）の数で、DB全体の数ではありません。「テーブル・ビュー」にはマテリアライズドビューを含みます。

        ### オブジェクトの数

        | スキーマ名 | テーブル | うちパーティション表 | ビュー | マテリアライズドビュー | カラム | 関数 | プロシージャ | シーケンス | ユーザー定義型 | トリガー |
        |:---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
        |sample|2|0|0|0|1|1|0|0|0|0|

        ### 論理名の記述状況

        論理名（DBのコメント）を持つものの数／全体の数です。

        | スキーマ名 | テーブル・ビュー | カラム |
        |:---|---:|---:|
        |sample|1 / 2|1 / 1|

        ### 関連と読み解きの状況

        外部キー・論理リレーションは参照元のテーブルのスキーマで数えます。「関連を持たない」は、外部キー・論理リレーションのどちらの端にもならないものです。観点・説明・備考はサイドカーYAMLで宣言・記述したものです。

        | スキーマ名 | 外部キー | 論理リレーション | 関連を持たないテーブル・ビュー | 観点に所属するテーブル・ビュー | 説明・備考を補ったテーブル・ビュー |
        |:---|---:|---:|---:|---:|---:|
        |sample|1|0|0 / 2|0 / 2|0 / 2|

        """,
        ReadmeTemplates.metrics(builder.build()));
  }

  @Test
  @DisplayName("metrics: スキーマが2つ以上の場合は合計の行を足し、取得しなかった種別の列は省く")
  void metricsOfSchemasWithTotal() {
    final String section =
        ReadmeTemplates.metrics(
            DatabaseMetrics.builder(
                    ExportTargetsFixtures.of(
                        List.of(table("hr", "employee", ""), table("sales", "orders", "")),
                        List.of(),
                        List.of(),
                        Set.of()))
                .build());

    assertTrue(
        section.contains(
            "| スキーマ名 | テーブル | うちパーティション表 | ビュー | マテリアライズドビュー | カラム |" + System.lineSeparator()),
        section);
    assertFalse(section.contains("関数"), section);
    assertFalse(section.contains("トリガー"), section);
    assertTrue(section.contains("|hr|1|0|0|0|0|"), section);
    assertTrue(section.contains("|sales|1|0|0|0|0|"), section);
    assertTrue(section.contains("|（合計）|2|0|0|0|0|"), section);
    assertTrue(section.contains("|（合計）|0|0|2 / 2|0 / 2|0 / 2|"), section);
  }

  @Test
  @DisplayName("metrics: スキーマ名の|はエスケープする")
  void metricsEscapesSchemaName() {
    final String section =
        ReadmeTemplates.metrics(
            DatabaseMetrics.builder(
                    ExportTargetsFixtures.of(
                        List.of(table("a|b", "t", "")), List.of(), List.of(), Set.of()))
                .build());

    assertTrue(section.contains("|a\\|b|1|"), section);
  }

  @Test
  @DisplayName("metrics: 集計の対象が1つも無い場合は何も出さない")
  void metricsOfNothing() {
    assertEquals(
        "",
        ReadmeTemplates.metrics(
            DatabaseMetrics.builder(
                    ExportTargetsFixtures.of(List.of(), List.of(), List.of(), Set.of()))
                .build()));
  }

  private static TableEntity table(String schema, String name, String logicalName) {
    return new TableEntity("testdb", schema, logicalName, name, TableType.TABLE, "", "");
  }

  private static FunctionEntity function(String schema, String name) {
    return new FunctionEntity("testdb", schema, name, 1, 1, "FUNCTION", "", "", "sql", "");
  }
}
