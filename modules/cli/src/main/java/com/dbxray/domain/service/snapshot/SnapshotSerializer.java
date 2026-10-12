package com.dbxray.domain.service.snapshot;

import java.util.List;
import java.util.Map;

/**
 * スキーマのスナップショット・参考情報（{@code domain.model.snapshot}・{@code domain.model.insight}配下のrecord）と
 * JSON文字列の相互変換を行うインタフェース<br>
 * JSONライブラリへの依存をドメイン層へ持ち込まないため、変換処理はインフラ層で実装する
 */
public interface SnapshotSerializer {

  /**
   * スナップショットのrecordを、改行を含まない1行のJSON文字列へ変換する<br>
   * 値が無い項目（null・空文字・空リスト）は出力しない。
   * 同じ内容からは常に同じ文字列を生成すること（差分検知で比較するため）
   *
   * @param snapshot スナップショットのrecord
   * @return 1行のJSON文字列（末尾の改行は含まない）
   */
  String serialize(Object snapshot);

  /**
   * {@link #serialize}で出力した1行のJSON文字列を、項目名をキーとするマップへ変換する<br>
   * スナップショット同士の比較で、1行がどのオブジェクトのものかを識別するために用いる
   *
   * @param json 1行のJSON文字列
   * @return 項目名をキー、値を値とするマップ
   */
  Map<String, Object> deserialize(String json);

  /**
   * {@link #serialize}で出力した1行のJSON文字列を、unified diffでの表示に適した複数行へ整形する<br>
   * トップレベルの項目は1項目1行、配列は1要素を1行とし、行末にカンマは付けない。値が変わった行に必ず
   * 項目名（配列内であれば少なくとも要素そのもの）が含まれるようにすることで、前後3行の文脈だけでも
   * 何が変わったか読み取れるようにする。1要素追加しただけで直前の行まで差分になるのを避ける狙いもある。
   * 項目の並び順は{@link #serialize}と同じ（同じ内容からは常に同じ結果を返す）
   *
   * @param json 1行のJSON文字列
   * @return 整形した行のリスト。JSONとして解釈できない場合は{@code json}をそのまま1件だけ含むリスト
   */
  List<String> formatForDiff(String json);
}
