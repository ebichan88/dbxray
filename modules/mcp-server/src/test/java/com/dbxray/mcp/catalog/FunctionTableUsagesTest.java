package com.dbxray.mcp.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link FunctionTableUsages}のテスト */
class FunctionTableUsagesTest {

  private static final ObjectKey KEY = new ObjectKey("testdb", "sample", "f");

  @Test
  @DisplayName("find: キーと引数が一致し、定義本体のハッシュも一致する参考情報を返す")
  void findsMatchingHash() {
    final FunctionTableUsageEntry usage = usage("p integer", "hash1");

    assertEquals(
        Optional.of(usage),
        new FunctionTableUsages(List.of(usage)).find(function("p integer", "hash1")));
    assertTrue(
        new FunctionTableUsages(List.of(usage)).find(function("p text", "hash1")).isEmpty(),
        "引数の違うオーバーロードには対応付けない");
  }

  @Test
  @DisplayName("find: 定義本体のハッシュが異なる（定義が変わった後の古い）参考情報・定義本体が無くなった関数の参考情報は返さない")
  void ignoresStaleUsage() {
    final FunctionTableUsages usages = new FunctionTableUsages(List.of(usage("", "old")));

    assertTrue(usages.find(function("", "new")).isEmpty());
    assertTrue(usages.find(function("", "")).isEmpty());
  }

  @Test
  @DisplayName("find: ハッシュを持たない参考情報は、定義本体のある関数には返さない（定義本体を取得できなかった後に取得できるようになった場合）")
  void ignoresUsageWithoutHashForFunctionWithDefinition() {
    final FunctionTableUsages usages = new FunctionTableUsages(List.of(usage("", "")));

    assertTrue(usages.find(function("", "h")).isEmpty());
  }

  @Test
  @DisplayName("find: 定義本体が無い関数（スナップショットのハッシュが空）には、ハッシュを持たない参考情報を返す")
  void findsUsageWithoutHashForFunctionWithoutDefinition() {
    final FunctionTableUsageEntry usage = usage("", "");

    assertEquals(
        Optional.of(usage), new FunctionTableUsages(List.of(usage)).find(function("", "")));
  }

  private static FunctionTableUsageEntry usage(String arguments, String definitionHash) {
    return new FunctionTableUsageEntry(
        KEY,
        arguments,
        definitionHash,
        FunctionTableUsageEntry.ANALYZED,
        "",
        List.of(),
        List.of(),
        false,
        false);
  }

  private static FunctionEntry function(String arguments, String definitionHash) {
    return new FunctionEntry(KEY, "FUNCTION", arguments, "void", "sql", "{}", definitionHash);
  }
}
