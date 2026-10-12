package com.dbxray.domain.service.writer.template;

/**
 * 表の要素1つを、Markdownの表の1行にする関数
 *
 * @param <T> 表の要素の型
 */
@FunctionalInterface
public interface RowRenderer<T> {

  /**
   * 表の1行を組み立てるメソッド
   *
   * @param rowNumber 表の中での行番号（1始まり。「No.」列に表示する）
   * @param row 表の要素
   * @return 1行分の文字列（末尾の改行を含むかは表ごとに決める）
   */
  String render(int rowNumber, T row);
}
