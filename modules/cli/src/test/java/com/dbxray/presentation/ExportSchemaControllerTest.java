package com.dbxray.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.CheckDocumentDiffUsecase;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.ExportSchemaUsecase;
import com.dbxray.application.TargetSelection;
import com.dbxray.domain.model.snapshot.ContentDiff;
import com.dbxray.domain.model.snapshot.DiffResult;
import com.dbxray.presentation.dto.DiffCheckResultDto;
import com.dbxray.presentation.dto.ResultDto;
import com.dbxray.presentation.type.ExitStatus;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ExportSchemaController の処理結果の組み立てと、例外を捕捉せずに伝えることに関するテスト */
public class ExportSchemaControllerTest {

  /**
   * 呼び出し引数を記録し、任意の例外を投げられるユースケースのスタブ<br>
   * ユースケースは具象クラスのため、メソッドを上書きしたサブクラスで差し替える（依存は使わないのでnullで足りる）
   */
  private static class RecordingUsecase {
    ExportSchemaRequest capturedExportSchemaRequest;
    CheckDocumentDiffRequest capturedCheckDocumentDiffRequest;
    RuntimeException toThrow;
    DiffResult diffResultToReturn = new DiffResult(List.of(), List.of(), List.of());

    ExportSchemaController controller() {
      return new ExportSchemaController(
          new ExportSchemaUsecase(null, null, null, null, null, null) {
            @Override
            public void exportSchema(ExportSchemaRequest request) {
              capturedExportSchemaRequest = request;
              if (toThrow != null) {
                throw toThrow;
              }
            }
          },
          new CheckDocumentDiffUsecase(null, null, null, null, null) {
            @Override
            public DiffResult checkDocumentDiff(CheckDocumentDiffRequest request) {
              capturedCheckDocumentDiffRequest = request;
              if (toThrow != null) {
                throw toThrow;
              }
              return diffResultToReturn;
            }
          },
          Clock.systemUTC());
    }
  }

  private ExportSchemaRequest exportRequest(
      List<String> schemaList,
      List<String> tableList,
      String outputPath,
      int chunkSize,
      int erDiagramMaxNodes,
      List<String> outputObjectList,
      String sidecarPath,
      boolean rmDist) {
    return new ExportSchemaRequest(
        TargetSelection.of(schemaList, tableList, outputObjectList),
        sidecarPath,
        outputPath,
        chunkSize,
        erDiagramMaxNodes,
        1,
        rmDist,
        Set.of());
  }

  private CheckDocumentDiffRequest checkDiffRequest(
      List<String> schemaList,
      List<String> tableList,
      String outputPath,
      int chunkSize,
      List<String> outputObjectList,
      String sidecarPath) {
    return new CheckDocumentDiffRequest(
        TargetSelection.of(schemaList, tableList, outputObjectList),
        sidecarPath,
        outputPath,
        chunkSize);
  }

  @Test
  @DisplayName("execute: ユースケースが正常終了した場合はSUCCESSの結果を返す")
  void testExecuteSuccessReturnsSuccessResult() {
    var usecase = new RecordingUsecase();
    var controller = usecase.controller();

    ResultDto result =
        controller.execute(
            exportRequest(
                List.of("public"),
                List.of("orders"),
                "output",
                100,
                80,
                List.of(),
                "conf/annotations.yml",
                false));

    assertEquals("Table definition output is complete.", result.message());
    assertTrue(result.getResultMessage().startsWith("[result]:SUCCESS"));
  }

  @Test
  @DisplayName("execute: 引数（ExportSchemaRequest）をそのままユースケースへ渡す")
  void testExecutePassesArgumentsThrough() {
    var usecase = new RecordingUsecase();
    var controller = usecase.controller();

    ExportSchemaRequest request =
        exportRequest(
            List.of("public"),
            List.of("orders"),
            "output",
            100,
            80,
            List.of("trigger", "function"),
            "conf/annotations.yml",
            true);

    controller.execute(request);

    assertSame(request, usecase.capturedExportSchemaRequest);
  }

  @Test
  @DisplayName("execute: ユースケースの例外は捕捉せず、そのまま呼び出し元へ伝える（捕捉はエントリーポイントの境界で行う）")
  void testExecutePropagatesUsecaseException() {
    var usecase = new RecordingUsecase();
    usecase.toThrow = new IllegalStateException("unexpected");
    var controller = usecase.controller();

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                controller.execute(
                    exportRequest(List.of(), List.of(), null, 0, 0, List.of(), null, false)));

    assertSame(usecase.toThrow, thrown);
  }

  @Test
  @DisplayName("checkDiff: 差分が見つからない場合はhasDifference=falseを返し、終了状態はSUCCESSとなる")
  void testCheckDiffNoDifferenceReturnsSuccessWithoutDifference() {
    var usecase = new RecordingUsecase();
    usecase.diffResultToReturn = new DiffResult(List.of(), List.of(), List.of());
    var controller = usecase.controller();

    DiffCheckResultDto result =
        controller.checkDiff(
            checkDiffRequest(List.of("public"), List.of(), "output", 100, List.of(), null));

    assertFalse(result.hasDifference());
    assertEquals(ExitStatus.SUCCESS, result.exitStatus());
    assertTrue(result.getResultMessage().startsWith("[result]:SUCCESS"));
  }

  @Test
  @DisplayName(
      "checkDiff: 差分が見つかった場合はhasDifference=trueを返し（終了状態はDIFFERENCE_FOUND）、差分対象とunified diffをメッセージに含める")
  void testCheckDiffWithDifferenceReturnsSuccessWithDifference() {
    var usecase = new RecordingUsecase();
    usecase.diffResultToReturn =
        new DiffResult(
            List.of("new.md"),
            List.of("stale.md"),
            List.of(
                new ContentDiff(
                    "table public.changed",
                    List.of(
                        "--- committed/t.jsonl",
                        "+++ generated/t.jsonl",
                        "@@ -1 +1 @@",
                        "-old",
                        "+new"))));
    var controller = usecase.controller();

    DiffCheckResultDto result =
        controller.checkDiff(
            checkDiffRequest(List.of(), List.of(), "output", 100, List.of(), null));

    assertTrue(result.hasDifference());
    assertEquals(ExitStatus.DIFFERENCE_FOUND, result.exitStatus());
    assertTrue(result.message().contains("new.md"));
    assertTrue(result.message().contains("stale.md"));
    assertTrue(result.message().contains("table public.changed"));
    assertTrue(result.message().contains("--- committed/t.jsonl"));
    assertTrue(result.message().contains("+++ generated/t.jsonl"));
    assertTrue(result.message().contains("@@ -1 +1 @@"));
    assertTrue(result.message().contains("-old"));
    assertTrue(result.message().contains("+new"));
  }

  @Test
  @DisplayName("checkDiff: 引数（CheckDocumentDiffRequest）をそのままユースケースへ渡す")
  void testCheckDiffPassesArgumentsThrough() {
    var usecase = new RecordingUsecase();
    var controller = usecase.controller();

    CheckDocumentDiffRequest request =
        checkDiffRequest(
            List.of("public"),
            List.of("orders"),
            "output",
            100,
            List.of("trigger"),
            "conf/annotations.yml");

    controller.checkDiff(request);

    assertSame(request, usecase.capturedCheckDocumentDiffRequest);
  }

  @Test
  @DisplayName("checkDiff: ユースケースの例外は捕捉せず、そのまま呼び出し元へ伝える（捕捉はエントリーポイントの境界で行う）")
  void testCheckDiffPropagatesUsecaseException() {
    var usecase = new RecordingUsecase();
    usecase.toThrow = new RuntimeException("boom");
    var controller = usecase.controller();

    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                controller.checkDiff(
                    checkDiffRequest(List.of(), List.of(), null, 0, List.of(), null)));

    assertSame(usecase.toThrow, thrown);
  }
}
