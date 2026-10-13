package com.dbxray.presentation.dto;

import com.dbxray.presentation.type.ProcessResult;

/**
 * 通常実行の処理結果に関するrecordクラス<br>
 * 失敗した場合の報告は{@link com.dbxray.presentation.FailureReporter}が組み立てるため、成功した場合の結果のみを表す
 */
public record ResultDto(String message) {

  /**
   * 処理結果のメッセージを返却する
   *
   * @return 処理結果の種別（成功）を先頭に付けた処理結果のメッセージ
   */
  public String resultMessage() {
    return ProcessResult.SUCCESS.formatMessage(message);
  }
}
