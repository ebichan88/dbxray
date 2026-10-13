package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * テーブルを変更・削除したときに影響を受けるものと、テーブルが依存するものをまとめた結果<br>
 * 影響を受けるもの（参照元・ビュー・トリガー・関数）と依存先（シーケンス・型）は向きが逆のため、別の項目に分けて持つ
 *
 * @param table 調べたテーブル（ビュー・マテリアライズドビューの場合もある）
 * @param directReferences 外部キー・論理リレーションで自テーブルを直接参照している関連（段1）。自己参照を含む。
 *     参照元のテーブル・関連の種別の順
 * @param indirectReferences 段1には現れず、{@value RelationCounts#IMPACT_DEPTH}段までの関連で自テーブルに届くテーブル。段数・テーブルの順
 * @param views 自テーブルを参照しているビュー・マテリアライズドビュー（ビューを参照するビューを含む）。段数・テーブルの順
 * @param triggers 自テーブルのトリガー
 * @param functions 自テーブルを使う関数・プロシージャ
 * @param sequences 自テーブルのカラムがデフォルト値の{@code nextval}で使うシーケンス
 * @param types 自テーブルのカラムの型に使われている、スナップショットにあるユーザー定義型
 */
public record TableImpact(
    TableEntry table,
    List<Relation> directReferences,
    List<IndirectReference> indirectReferences,
    List<ViewReference> views,
    List<TriggerEntry> triggers,
    FunctionImpact functions,
    List<SequenceUse> sequences,
    List<TypeUse> types) {

  /** 複製して保持する */
  public TableImpact {
    directReferences = List.copyOf(directReferences);
    indirectReferences = List.copyOf(indirectReferences);
    views = List.copyOf(views);
    triggers = List.copyOf(triggers);
    sequences = List.copyOf(sequences);
    types = List.copyOf(types);
  }

  /**
   * 間接的に自テーブルを参照しているテーブル
   *
   * @param depth 自テーブルから何段目で見つけたか（最小の段数。2以上）
   * @param via その段で見つけた関連の参照先（最初に見つけた1つ）
   */
  public record IndirectReference(ObjectKey table, int depth, ObjectKey via) {}

  /**
   * 自テーブルを参照しているビュー
   *
   * @param via ビューが参照しているビュー。自テーブルを直接参照している場合はnull
   */
  public record ViewReference(TableEntry view, ObjectKey via) {}

  /**
   * 自テーブルを使う関数・プロシージャ
   *
   * @param coverage 関数の参考情報で調べがついた範囲
   * @param usages 自テーブルを使う関数。関数のキー順で、同じ関数は自テーブル・ビューの順
   * @param unverified 自テーブルを使うかどうか確かめきれない関数（{@code usages}に載ったオーバーロードを除く）。
   *     理由（{@link UnverifiedFunction.Reason}の順）・関数のキー・引数の順
   */
  public record FunctionImpact(
      FunctionCoverage coverage, List<FunctionUse> usages, List<UnverifiedFunction> unverified) {

    /** 複製して保持する */
    public FunctionImpact {
      usages = List.copyOf(usages);
      unverified = List.copyOf(unverified);
    }
  }

  /**
   * 自テーブルを使うかどうか、参考情報から確かめきれない関数のオーバーロード1つ
   *
   * @param arguments 引数。引数が無い場合は空文字
   * @param reason 確かめきれない理由
   */
  public record UnverifiedFunction(ObjectKey function, String arguments, Reason reason) {

    /** 確かめきれない理由。1つのオーバーロードに複数当てはまる場合は、宣言順で先のものを採る */
    public enum Reason {
      /** 動的SQLを含む（動的SQLの中の参照は参考情報に含まれない） */
      DYNAMIC_SQL("dynamicSql"),
      /** 定義本体を最後まで読めなかった */
      INCOMPLETE("incomplete"),
      /** 参考情報はあるが、解析しなかった（対象外の言語・難読化・定義本体が無い等） */
      UNSUPPORTED("unsupported"),
      /** 参考情報はあるが、抽出元の定義本体がスナップショットと異なる */
      STALE("stale"),
      /** 参考情報が無い */
      NO_INSIGHT("noInsight");

      private final String value;

      Reason(String value) {
        this.value = value;
      }

      /** ツールの結果で使う値を返すメソッド */
      public String value() {
        return value;
      }
    }
  }

  /**
   * テーブルのカラムが使うシーケンス
   *
   * @param columns シーケンスを使うカラムの名前（カラムの並び順）
   */
  public record SequenceUse(ObjectKey sequence, List<String> columns) {

    /** 複製して保持する */
    public SequenceUse {
      columns = List.copyOf(columns);
    }
  }

  /**
   * テーブルのカラムの型に使われているユーザー定義型
   *
   * @param columns 型を使うカラムの名前（カラムの並び順）
   */
  public record TypeUse(TypeEntry type, List<String> columns) {

    /** 複製して保持する */
    public TypeUse {
      columns = List.copyOf(columns);
    }
  }
}
