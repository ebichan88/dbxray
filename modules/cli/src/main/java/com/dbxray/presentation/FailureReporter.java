package com.dbxray.presentation;

import com.dbxray.presentation.type.ProcessResult;
import com.dbxray.shared.exception.UserCorrectableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 処理の失敗を利用者へ報告するクラス<br>
 * エントリーポイント（{@code Dbxray.main()}）が処理全体の例外を1箇所で捕捉し、このクラスへ渡す。
 * コントローラー・ユースケース等の途中の層では例外を捕捉しない。失敗は次の2つに分けて報告する。
 *
 * <ul>
 *   <li>利用者が直せる誤り（{@link UserCorrectableException}）: 何を直せばよいかをメッセージで伝える。
 *       不具合ではないため、ログにもスタックトレースは残さない
 *   <li>想定外の失敗（それ以外）: 原因の調査に要るスタックトレースをログへ残し、画面にはログの場所を案内する
 * </ul>
 *
 * いずれの場合も、例外の連鎖（原因）のうち表示に含まれていない情報を持つものを{@code [cause]}として併記する
 * （例外を包んだ箇所で、DBが返したエラー等の原因のメッセージが失われないようにするため）
 */
public final class FailureReporter {

  private static final Logger logger = LogManager.getLogger(FailureReporter.class);

  /** ログファイルの場所（log4j2.xmlのlogfileと揃える）。想定外の失敗の報告で、詳細の確認先として案内する */
  private static final String LOG_FILE = "./var/log/dbxray.log";

  private final String failureSummary;
  private final Consumer<String> console;

  /**
   * @param failureSummary 報告の先頭に示す要旨（どの処理が失敗したか）
   * @param console 報告の出力先（通常は標準出力）
   */
  public FailureReporter(String failureSummary, Consumer<String> console) {
    this.failureSummary = failureSummary;
    this.console = console;
  }

  /**
   * 失敗を報告するメソッド<br>
   * 利用者が直せる誤りはメッセージのみをログへ記録し、想定外の失敗はスタックトレース付きでログへ記録したうえで、画面にログの場所を案内する
   *
   * @param failure エントリーポイントが捕捉した例外
   */
  public void report(Throwable failure) {
    final List<String> lines = lines(failure);
    if (failure instanceof UserCorrectableException) {
      logger.error("{}", String.join(System.lineSeparator(), lines));
    } else {
      logger.error(String.join(System.lineSeparator(), lines), failure);
      lines.add(
          " An unexpected error occurred. See the log file for details. [logFile="
              + LOG_FILE
              + "]");
    }
    console.accept(ProcessResult.FAIL.formatMessage(String.join(System.lineSeparator(), lines)));
  }

  /**
   * 報告（要旨・例外のメッセージ・原因）を行単位で組み立てるメソッド
   *
   * @param failure 報告する例外
   * @return 報告の行のリスト（呼び出し側で行を追加できるよう変更可能なリストで返す）
   */
  private List<String> lines(Throwable failure) {
    final List<String> lines = new ArrayList<>();
    lines.add(failureSummary);
    final String message = describe(failure);
    lines.add(" [errmsg]:" + message);
    causesToShow(failure, message).forEach(cause -> lines.add(" [cause]:" + cause));
    return lines;
  }

  /**
   * 報告に併記する原因を求めるメソッド<br>
   * 例外の連鎖のうち、既に表示する内容に含まれるものと、より深い原因のメッセージを繰り返しているだけのもの
   * （MyBatisの例外等）を除き、浅い順に返す。例えば「SQLの失敗」を包んだ例外では、DBが返したエラーだけが残る
   *
   * @param failure 発生した例外
   * @param message 表示する例外のメッセージ
   * @return 併記する原因のリスト（浅い順）
   */
  private static List<Throwable> causesToShow(Throwable failure, String message) {
    final List<Throwable> causes = causeChain(failure);
    final List<String> shownMessages = new ArrayList<>(List.of(message));
    final List<Throwable> result = new ArrayList<>();
    for (int i = 0; i < causes.size(); i++) {
      final String causeMessage = describe(causes.get(i));
      final boolean alreadyShown =
          shownMessages.stream().anyMatch(shown -> containsAllLines(shown, causeMessage));
      final boolean repeatsDeeperCause =
          causes.subList(i + 1, causes.size()).stream()
              .anyMatch(deeper -> containsAllLines(causeMessage, describe(deeper)));
      if (!alreadyShown && !repeatsDeeperCause) {
        shownMessages.add(causeMessage);
        result.add(causes.get(i));
      }
    }
    return result;
  }

  /**
   * メッセージの各行がすべて、別の文章に含まれているか判定するメソッド<br>
   * 複数の誤りをまとめたメッセージを、包む側が箇条書き等に整形し直していても、同じ内容と判定できるよう行単位で比べる
   *
   * @param text 含んでいるか調べる文章
   * @return メッセージの空でない各行（前後の空白を除く）がすべて{@code text}に含まれる場合はtrue
   */
  private static boolean containsAllLines(String text, String message) {
    return message
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .allMatch(text::contains);
  }

  /**
   * 例外の原因を浅い順に列挙するメソッド（原因が循環している場合も終了する）
   *
   * @param failure 発生した例外
   * @return 原因のリスト（{@code failure}自身は含まない）
   */
  private static List<Throwable> causeChain(Throwable failure) {
    final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    visited.add(failure);
    final List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure.getCause();
        cause != null && visited.add(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }

  /**
   * 例外を表示用の文字列にするメソッド
   *
   * @param throwable 例外
   * @return 例外のメッセージ。メッセージを持たない場合は例外のクラス名
   */
  private static String describe(Throwable throwable) {
    final String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getName() : message;
  }
}
