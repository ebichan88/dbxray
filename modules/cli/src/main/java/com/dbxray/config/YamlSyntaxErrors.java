package com.dbxray.config;

import java.nio.charset.CharacterCodingException;
import java.util.Objects;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.reader.ReaderException;

/**
 * YAMLの解析の失敗を、ファイルの内容を含めずに説明する文言へ変換するクラス<br>
 * SnakeYAMLの例外のメッセージは、誤りの箇所の行をそのまま引用する。設定ファイルに誤って書いたパスワード等が
 * 画面・ログへ出ないよう、例外のメッセージ（と、それを持つ例外自体）は報告に含めず、この文言で置き換える
 */
public final class YamlSyntaxErrors {

  private YamlSyntaxErrors() {}

  /**
   * 解析の失敗を説明する文言を返すメソッド
   *
   * @return 失敗の種類と位置（行・列は1始まり）。
   *     例: {@code found unexpected end of stream at line 5, column 1 (while scanning a quoted scalar at line 3, column 13)}
   */
  public static String describe(YAMLException e) {
    if (e instanceof MarkedYAMLException marked) {
      final String problem =
          located(
              Objects.requireNonNullElse(marked.getProblem(), "invalid YAML"),
              marked.getProblemMark());
      return marked.getContext() == null
          ? problem
          : problem + " (" + located(marked.getContext(), marked.getContextMark()) + ")";
    }
    if (e instanceof ReaderException reader) {
      // getMessage()は読めなかった文字そのものを含むため、位置だけを示す
      return "the file contains a character that is not allowed at position "
          + (reader.getPosition() + 1);
    }
    if (e.getCause() instanceof CharacterCodingException) {
      return "the file is not saved in UTF-8";
    }
    return "the file could not be parsed as YAML";
  }

  private static String located(String text, Mark mark) {
    return mark == null
        ? text
        : text + " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
  }
}
