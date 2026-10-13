package com.dbxray.presentation.dto;

import com.dbxray.presentation.type.ExitStatus;
import com.dbxray.presentation.type.ProcessResult;

/**
 * ドキュメント差分検知（{@code --check}モード）の処理結果に関するrecordクラス<br>
 * 比較処理自体が失敗した場合の報告は{@link com.dbxray.presentation.FailureReporter}が組み立てるため、
 * 比較を終えた場合の結果（差分の有無を含む）のみを表す
 *
 * @param message 処理結果のメッセージ（差分の報告）
 * @param hasDifference 生成ドキュメントとコミット済みドキュメントに差分が見つかったか
 */
public record DiffCheckResultDto(String message, boolean hasDifference) {

  /**
   * 処理結果のメッセージを返却する
   *
   * @return 処理結果の種別（成功）を先頭に付けた処理結果のメッセージ
   */
  public String resultMessage() {
    return ProcessResult.SUCCESS.formatMessage(message);
  }

  /**
   * 差分の有無に応じた終了状態を返却する
   *
   * @return 差分がある場合は{@link ExitStatus#DIFFERENCE_FOUND}、ない場合は{@link ExitStatus#SUCCESS}
   */
  public ExitStatus exitStatus() {
    return hasDifference ? ExitStatus.DIFFERENCE_FOUND : ExitStatus.SUCCESS;
  }
}
