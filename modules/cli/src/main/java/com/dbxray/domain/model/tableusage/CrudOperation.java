package com.dbxray.domain.model.tableusage;

/** テーブルに対する操作の種別（C・R・U・D） */
public enum CrudOperation {
  /** {@code INSERT}・{@code MERGE}の{@code INSERT} */
  CREATE("C"),
  /** {@code FROM}・{@code JOIN}・{@code USING}で読む */
  READ("R"),
  /** {@code UPDATE}・{@code MERGE}の{@code UPDATE}・{@code ON CONFLICT DO UPDATE} */
  UPDATE("U"),
  /** {@code DELETE}・{@code TRUNCATE}・{@code MERGE}の{@code DELETE} */
  DELETE("D");

  private final String letter;

  CrudOperation(String letter) {
    this.letter = letter;
  }

  /**
   * 1文字の略号を取得するメソッド
   *
   * @return {@code C}・{@code R}・{@code U}・{@code D}
   */
  public String letter() {
    return letter;
  }
}
