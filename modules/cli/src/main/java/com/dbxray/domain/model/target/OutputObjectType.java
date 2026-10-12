package com.dbxray.domain.model.target;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 出力対象を絞り込める追加オブジェクト（テーブル以外）の種別を表す列挙型 */
public enum OutputObjectType {
  /** トリガー */
  TRIGGER("trigger"),
  /** 関数・プロシージャ */
  FUNCTION("function"),
  /** シーケンス */
  SEQUENCE("sequence"),
  /** ユーザー定義型 */
  TYPE("type");

  private final String id;

  OutputObjectType(String id) {
    this.id = id;
  }

  /**
   * 種別を示す名前を取得するメソッド
   *
   * @return 設定ファイルの{@code target.objects}に書く名前（例: {@code trigger}）
   */
  public String id() {
    return id;
  }

  /**
   * 名前から種別を求めるメソッド
   *
   * @param id 種別を示す名前（{@link #id()}）
   * @throws IllegalArgumentException 対応していない名前の場合
   */
  public static OutputObjectType fromId(String id) {
    return Arrays.stream(values())
        .filter(type -> Objects.equals(id, type.id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown output object type: " + id));
  }

  /**
   * 設定ファイルの{@code target.objects}の値から、出力する種別の集合を求めるメソッド<br>
   * 種別の名前は前後の空白を除去して解釈し、空要素は無視する。指定が空の場合は、すべての種別を出力対象とする
   *
   * @param rawList 種別の名前（{@link #id()}）のリスト
   * @return 出力する種別の集合（変更不可）
   * @throws IllegalArgumentException 未知の種別の名前が含まれる場合
   */
  public static Set<OutputObjectType> parse(List<String> rawList) {
    final List<String> names =
        rawList.stream().map(String::strip).filter(name -> !name.isEmpty()).toList();
    if (names.isEmpty()) {
      return Collections.unmodifiableSet(EnumSet.allOf(OutputObjectType.class));
    }
    final EnumSet<OutputObjectType> result = EnumSet.noneOf(OutputObjectType.class);
    names.stream().map(OutputObjectType::fromId).forEach(result::add);
    return Collections.unmodifiableSet(result);
  }
}
