package com.dbxray.domain.model.relation;

import java.util.Arrays;
import java.util.Optional;

/**
 * 外部キーによる関連の多重度を表す列挙型<br>
 * 多重度は外部キーそのものではなく、参照元（子）テーブルの外部キー列に付与された制約から機械的に決まる。
 *
 * <ul>
 *   <li>親側（1件の子に対する親の件数）：外部キー列がすべてNOT NULLなら「ちょうど1」、NULLを許容するなら「0または1」
 *   <li>子側（1件の親に対する子の件数）：外部キー列が一意制約・一意索引で覆われているなら「0または1」、覆われていないなら「0以上」
 * </ul>
 *
 * 「親1件につき子が1件以上存在すること」はテーブル定義では表現できないため、子側が「1以上」となることはない。<br>
 * なお論理リレーション（{@link RelationType#LOGICAL}）はDBに制約が存在しないため上記の機械的判定ができない。
 * この場合はサイドカーYAMLでの明示指定（{@link #fromLabel(String)}）か、既定値の1対多が用いられる
 */
public enum Cardinality {
  /** 1対多。外部キー列はNOT NULLで一意ではない */
  ONE_TO_MANY("||", "o{", "1対多"),
  /** 0..1対多。外部キー列はNULLを許容し一意ではない */
  OPTIONAL_ONE_TO_MANY("|o", "o{", "0..1対多"),
  /** 1対1。外部キー列はNOT NULLかつ一意 */
  ONE_TO_ONE("||", "o|", "1対1"),
  /** 0..1対1。外部キー列はNULLを許容し一意 */
  OPTIONAL_ONE_TO_ONE("|o", "o|", "0..1対1");

  /**
   * 論理リレーションの多重度が省略された場合、または不正な値が指定された場合に用いる既定値<br>
   * DBに制約が存在せず機械的に判定できないため、外部キーの関連として最も一般的な1対多とみなす
   */
  public static final Cardinality DEFAULT_FOR_LOGICAL_RELATION = ONE_TO_MANY;

  /** Mermaidの関連線のうち参照先（親）側の端点表記 */
  private final String parentNotation;

  /** Mermaidの関連線のうち参照元（子）側の端点表記 */
  private final String childNotation;

  /** 一覧表などに掲載する日本語のラベル */
  private final String label;

  Cardinality(String parentNotation, String childNotation, String label) {
    this.parentNotation = parentNotation;
    this.childNotation = childNotation;
    this.label = label;
  }

  /**
   * Mermaidの関連線表記を返却するメソッド<br>
   * 「参照先（親） {@code 表記} 参照元（子）」の並びで用いる。 線種は関連の由来によって変わるため、多重度の端点表記と{@link RelationType}の線種を組み立てて返す
   *
   * @param relationType 関連の由来（物理／論理）
   * @return Mermaidの関連線表記
   */
  public String notation(RelationType relationType) {
    return parentNotation + relationType.lineNotation() + childNotation;
  }

  /**
   * 日本語のラベルを返却するメソッド
   *
   * @return 日本語のラベル
   */
  public String label() {
    return label;
  }

  /**
   * 参照元（子）テーブルの外部キー列に付与された制約から多重度を判定するメソッド
   *
   * @param unique 外部キー列が一意制約・一意索引で覆われている場合はtrue
   * @param mandatory 外部キー列がすべてNOT NULLの場合はtrue
   */
  public static Cardinality of(boolean unique, boolean mandatory) {
    if (unique) {
      return mandatory ? ONE_TO_ONE : OPTIONAL_ONE_TO_ONE;
    }
    return mandatory ? ONE_TO_MANY : OPTIONAL_ONE_TO_MANY;
  }

  /**
   * 日本語のラベルから多重度を逆引きするメソッド<br>
   * サイドカーYAMLで論理リレーションの多重度が明示指定された場合の変換に用いる。
   * 該当しない場合は空を返すため、呼び出し側で既定値の適用や警告を行う
   *
   * @param label 日本語のラベル（例: {@code 1対多}）。null・空文字の場合は空を返す
   * @return 対応する多重度。該当しない場合は空
   */
  public static Optional<Cardinality> fromLabel(String label) {
    if (label == null || label.isBlank()) {
      return Optional.empty();
    }
    final String stripped = label.strip();
    return Arrays.stream(values())
        .filter(cardinality -> cardinality.label.equals(stripped))
        .findFirst();
  }
}
