package com.dbxray.mcp.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 基準に構造が似ている1テーブル
 *
 * @param similarity 類似度（%。0〜100）。共通のカラムの重みの合計が、基準と相手のカラムの重みの合計に占める割合。
 *     100は{@link SimilarityKind#IDENTICAL}の場合だけで、それ以外は丸めても99までとする
 * @param kind 基準と相手のカラム名の集合の関係
 * @param sharedColumns 共通のカラムの数
 * @param onlyInBase 基準にだけあるカラムの名前（基準での並び順）
 * @param onlyInOther 相手にだけあるカラムの名前（相手での並び順）
 * @param typeMismatches 共通のカラムのうち、型が異なるもの。基準の型が分からない場合は空
 * @param relatedToBase 基準と外部キー・論理リレーションで直接つながるか。基準をカラム名で指定した場合はfalse
 */
public record SimilarTable(
    TableEntry table,
    int similarity,
    SimilarityKind kind,
    int sharedColumns,
    List<String> onlyInBase,
    List<String> onlyInOther,
    List<TypeMismatch> typeMismatches,
    boolean relatedToBase) {

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
  public record TypeMismatch(
      String column, String baseType, String otherType, TypeDifference difference) {

    /**
     * 同名のカラムの型を比べるメソッド
     *
     * @return 型が同じ場合、どちらかの型が未設定の場合は空
     */
    static Optional<TypeMismatch> between(ColumnEntry base, ColumnEntry other) {
      if (base.type().isEmpty() || other.type().isEmpty()) {
        return Optional.empty();
      }
      return TypeDifference.between(ColumnType.parse(base.type()), ColumnType.parse(other.type()))
          .map(difference -> new TypeMismatch(base.name(), base.type(), other.type(), difference));
    }
  }

  /** 型の違いの程度 */
  public enum TypeDifference {
    /** 型の名前が異なる（{@code integer}と{@code numeric(10,2)}等） */
    TYPE("type"),
    /** 型の名前は同じで、長さ・精度等の指定だけが異なる（{@code character varying(10)}と{@code character varying(50)}等） */
    SIZE("size");

    private final String value;

    TypeDifference(String value) {
      this.value = value;
    }

    /** ツールの結果で使う値を返すメソッド */
    public String value() {
      return value;
    }

    /**
     * 2つの型の違いを求めるメソッド
     *
     * @return 表記の違いが大文字小文字・全角半角・空白だけの場合は空
     */
    static Optional<TypeDifference> between(ColumnType base, ColumnType other) {
      if (base.equals(other)) {
        return Optional.empty();
      }
      return Optional.of(base.name().equals(other.name()) ? SIZE : TYPE);
    }
  }
}
