package com.dbxray.mcp.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.FunctionCoverage;
import com.dbxray.mcp.catalog.FunctionEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.FunctionUse;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TableImpact;
import com.dbxray.mcp.catalog.TableImpact.IndirectReference;
import com.dbxray.mcp.catalog.TriggerEntry;
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

  @Test
  @DisplayName("employeeの影響範囲として、自己参照を含む参照元・ビューのビュー・トリガー・employeeを使う関数を求められる")
  void impactOfEmployee() {
    final TableEntry employee = table("employee");

    final TableImpact impact = catalog.impactOf(employee);

    assertEquals(
        List.of(
            "attendance",
            "audit_log",
            "employee",
            "employee_history",
            "employee_profile",
            "project_assignment"),
        impact.directReferences().stream().map(relation -> relation.from().name()).toList(),
        "自己参照（manager_id）も段1の関連に含む");
    assertEquals(
        List.of(
            new IndirectReference(
                new ObjectKey("testdb", "sample", "attendance_note"),
                2,
                new ObjectKey("testdb", "sample", "attendance"))),
        impact.indirectReferences());
    assertEquals(
        catalog.relations().counts(employee).impact(),
        impact.directReferences().stream()
                .map(relation -> relation.from())
                .filter(from -> !from.equals(employee.key()))
                .distinct()
                .count()
            + impact.indirectReferences().size(),
        "list_tablesのimpactと一致する");
    assertEquals(
        List.of("employee_directory_view", "attendance_monthly_view@employee_directory_view"),
        impact.views().stream()
            .map(
                view ->
                    view.view().key().name() + (view.via() == null ? "" : "@" + view.via().name()))
            .toList());
    assertEquals(
        List.of("trg_employee_audit", "trg_employee_set_updated_at"),
        impact.triggers().stream().map(TriggerEntry::name).toList());
    assertEquals(
        List.of(
            "close_project:U",
            "count_rows:R?",
            "department_headcount:R",
            "employee_directory_insert:C",
            "raise_salary:U"),
        impact.functions().usages().stream()
            .map(
                use ->
                    use.function().name()
                        + ":"
                        + String.join("", use.operations())
                        + (use.possible() ? "?" : ""))
            .toList());
    final FunctionUse countRows =
        impact.functions().usages().stream()
            .filter(use -> use.function().name().equals("count_rows"))
            .findFirst()
            .orElseThrow();
    assertEquals("search_path", countRows.schemaResolution());
    assertEquals(List.of("EXECUTE"), countRows.dynamicSql());
    assertEquals(
        List.of("employee_directory_view.trg_employee_directory_insert"),
        impact.functions().usages().stream()
            .filter(use -> use.function().name().equals("employee_directory_insert"))
            .flatMap(use -> use.calledByTriggers().stream())
            .map(found -> found.table().key().name() + "." + found.trigger().name())
            .toList(),
        "ビューのINSTEAD OFトリガー経由で、employeeへ書き込む関数が実行される");
    final int functionCount =
        catalog.functions().all().stream().mapToInt(f -> f.overloads().size()).sum();
    assertEquals(
        new FunctionCoverage(functionCount, functionCount, 0, 0, 0, 1, 0, 0),
        impact.functions().coverage(),
        "サンプルの関数はすべて解析済みで、動的SQLを含むのはcount_rowsだけ");
    assertEquals(
        List.of("employee_employee_id_seq"),
        impact.sequences().stream().map(use -> use.sequence().name()).toList());
    assertEquals(
        List.of("employee_status_enum"),
        impact.types().stream().map(use -> use.type().key().name()).toList());
  }

  private static TableEntry table(String name) {
    return catalog.tables().all().stream()
        .filter(t -> t.key().name().equals(name))
        .findFirst()
        .orElseThrow();
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
