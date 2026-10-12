package com.dbxray.domain.model.target;

/**
 * 出力対象のテーブルと、それを参照する情報（外部キー・サイドカーの論理リレーション／付帯情報／観点）を突き合わせた結果の通知1件分を表す値オブジェクト<br>
 * リネーム・削除によるDBとサイドカーの乖離など、利用者が気付くべき事柄を表す。
 * 突き合わせ自体はドメインサービスが行い、通知をどこへ（ログ等）どう出力するかは呼び出し側が決める
 *
 * @param message 通知の内容（利用者向けのメッセージ）
 */
public record ConsistencyNotice(Kind kind, String message) {

  /** 通知の重要度 */
  public enum Severity {
    /** 処理内容の報告（対応は不要） */
    INFO,
    /** DBとサイドカーの乖離等、利用者の確認が必要なもの */
    WARN
  }

  /** 通知の種類 */
  public enum Kind {
    /** 実在しないテーブルに対する付帯情報（リネーム・削除の可能性） */
    ORPHAN_TABLE_ANNOTATION(Severity.WARN),
    /** 実在しないカラムに対するカラム備考（リネーム・削除の可能性） */
    ORPHAN_COLUMN_ANNOTATION(Severity.WARN),
    /** 参照先のテーブルが出力対象に存在しないため除外した外部キー */
    UNRESOLVED_FOREIGN_KEY(Severity.WARN),
    /** 参照元・参照先のテーブルが出力対象に存在しないため除外した論理リレーション */
    UNRESOLVED_LOGICAL_RELATION(Severity.WARN),
    /** 観点の所属テーブルのパターンのうち、どのテーブルにも一致しないもの（リネーム・削除の可能性） */
    UNMATCHED_VIEWPOINT_PATTERN(Severity.WARN),
    /** 出力対象が絞り込まれているため、実在しないテーブルに対する付帯情報の検出を行わなかったこと */
    ORPHAN_TABLE_ANNOTATION_CHECK_SKIPPED(Severity.INFO),
    /** サイドカーで宣言された論理リレーションを外部キーの集合へ合流させたこと */
    LOGICAL_RELATIONS_MERGED(Severity.INFO);

    private final Severity severity;

    Kind(Severity severity) {
      this.severity = severity;
    }

    /** 通知の重要度を返却するメソッド */
    public Severity getSeverity() {
      return severity;
    }
  }

  /** 通知の重要度を返却するメソッド */
  public Severity severity() {
    return kind.getSeverity();
  }
}
