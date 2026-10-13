package com.dbxray.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 関数・プロシージャが利用しているテーブルの参考情報を、スナップショットの関数（オーバーロード）から引くクラス<br>
 * 関数はスナップショットと同じく、キー（DB名・スキーマ名・名前）と引数で識別する。参考情報はスナップショットとは別のファイルのため、
 * 片方だけが出力し直された場合（{@code --rm-dist}を付けずに対象のスキーマを減らした場合等）に備え、
 * 定義本体のハッシュがスナップショットと異なるもの（定義が変わった後の古い結果）は返さない。定義本体が無い関数は、どちらのハッシュも空文字で一致する
 */
public final class FunctionTableUsages {

  private final Map<Identity, FunctionTableUsageEntry> byFunction = new LinkedHashMap<>();

  FunctionTableUsages(List<FunctionTableUsageEntry> entries) {
    entries.forEach(entry -> byFunction.putIfAbsent(Identity.of(entry), entry));
  }

  /**
   * 関数・プロシージャ（オーバーロード1つ）の利用しているテーブルを求めるメソッド
   *
   * @return 参考情報が無い場合（cliで{@code --preview}を付けずに出力した場合等）・定義本体が変わった後の古い参考情報の場合は空
   */
  public Optional<FunctionTableUsageEntry> find(FunctionEntry function) {
    return Optional.ofNullable(byFunction.get(identityOf(function)))
        .filter(usage -> usage.definitionHash().equals(function.definitionHash()));
  }

  /**
   * 関数の参考情報が、スナップショットと対応付けられるか判定するメソッド<br>
   * {@link #find}が空を返す理由を区別する。利用者がすべきことが違うため（無ければ{@code --preview}を付けて出力し、古ければ出力し直す）
   */
  public Freshness freshnessOf(FunctionEntry function) {
    final FunctionTableUsageEntry usage = byFunction.get(identityOf(function));
    if (usage == null) {
      return Freshness.ABSENT;
    }
    return usage.definitionHash().equals(function.definitionHash())
        ? Freshness.CURRENT
        : Freshness.STALE;
  }

  /** 関数の参考情報と、スナップショットの関数との対応 */
  public enum Freshness {
    /** 参考情報があり、抽出元の定義本体がスナップショットと一致する */
    CURRENT,
    /** 参考情報はあるが、抽出元の定義本体がスナップショットと異なる（参考情報の出力後に定義が変わった） */
    STALE,
    /** 参考情報が無い（{@code --preview}を付けずに出力した場合等） */
    ABSENT
  }

  private static Identity identityOf(FunctionEntry function) {
    return new Identity(function.key(), function.arguments());
  }

  /** 関数を識別する組（オーバーロードは引数で区別する） */
  private record Identity(ObjectKey key, String arguments) {

    static Identity of(FunctionTableUsageEntry entry) {
      return new Identity(entry.key(), entry.arguments());
    }
  }
}
