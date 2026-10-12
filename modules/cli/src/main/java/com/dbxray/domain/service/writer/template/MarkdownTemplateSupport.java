package com.dbxray.domain.service.writer.template;

import com.dbxray.domain.model.database.BaseInfoEntity;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 各テンプレートクラスで共通して利用するMarkdownの定数・部品を集約したクラス<br>
 * 改行コードや水平線、「## 基本情報」セクション、タイトル付きファイルヘッダーなど、複数のテンプレートクラスで内容が重複していたものをここに集約する
 */
public final class MarkdownTemplateSupport {

  public static final String LINE_SEPARATOR = System.lineSeparator();
  public static final String LINE_SEPARATOR_DOUBLE = LINE_SEPARATOR + LINE_SEPARATOR;
  public static final String HORIZON = "___";

  /** 基本情報に掲載する作成日の書式 */
  private static final DateTimeFormatter GENERATED_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy/MM/dd");

  /** 表のセルで真偽値の「真」を表すマーカー文字列（「偽」は空文字とする） */
  private static final String MARKER = "○";

  private MarkdownTemplateSupport() {}

  /**
   * 基本情報セクション<br>
   * テーブル定義書・各種一覧・ER図など、ほぼ全てのファイルの先頭に共通で掲載する
   */
  public static String baseInfoSection(BaseInfoEntity baseInfo) {
    return """
                ## 基本情報

                | RDBMS | データベース名 | 作成日 |
                |:---|:---|:---|
                """
        + row(
            baseInfo.dbmsName() + " " + baseInfo.majorVersion(),
            baseInfo.dbName(),
            baseInfo.generatedDate().format(GENERATED_DATE_FORMAT))
        + LINE_SEPARATOR_DOUBLE;
  }

  /** 生のHTMLのタグ・コメントの開始になる{@code <}のうち、表のセルの改行に使う{@code <br>}以外 */
  private static final Pattern HTML_START = Pattern.compile("<(?!br>)");

  private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\r|\\n");

  /** コードブロックの囲みの最短の長さ（CommonMarkの仕様） */
  private static final int MIN_FENCE_LENGTH = 3;

  /**
   * Markdownの表の1行を組み立てるメソッド<br>
   * 各引数を{@code |}区切りで連結し、先頭・末尾にも{@code |}を付与する（末尾の改行は含まない）。
   * セルの値は{@link String#valueOf}相当で文字列化し、行全体に{@link #escapeTableRow}を適用する。
   * {@code |}を含みうる自由記述文字列は、呼び出し側で{@link #escapeTableCell}等を適用した上で渡すこと。
   * {@code \}で終わるセルの後には空白を1つ置く（表示ではセルの前後の空白は除かれる）
   *
   * @param cells セルの値（先頭から順に列として並ぶ）
   * @return {@code |cell1|cell2|...|} 形式の1行分の文字列
   */
  public static String row(Object... cells) {
    final StringBuilder sb = new StringBuilder();
    for (Object cell : cells) {
      final String value = String.valueOf(cell);
      sb.append('|').append(value);
      // GFMの表では\|がセルの区切りではなく文字の|と解釈され、値が\で終わると次のセルと1つにつながって列がずれる
      if (value.endsWith("\\")) {
        sb.append(' ');
      }
    }
    return escapeTableRow(sb.append('|').toString());
  }

  /**
   * 表の1行分の文字列（末尾の改行を含まない）に含まれるDB由来の文字列をエスケープするメソッド<br>
   * 改行を{@code <br>}に置き換えたうえで{@link #escapeHtml}を適用する（DB由来の名前の改行で行が切れ、
   * 次の行がMarkdownの構文として解釈されないようにするため）。{@link #row}を使わず書式で組み立てた行に適用する
   */
  public static String escapeTableRow(String line) {
    return escapeHtml(LINE_BREAK.matcher(Objects.toString(line, "")).replaceAll("<br>"));
  }

  /**
   * テーブル定義書・個別定義ファイルなどへのリンクを表す表セルを組み立てるメソッド
   *
   * @param href リンク先への相対パス
   * @return {@code [■](href)} 形式のリンクセル文字列
   */
  public static String linkCell(String href) {
    return "[■](" + href + ")";
  }

  /**
   * タイトル付きファイルヘッダー（「# タイトル（DB名：xxx）」の形式）<br>
   * ER図一覧・オブジェクト一覧など、タイトルとDB名のみで組み立てられるヘッダーで共通利用する
   */
  public static String titledFileHeader(String title, BaseInfoEntity baseInfo) {
    return "# "
        + escapeInline(String.format("%s（DB名：%s）", title, baseInfo.dbName()))
        + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * 手動付帯情報などの自由記述文字列を、Markdownの表セルへ安全に埋め込める形へエスケープするメソッド<br>
   * セル区切りとして解釈される{@code |}をエスケープし、セルを崩す改行（CR/LF）は{@code <br>}に置換する
   *
   * @param value エスケープ対象の文字列（nullの場合は空文字として扱う）
   * @return 表セルへ埋め込み可能な文字列
   */
  public static String escapeTableCell(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return LINE_BREAK.matcher(value.replace("|", "\\|")).replaceAll("<br>");
  }

  /**
   * DBのカタログから取得した定義文字列（インデックス定義・制約定義等）を、Markdownの表セルへ埋め込める形へエスケープするメソッド<br>
   * セル区切りとして解釈される{@code |}のみをエスケープする。{@link #escapeTableCell(String)}と異なり改行は置換しない
   * （カタログ由来の定義は1行で取得しているため）。
   * SQL側でエスケープすると構造化データ（スナップショット等）にもMarkdown記法が混入するため、表示用のエスケープはここで行う
   *
   * @param value エスケープ対象の文字列（nullの場合は空文字として扱う）
   * @return 表セルへ埋め込み可能な文字列
   */
  public static String escapePipe(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return value.replace("|", "\\|");
  }

  /**
   * DB由来の文字列が、Markdownのビューアで生のHTMLとして解釈されないようにするメソッド<br>
   * DBのコメントやテーブル名には任意の文字列を書けるため、{@code <img onerror=...>}等がそのまま描画されると、
   * HTMLを無害化しないビューアでスクリプトが動く。表のセルの改行（{@link #escapeTableCell}が出力する{@code <br>}）だけは残す。
   * コードブロック・コードスパンの中身には適用しない（文字参照が文字どおり表示されるため）
   *
   * @param value エスケープ対象の文字列（nullの場合は空文字として扱う）
   * @return {@code <br>}以外の{@code <}を{@code &lt;}に置き換えた文字列
   */
  public static String escapeHtml(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return HTML_START.matcher(value).replaceAll("&lt;");
  }

  /**
   * 見出し・リストの項目など、1行に収めるDB由来の文字列をエスケープするメソッド<br>
   * 改行が残ると、次の行がMarkdownの構文（コードブロックの開始等）として解釈されるため空白に置き換える
   *
   * @param value エスケープ対象の文字列（nullの場合は空文字として扱う）
   */
  public static String escapeInline(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return escapeHtml(LINE_BREAK.matcher(value).replaceAll(" "));
  }

  /**
   * DB由来の文字列（関数・ビューの定義等）を囲むコードブロックの囲みを返すメソッド<br>
   * 中身に{@code ```}の行があると、そこでコードブロックが閉じて以降がMarkdownとして解釈されるため、中身に現れる最長のバッククォートの並びより長い囲みにする
   *
   * @return 通常は{@code ```}。中身に3個以上連続するバッククォートがある場合は、それより1個多いバッククォート
   */
  public static String codeFence(String content) {
    return "`".repeat(Math.max(MIN_FENCE_LENGTH, longestBacktickRun(content) + 1));
  }

  /**
   * DB由来の文字列（パーティションキー等）をコードスパンにするメソッド<br>
   * 中身のバッククォートでコードスパンが閉じないよう、中身に現れる最長のバッククォートの並びより長い区切りで囲む。
   * 改行は、次の行がMarkdownの構文として解釈されないよう空白に置き換える
   *
   * @return {@code `値`}（中身がバッククォートで始まる・終わる場合は、区切りとの間に空白を入れる）
   */
  public static String codeSpan(String content) {
    final String value = LINE_BREAK.matcher(Objects.toString(content, "")).replaceAll(" ");
    final String delimiter = "`".repeat(longestBacktickRun(value) + 1);
    final String padding = value.startsWith("`") || value.endsWith("`") ? " " : "";
    return delimiter + padding + value + padding + delimiter;
  }

  private static int longestBacktickRun(String value) {
    int longest = 0;
    int current = 0;
    for (final char c : Objects.toString(value, "").toCharArray()) {
      current = c == '`' ? current + 1 : 0;
      longest = Math.max(longest, current);
    }
    return longest;
  }

  /**
   * 真偽値を表のセルに表示するマーカー文字列へ変換するメソッド
   *
   * @return 真の場合は{@code ○}、偽の場合は空文字
   */
  public static String marker(boolean value) {
    return value ? MARKER : "";
  }
}
