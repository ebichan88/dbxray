package com.dbxray.config;

import com.dbxray.shared.exception.UserCorrectableException;

/**
 * 設定ファイルの誤り（設定ファイルが見つからない、未知のキー、値が不正等）を表す例外クラス<br>
 * 利用者が設定を見直せば解消する誤り（{@link UserCorrectableException}）のうち、設定ファイルに関するもの。
 * 呼び出し側は、設定の読み込みで起きる個々の例外（YAMLの解析の例外等）を知らなくても、この例外だけで設定誤りを判別できる
 */
public class InvalidConfigurationException extends UserCorrectableException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 誤りの内容（利用者向けのメッセージ）
   */
  public InvalidConfigurationException(String message) {
    super(message);
  }

  /**
   * @param message 誤りの内容（利用者向けのメッセージ）
   * @param cause 誤りの原因となった例外
   */
  public InvalidConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
