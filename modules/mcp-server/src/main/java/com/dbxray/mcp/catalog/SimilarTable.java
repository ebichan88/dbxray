package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * 基準に構造が似ている1テーブル
 *
 * @param similarity 類似度（%。0〜100）。共通のカラムの重みの合計が、基準と相手のカラムの重みの合計に占める割合
 * @param kind 基準と相手のカラム名の集合の関係
 * @param sharedColumns 共通のカラムの数
 * @param onlyInBase 基準にだけあるカラムの名前（基準での並び順）
 * @param onlyInOther 相手にだけあるカラムの名前（相手での並び順）
 * @param typeMismatches 共通のカラムのうち、型が異なるもの。基準の型が分からない場合は空
 */
public record SimilarTable(
    TableEntry table,
    int similarity,
    SimilarityKind kind,
    int sharedColumns,
    List<String> onlyInBase,
    List<String> onlyInOther,
    List<TypeMismatch> typeMismatches) {

  /** 複製して保持する */
  public SimilarTable {
    onlyInBase = List.copyOf(onlyInBase);
    onlyInOther = List.copyOf(onlyInOther);
    typeMismatches = List.copyOf(typeMismatches);
  }

  /**
   * 共通のカラムで型が異なるもの
   *
   * @param column 基準でのカラム名
   */
  public record TypeMismatch(String column, String baseType, String otherType) {}
}
