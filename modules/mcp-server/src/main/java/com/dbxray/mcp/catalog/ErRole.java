package com.dbxray.mcp.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** テーブルのER上の区分。主キー・外部キーの構成から機械的に求める推測で、通常のテーブルは区分を持たない */
public enum ErRole {
  /** 多対多をつなぐ中間テーブル。主キーが、2つ以上の外部キー（論理リレーションを含む）の列だけで構成される */
  JUNCTION("junction");

  private final String value;

  ErRole(String value) {
    this.value = value;
  }

  /** ツールの結果・引数で使う値を返すメソッド */
  public String value() {
    return value;
  }

  /** 全区分の値を返すメソッド（ツールの入力スキーマの{@code enum}に使う） */
  public static List<String> allValues() {
    return Arrays.stream(values()).map(ErRole::value).toList();
  }

  /**
   * 値から区分を求めるメソッド
   *
   * @return 空文字・未知の値は空
   */
  public static Optional<ErRole> of(String value) {
    return Arrays.stream(values()).filter(role -> role.value.equals(value)).findFirst();
  }
}
