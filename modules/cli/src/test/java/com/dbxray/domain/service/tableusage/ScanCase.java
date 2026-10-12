package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * テーブルの位置の判定のテストケース1件（本体のSQLと、期待する結果の短い記法）
 *
 * @param expected {@code "sample.employee:RU, audit_log:C [dyn EXECUTE]"}の形。名前は拾った順、操作はC・R・U・Dの順。何も無ければ{@code "-"}
 */
record ScanCase(Dbms dbms, String sql, String expected) {

  /**
   * PostgreSQL・Oracleの両方で同じ結果になるケース（期待値の名前は小文字で書き、Oracleでは大文字にする）<br>
   * 動的SQLの{@code [dyn …]}は、DBMSごとに種類の名前が違うため大文字にせず、そのまま使う
   */
  static List<ScanCase> both(String sql, String expected) {
    final int dynamicStart = expected.indexOf('[');
    final String oracleExpected =
        dynamicStart < 0
            ? AsciiCase.toUpper(expected)
            : AsciiCase.toUpper(expected.substring(0, dynamicStart))
                + expected.substring(dynamicStart);
    return List.of(
        new ScanCase(Dbms.POSTGRESQL, sql, expected),
        new ScanCase(Dbms.ORACLE, sql, oracleExpected));
  }

  static ScanCase pg(String sql, String expected) {
    return new ScanCase(Dbms.POSTGRESQL, sql, expected);
  }

  static ScanCase ora(String sql, String expected) {
    return new ScanCase(Dbms.ORACLE, sql, expected);
  }

  /** 期待値に現れるテーブルの名前（スキーマ修飾を含む） */
  List<String> expectedNames() {
    return ExpectedNotation.tableNames(expected);
  }

  @Override
  public String toString() {
    return (dbms == Dbms.POSTGRESQL ? "PG: " : "ORA: ") + sql.replace("\n", "⏎");
  }

  /** 本体を字句に分けて走査する */
  static ScanResult scan(String sql, Dbms dbms) {
    return TableReferenceScanner.scan(SqlLexer.lex(sql, dbms).tokens(), dbms);
  }

  /** 走査の結果を、期待値の記法にする */
  static String describe(ScanResult result) {
    final Map<String, Set<CrudOperation>> byName = new LinkedHashMap<>();
    for (final TableReference reference : result.references()) {
      final String name =
          reference.schema().isEmpty()
              ? reference.table()
              : reference.schema() + "." + reference.table();
      byName
          .computeIfAbsent(name, k -> EnumSet.noneOf(CrudOperation.class))
          .add(reference.operation());
    }
    final String references =
        byName.entrySet().stream()
            .map(
                entry ->
                    entry.getKey()
                        + ":"
                        + entry.getValue().stream()
                            .map(CrudOperation::letter)
                            .collect(Collectors.joining()))
            .collect(Collectors.joining(", "));
    final String dynamic =
        result.dynamicSql().isEmpty()
            ? ""
            : "[dyn "
                + result.dynamicSql().stream()
                    .map(DynamicSqlKind::label)
                    .collect(Collectors.joining(","))
                + "]";
    if (references.isEmpty() && dynamic.isEmpty()) {
      return "-";
    }
    return references.isEmpty() || dynamic.isEmpty()
        ? references + dynamic
        : references + " " + dynamic;
  }
}
