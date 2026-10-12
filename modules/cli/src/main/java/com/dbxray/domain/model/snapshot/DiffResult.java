package com.dbxray.domain.model.snapshot;

import java.util.List;

/**
 * DBから生成したスキーマのスナップショットと、既にコミット済みのものとの差分結果を表すrecord<br>
 * {@code onlyInGenerated}・{@code onlyInCommitted}の各要素は差分の対象を表す表示用の文字列で、
 * オブジェクト（例: {@code table sample.employee}）またはファイル（例: {@code database.json}）の識別名となる
 *
 * @param onlyInGenerated 生成側にのみ存在するもの（コミット漏れの可能性）
 * @param onlyInCommitted コミット側にのみ存在するもの（削除されたテーブル等の残骸の可能性）
 * @param contentDiffer 両方に存在するが内容が一致しないもの（unified diff付き）
 */
public record DiffResult(
    List<String> onlyInGenerated, List<String> onlyInCommitted, List<ContentDiff> contentDiffer) {

  /**
   * 差分の有無を返すメソッド
   *
   * @return いずれかの区分に1件でも差分が存在する場合true
   */
  public boolean hasDifference() {
    return !onlyInGenerated.isEmpty() || !onlyInCommitted.isEmpty() || !contentDiffer.isEmpty();
  }
}
