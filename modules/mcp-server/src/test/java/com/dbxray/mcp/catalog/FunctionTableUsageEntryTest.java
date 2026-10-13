package com.dbxray.mcp.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable.Match;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link FunctionTableUsageEntry.UsedTable}のテスト */
class FunctionTableUsageEntryTest {

  private static final ObjectKey EMPLOYEE = new ObjectKey("testdb", "sample", "employee");

  @Test
  @DisplayName("matches: スキーマが決まった名前は、スキーマと名前が一致すればCONFIRMED、どちらかが違えばNONE")
  void matchesResolvedSchema() {
    assertEquals(Match.CONFIRMED, used("sample", "employee", List.of()).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("other", "employee", List.of()).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("sample", "department", List.of()).matches(EMPLOYEE));
  }

  @Test
  @DisplayName("matches: スキーマが決まらない名前は、名前が一致し候補のスキーマにテーブルのスキーマが含まれる場合だけCANDIDATE")
  void matchesCandidateSchema() {
    assertEquals(
        Match.CANDIDATE, used("", "employee", List.of("other", "sample")).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("", "employee", List.of("other")).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("", "employee", List.of()).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("", "department", List.of("sample")).matches(EMPLOYEE));
  }

  @Test
  @DisplayName("matches: 大文字小文字を区別して比べる（参考情報はカタログ上の名前で出力されるため）")
  void matchesCaseSensitively() {
    assertEquals(Match.NONE, used("sample", "Employee", List.of()).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("SAMPLE", "employee", List.of()).matches(EMPLOYEE));
    assertEquals(Match.NONE, used("", "employee", List.of("SAMPLE")).matches(EMPLOYEE));
  }

  private static UsedTable used(String schema, String name, List<String> candidates) {
    return new UsedTable(
        schema, name, schema.isEmpty() ? "search_path" : "", candidates, List.of());
  }
}
