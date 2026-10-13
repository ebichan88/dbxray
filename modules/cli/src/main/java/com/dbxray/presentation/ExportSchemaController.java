package com.dbxray.presentation;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.CheckDocumentDiffUsecase;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.ExportSchemaUsecase;
import com.dbxray.domain.model.snapshot.DiffResult;
import com.dbxray.presentation.dto.DiffCheckResultDto;
import com.dbxray.presentation.dto.ResultDto;
import java.time.Clock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DBドキュメント出力処理のコントローラークラス<br>
 * ユースケースを実行し、結果を表示用のDTOへ変換する。ユースケースの例外は捕捉せずに呼び出し元へ伝える（捕捉はエントリーポイントが1箇所にまとめて行い、
 * 報告は{@link FailureReporter}が行う）
 */
public class ExportSchemaController {

  private static final Logger logger = LogManager.getLogger(ExportSchemaController.class);
  private final ExportSchemaUsecase exportSchemaUsecase;
  private final CheckDocumentDiffUsecase checkDocumentDiffUsecase;
  private final Clock clock;

  /**
   * @param clock 処理時間を計る時計
   */
  public ExportSchemaController(
      ExportSchemaUsecase exportSchemaUsecase,
      CheckDocumentDiffUsecase checkDocumentDiffUsecase,
      Clock clock) {
    this.exportSchemaUsecase = exportSchemaUsecase;
    this.checkDocumentDiffUsecase = checkDocumentDiffUsecase;
    this.clock = clock;
  }

  /** コントローラーメソッド */
  public ResultDto execute(ExportSchemaRequest request) {
    logger.info("[START] exportSchema");
    final long startMillis = clock.millis();
    exportSchemaUsecase.exportSchema(request);
    logger.info("[ END ] exportSchema [elapsedMillis={}]", clock.millis() - startMillis);
    return new ResultDto("Table definition output is complete.");
  }

  /**
   * DBの現状から生成したドキュメントと、{@code outputPath}配下に既にコミット済みのドキュメントの差分を検知するメソッド
   *
   * @param request DB vs ドキュメントの差分検知の入力
   * @return 処理結果（差分の報告と、差分の有無）
   */
  public DiffCheckResultDto checkDiff(CheckDocumentDiffRequest request) {
    logger.info("[START] checkDocumentDiff");
    final long startMillis = clock.millis();
    final DiffResult diffResult = checkDocumentDiffUsecase.checkDocumentDiff(request);
    // 差分の内容（unified diff）は画面にだけ出し、ログには規模だけを残す。ログが差分の大きさに比例して膨らまないようにするため
    logger.info(
        "[ END ] checkDocumentDiff [elapsedMillis={}, onlyInGenerated={}, onlyInCommitted={}, "
            + "contentDiffer={}]",
        clock.millis() - startMillis,
        diffResult.onlyInGenerated().size(),
        diffResult.onlyInCommitted().size(),
        diffResult.contentDiffer().size());
    return new DiffCheckResultDto(
        DiffReportFormatter.format(diffResult), diffResult.hasDifference());
  }
}
