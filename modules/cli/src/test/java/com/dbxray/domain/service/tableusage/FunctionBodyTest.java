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

/** FunctionBody の各結果の状態・検証・保持のテスト */
class FunctionBodyTest {

  private static final List<SqlToken> TOKENS = SqlLexer.lex("DELETE FROM t", Dbms.ORACLE).tokens();

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TableUsageStatus.class,
      names = {"NOT_ANALYZED", "NO_DEFINITION", "WRAPPED", "SUBPROGRAM_NOT_FOUND"})
  @DisplayName("NotAnalyzable: 解析しない理由を状態に持つ")
  void testNotAnalyzable(TableUsageStatus status) {
    assertEquals(status, new FunctionBody.NotAnalyzable(status).status());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TableUsageStatus.class,
      names = {"ANALYZED", "UNSUPPORTED_LANGUAGE"})
  @DisplayName("NotAnalyzable: 解析した（ANALYZED）・言語名を持つ（UNSUPPORTED_LANGUAGE）状態は、解析しない結果にできない")
  void testNotAnalyzableRejectsOtherStatuses(TableUsageStatus status) {
    assertThrows(IllegalArgumentException.class, () -> new FunctionBody.NotAnalyzable(status));
  }

  @Test
  @DisplayName("UnsupportedLanguage: 状態はUNSUPPORTED_LANGUAGEで、言語名を持つ")
  void testUnsupportedLanguage() {
    final FunctionBody body = new FunctionBody.UnsupportedLanguage("plpython3u");

    assertEquals(TableUsageStatus.UNSUPPORTED_LANGUAGE, body.status());
    assertEquals("plpython3u", ((FunctionBody.UnsupportedLanguage) body).language());
  }

  @Test
  @DisplayName("Postgres: 状態はANALYZEDで、本体の字句を1つの本体として持ち、オーバーロードをまとめることはない")
  void testPostgres() {
    final FunctionBody.Postgres body = new FunctionBody.Postgres(TOKENS, true, List.of("a"));

    assertEquals(TableUsageStatus.ANALYZED, body.status());
    assertEquals(List.of(TOKENS), body.segments());
    assertTrue(body.incomplete());
    assertFalse(body.overloadsMerged());
  }

  @Test
  @DisplayName("Postgres: 検索パスは変更不可な複製として持ち、字句のリストは複製せずそのまま持つ")
  void testPostgresKeepsTokensAndCopiesSearchPath() {
    final List<String> searchPath = new ArrayList<>(List.of("a"));

    final FunctionBody.Postgres body = new FunctionBody.Postgres(TOKENS, false, searchPath);
    searchPath.add("b");

    assertSame(TOKENS, body.tokens(), "字句のリストは変更不可な包みのため複製しない");
    assertEquals(List.of("a"), body.searchPath());
    assertThrows(UnsupportedOperationException.class, () -> body.searchPath().add("c"));
  }

  @Test
  @DisplayName("Oracle: 状態はANALYZEDで、オーバーロードをまとめたか・実行者権限かを持つ")
  void testOracle() {
    final FunctionBody.Oracle body = new FunctionBody.Oracle(List.of(TOKENS), false, true, true);

    assertEquals(TableUsageStatus.ANALYZED, body.status());
    assertTrue(body.overloadsMerged());
    assertTrue(body.invokerRights());
  }

  @Test
  @DisplayName("Oracle: 字句のリストを束ねるリストは変更不可な複製として持ち、字句のリストは複製せずそのまま持つ")
  void testOracleKeepsSegmentsAsViews() {
    final List<SqlToken> segment = TOKENS.subList(1, TOKENS.size());
    final List<List<SqlToken>> segments = new ArrayList<>(List.of(segment));

    final FunctionBody.Oracle body = new FunctionBody.Oracle(segments, false, false, false);
    segments.clear();

    assertSame(segment, body.segments().get(0), "字句のリストは変更不可な包みのビューのため複製しない");
    assertThrows(UnsupportedOperationException.class, () -> body.segments().add(List.of()));
  }
}
