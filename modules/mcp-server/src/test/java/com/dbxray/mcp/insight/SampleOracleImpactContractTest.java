package com.dbxray.mcp.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.FunctionCoverage;
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
 * cliが出力したOracleのサンプル（ベースラインと、{@code --preview}の差分のベースライン）で、影響範囲を求められることを確かめる契約テスト<br>
 * Oracleのトリガーは関数名を持たず本体を直接持ち、本体の中の参照は参考情報の対象外のため、PostgreSQLのサンプルでは確かめられない
 * 「トリガーが触るテーブルは分からない」ことを確かめる
 */
class SampleOracleImpactContractTest {

  private static final ObjectKey DATABASE_SCHEMA = new ObjectKey("FREEPDB1", "SAMPLE", "");

  private static SchemaCatalog catalog;

  @BeforeAll
  static void readSample() {
    catalog =
        new SnapshotDirectoryReader()
            .read(Path.of(System.getProperty("oracleSampleSnapshotDir")))
            .withFunctionTableUsages(
                new InsightsDirectoryReader()
                    .readFunctionTableUsages(
                        Path.of(System.getProperty("oracleSamplePreviewSnapshotDir"))));
  }

  @Test
  @DisplayName("EMPLOYEEの影響範囲として、参照元・ビュー・EMPLOYEEを使うプロシージャを求められる。トリガーが触るテーブルは分からない")
  void impactOfEmployee() {
    final TableEntry employee =
        catalog.tables().all().stream()
            .filter(table -> table.key().name().equals("EMPLOYEE"))
            .findFirst()
            .orElseThrow();

    final TableImpact impact = catalog.impactOf(employee);

    assertEquals(
        List.of("ATTENDANCE", "EMPLOYEE", "EMPLOYEE_PROFILE", "PROJECT_ASSIGNMENT"),
        impact.directReferences().stream().map(relation -> relation.from().name()).toList());
    assertEquals(
        List.of(
            new IndirectReference(
                new ObjectKey(
                    DATABASE_SCHEMA.database(), DATABASE_SCHEMA.schema(), "ATTENDANCE_NOTE"),
                2,
                new ObjectKey(DATABASE_SCHEMA.database(), DATABASE_SCHEMA.schema(), "ATTENDANCE"))),
        impact.indirectReferences());
    assertEquals(
        List.of("EMPLOYEE_DIRECTORY_VIEW", "ATTENDANCE_MONTHLY_VIEW@EMPLOYEE_DIRECTORY_VIEW"),
        impact.views().stream()
            .map(
                view ->
                    view.view().key().name() + (view.via() == null ? "" : "@" + view.via().name()))
            .toList(),
        "ビューを参照するビューも、経由したビューとともに求める");
    assertEquals(
        List.of("TRG_EMPLOYEE_AUDIT", "TRG_EMPLOYEE_SET_UPDATED_AT"),
        impact.triggers().stream().map(TriggerEntry::name).toList());
    assertTrue(
        impact.triggers().stream().allMatch(trigger -> trigger.function().isEmpty()),
        "Oracleのトリガーは関数名を持たない");
    assertEquals(
        List.of("RAISE_SALARY"),
        impact.functions().usages().stream().map(use -> use.function().name()).toList(),
        "EMPLOYEE_DIRECTORY_VIEWのINSTEAD OFトリガーがEMPLOYEEへINSERTするが、本体の中の参照は含まれない");
    final FunctionUse raiseSalary = impact.functions().usages().get(0);
    assertEquals(List.of("U"), raiseSalary.operations());
    assertTrue(raiseSalary.calledByTriggers().isEmpty());
    assertEquals(
        new FunctionCoverage(6, 6, 0, 0, 0, 1, 0, 5),
        impact.functions().coverage(),
        "サンプルのプロシージャ・関数はすべて解析済み（動的SQLを含むのはPURGE_AUDIT_LOGだけ）で、関数名を持たないトリガーは5つ");
    assertTrue(impact.sequences().isEmpty(), "IDENTITY列はnextvalのデフォルト値ではない");
    assertTrue(impact.types().isEmpty());
  }
}
