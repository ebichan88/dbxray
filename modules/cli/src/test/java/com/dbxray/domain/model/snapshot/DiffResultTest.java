package com.dbxray.domain.model.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DiffResult の差分の有無（--checkの終了状態を決める判定）に関するテスト */
public class DiffResultTest {

  private static final List<String> NONE = List.of();

  @Test
  @DisplayName("hasDifference: どの区分にも差分が無い場合だけfalse")
  void testNoDifference() {
    assertFalse(new DiffResult(NONE, NONE, List.of()).hasDifference());
  }

  @Test
  @DisplayName("hasDifference: 3つの区分のどれか1つだけに差分がある場合も、それぞれtrue")
  void testDifferenceInEachCategoryAlone() {
    List<String> one = List.of("table public.t1");
    ContentDiff contentDiff = new ContentDiff("table public.t1", List.of("--- a", "+++ b"));

    assertTrue(new DiffResult(one, NONE, List.of()).hasDifference(), "生成側にのみ存在");
    assertTrue(new DiffResult(NONE, one, List.of()).hasDifference(), "コミット側にのみ存在");
    assertTrue(new DiffResult(NONE, NONE, List.of(contentDiff)).hasDifference(), "内容が一致しない");
  }
}
