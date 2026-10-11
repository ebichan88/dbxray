package com.dbxray.mcp.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.snapshot.SnapshotDirectoryReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * cliが{@code --preview}で出力したサンプルの関数・プロシージャが利用しているテーブル（差分のベースライン）を読み込み、
 * スナップショットのベースラインの関数と対応付けられることを確かめる契約テスト<br>
 * 差分のベースライン（{@code docs/sample/postgres/output-preview}）にはスナップショットを置かないため、スナップショットは既定のベースラインから読む
 */
class SampleFunctionTableUsagesContractTest {

  private static SchemaCatalog catalog;

  @BeforeAll
  static void readSample() {
    final List<FunctionTableUsageEntry> usages =
        new InsightsDirectoryReader()
            .readFunctionTableUsages(Path.of(System.getProperty("samplePreviewSnapshotDir")));
    catalog =
        new SnapshotDirectoryReader()
            .read(Path.of(System.getProperty("sampleSnapshotDir")))
            .withFunctionTableUsages(usages);
  }

  @Test
  @DisplayName("スナップショットのすべての関数（オーバーロードを含む）に、キーと引数で参考情報を対応付けられ、定義本体のハッシュも一致する")
  void everyFunctionHasTableUsage() {
    final List<FunctionEntry> functions =
        catalog.functions().all().stream().flatMap(f -> f.overloads().stream()).toList();

    assertTrue(functions.size() >= 11, "サンプルの関数が読み込まれていること");
    for (final FunctionEntry function : functions) {
      final FunctionTableUsageEntry usage =
          catalog
              .functionTableUsages()
              .find(function)
              .orElseThrow(
                  () -> new AssertionError(function.key() + "(" + function.arguments() + ")"));
      assertFalse(
          usage.definitionHash().isEmpty(), () -> function.key() + ": ハッシュを照らしたうえで対応付けていること");
    }
  }

  @Test
  @DisplayName("利用しているテーブル・操作・スキーマが決まらない名前の候補・動的SQLを読み込む")
  void readsTablesAndDynamicSql() {
    assertEquals(
        List.of(
            new UsedTable("sample", "audit_log", "", List.of(), List.of("C", "R")),
            new UsedTable("sample", "employee", "", List.of(), List.of("U")),
            new UsedTable("sample", "project", "", List.of(), List.of("R")),
            new UsedTable("sample", "project_assignment", "", List.of(), List.of("R", "D"))),
        usage("close_project").tables());

    final FunctionTableUsageEntry countRows = usage("count_rows");
    assertEquals(
        List.of(new UsedTable("", "employee", "search_path", List.of("sample"), List.of("R"))),
        countRows.tables());
    assertEquals(List.of("EXECUTE"), countRows.dynamicSql());
    assertEquals("analyzed", countRows.status());
  }

  private static FunctionTableUsageEntry usage(String name) {
    final FunctionEntry function =
        catalog.functions().all().stream()
            .filter(f -> f.key().name().equals(name))
            .findFirst()
            .orElseThrow()
            .overloads()
            .get(0);
    return catalog.functionTableUsages().find(function).orElseThrow();
  }
}
