package com.dbxray.mcp.catalog;

/** 基準と相手のテーブルの、カラム名の集合の関係 */
public enum SimilarityKind {
  /** 両者のカラム名が一致する。複製・バックアップの疑いがある */
  IDENTICAL("identical"),
  /** 相手が基準の全カラムを持ち、さらに追加のカラムがある。履歴・拡張の疑いがある */
  SUPERSET("superset"),
  /** 相手が持つカラムは、基準の一部だけ */
  SUBSET("subset"),
  /** 基準にだけあるカラムも、相手にだけあるカラムもある */
  OVERLAP("overlap");

  private final String value;

  SimilarityKind(String value) {
    this.value = value;
  }

  /** ツールの結果で使う値を返すメソッド */
  public String value() {
    return value;
  }

  /**
   * カラム名の集合の関係から区分を求めるメソッド
   *
   * @param hasOnlyInBase 基準にだけあるカラムがあるか
   * @param hasOnlyInOther 相手にだけあるカラムがあるか
   */
  static SimilarityKind of(boolean hasOnlyInBase, boolean hasOnlyInOther) {
    if (hasOnlyInBase) {
      return hasOnlyInOther ? OVERLAP : SUBSET;
    }
    return hasOnlyInOther ? SUPERSET : IDENTICAL;
  }
}
