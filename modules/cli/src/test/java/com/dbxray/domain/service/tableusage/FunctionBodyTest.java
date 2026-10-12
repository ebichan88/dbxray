package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FunctionBody の生成メソッドのテスト */
class FunctionBodyTest {

  @Test
  @DisplayName("notAnalyzable: 解析しない理由を状態に持ち、字句・言語名は持たない")
  void testNotAnalyzable() {
    final FunctionBody body = FunctionBody.notAnalyzable(TableUsageStatus.WRAPPED);

    assertEquals(TableUsageStatus.WRAPPED, body.status());
    assertEquals(List.of(), body.segments());
    assertEquals("", body.language());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TableUsageStatus.class,
      names = {"ANALYZED", "UNSUPPORTED_LANGUAGE"})
  @DisplayName("notAnalyzable: 解析した（ANALYZED）・言語名を持つ（UNSUPPORTED_LANGUAGE）状態は、解析しない結果にできない")
  void testNotAnalyzableRejectsOtherStatuses(TableUsageStatus status) {
    assertThrows(IllegalArgumentException.class, () -> FunctionBody.notAnalyzable(status));
  }

  @Test
  @DisplayName("unsupportedLanguage: 言語名を持ち、字句は持たない")
  void testUnsupportedLanguage() {
    final FunctionBody body = FunctionBody.unsupportedLanguage("plpython3u");

    assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, body.status());
    assertEquals("plpython3u", body.language());
    assertEquals(List.of(), body.segments());
  }

  @Test
  @DisplayName("コンストラクタ: 字句のリストは複製せず、字句のリストを束ねるリストと検索パスは変更不可な複製にする")
  void testSegmentsAreKeptAsViews() {
    final List<SqlToken> tokens = SqlLexer.lex("DELETE FROM t", Dbms.ORACLE).tokens();
    final List<SqlToken> segment = tokens.subList(1, tokens.size());
    final List<List<SqlToken>> segments = new ArrayList<>(List.of(segment));
    final List<String> searchPath = new ArrayList<>(List.of("a"));

    final FunctionBody body = FunctionBody.oracle(segments, false, false, false);
    final FunctionBody postgres = FunctionBody.postgres(tokens, false, searchPath);
    segments.clear();
    searchPath.add("b");

    assertSame(segment, body.segments().get(0), "字句のリストは変更不可な包みのビューのため複製しない");
    assertThrows(UnsupportedOperationException.class, () -> body.segments().add(List.of()));
    assertEquals(List.of("a"), postgres.searchPath());
  }
}
