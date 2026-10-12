package com.dbxray.domain.model.snapshot;

import java.util.Map;
import java.util.Objects;

/**
 * スナップショットのうち、スキーマ単位のJSON Linesファイルに出力するオブジェクトの種別を表す列挙型<br>
 * 種別ごとに1ファイルとし、1行に1オブジェクトを出力する
 */
public enum SnapshotKind {
  /** テーブル（view・materialized viewを含む） */
  TABLE("tables", "table"),
  /** 関数・プロシージャ */
  FUNCTION("functions", "function") {
    /** 同名の関数（オーバーロード）を区別するため、引数も識別名に含める */
    @Override
    public String identify(Map<String, ?> snapshot) {
      return super.identify(snapshot) + "(" + Objects.toString(snapshot.get("arguments"), "") + ")";
    }
  },
  /** シーケンス */
  SEQUENCE("sequences", "sequence"),
  /** ユーザー定義型 */
  TYPE("types", "type");

  /** 出力ファイル名（拡張子を除く） */
  private final String fileName;

  /** 差分の報告等に用いる種別名 */
  private final String label;

  SnapshotKind(String fileName, String label) {
    this.fileName = fileName;
    this.label = label;
  }

  /**
   * 出力ファイル名（拡張子を除く）を返却するメソッド
   *
   * @return 出力ファイル名（拡張子を除く）
   */
  public String fileName() {
    return fileName;
  }

  /** 差分の報告等に用いる種別名を返却するメソッド */
  public String label() {
    return label;
  }

  /**
   * スナップショットの1行（項目名をキーとするマップ）から、オブジェクトを識別する名前を取得するメソッド<br>
   * スナップショット同士の比較で、同じオブジェクトの行を突き合わせるキーとして用いる
   *
   * @param snapshot スナップショットの1行を項目名をキーとするマップへ変換したもの
   * @return {@code スキーマ名.名前}形式の識別名（関数・プロシージャの場合は引数を含む）
   */
  public String identify(Map<String, ?> snapshot) {
    return snapshot.get("schema") + "." + snapshot.get("name");
  }
}
