package com.dbxray.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 関数・プロシージャが利用しているテーブルの参考情報を、スナップショットの関数（オーバーロード）から引くクラス<br>
 * 関数はスナップショットと同じく、キー（DB名・スキーマ名・名前）と引数で識別する。cliで{@code --rm-dist}を付けずに出力し直すと前回の参考情報が残るため （{@code
 * --preview}を付けなかった場合等）、定義本体のハッシュがスナップショットと異なるもの（定義が変わった後の古い結果）は返さない
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
    return Optional.ofNullable(byFunction.get(new Identity(function.key(), function.arguments())))
        .filter(
            usage ->
                usage.definitionHash().isEmpty()
                    || usage.definitionHash().equals(function.definitionHash()));
  }

  /** 関数を識別する組（オーバーロードは引数で区別する） */
  private record Identity(ObjectKey key, String arguments) {

    static Identity of(FunctionTableUsageEntry entry) {
      return new Identity(entry.key(), entry.arguments());
    }
  }
}
