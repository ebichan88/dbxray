package com.dbxray.domain.model.document;

/**
 * 一覧ドキュメント（{@code {接頭辞}List_{DB名}.md}）の種別をもつ列挙型クラス<br>
 * 一覧ファイル名の接頭辞と一覧のタイトルを保持する。関数・プロシージャ／シーケンス／ユーザー定義型は、個別定義ファイルを配置するディレクトリ名にも同じ接頭辞を用いる
 */
public enum ListDocumentType {
  /** テーブル一覧 */
  TABLE("table", "テーブル一覧"),
  /** ER図一覧（スキーマ別ER図の索引） */
  ER_DIAGRAM("erDiagram", "ER図一覧"),
  /** 関数・プロシージャ一覧 */
  FUNCTION("function", "関数・プロシージャ一覧"),
  /** シーケンス一覧 */
  SEQUENCE("sequence", "シーケンス一覧"),
  /** ユーザー定義型一覧 */
  TYPE("type", "ユーザー定義型一覧"),
  /** トリガー一覧 */
  TRIGGER("trigger", "トリガー一覧"),
  /** 観点一覧（サイドカーYAMLで宣言した観点ごとのページの索引） */
  VIEWPOINT("viewpoint", "観点一覧");

  /** 一覧ファイル名・個別定義ディレクトリ名の接頭辞 */
  private final String prefix;

  /** 一覧のタイトル */
  private final String title;

  ListDocumentType(String prefix, String title) {
    this.prefix = prefix;
    this.title = title;
  }

  /**
   * 一覧ファイル名・個別定義ディレクトリ名の接頭辞を取得するメソッド
   *
   * @return 接頭辞（例: {@code function}）
   */
  public String prefix() {
    return prefix;
  }

  /**
   * 一覧のタイトルを取得するメソッド
   *
   * @return タイトル（例: {@code 関数・プロシージャ一覧}）
   */
  public String title() {
    return title;
  }

  /**
   * 他のドキュメントから当該一覧へ戻るリンクの表示名を取得するメソッド
   *
   * @return リンクの表示名（例: {@code 関数・プロシージャ一覧へ}）
   */
  public String backLinkLabel() {
    return title + "へ";
  }
}
