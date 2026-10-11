package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** レガシーなシステムにありがちな長い本体（日本語のコメント・コメントアウトした古いコード・入れ子のループ・多数のサブプログラム）からの抽出のテスト */
class RealisticFunctionBodiesTest {

  private static final String MONTHLY_CLOSING = resource("postgresql/monthly_closing.sql");

  private static final String BILLING_PKG = resource("oracle/billing_pkg.sql");

  private static final Tables SALES_TABLES =
      Tables.of(
          Stream.of(
                  "sales.orders",
                  "sales.order_items",
                  "sales.customers",
                  "sales.monthly_summary",
                  "sales.closing_log",
                  "sales.products",
                  "sales.old_summary",
                  "archive.orders_archive",
                  "public.orders")
              .map(RealisticFunctionBodiesTest::table)
              .toList());

  private static final Tables BILLING_TABLES =
      Tables.of(
          Stream.of(
                  "BILLING.INVOICES",
                  "BILLING.INVOICE_LINES",
                  "BILLING.CUSTOMERS",
                  "BILLING.PAYMENTS",
                  "BILLING.PAYMENT_ERRORS",
                  "BILLING.TAX_RATES",
                  "BILLING.BILLING_LOG",
                  "BILLING.CUSTOMER_BALANCES",
                  "BILLING.PRODUCTS",
                  "HR.EMPLOYEES")
              .map(RealisticFunctionBodiesTest::table)
              .toList());

  private static final String MONTHLY_CLOSING_EXPECTED =
      "archive.orders_archive:C, sales.closing_log:C, sales.customers:R,"
          + " sales.monthly_summary:CD, sales.order_items:R, sales.orders:RUD, sales.products:R"
          + " [dyn EXECUTE]";

  /** サブプログラムの名前・カタログの引数の並びと、期待する結果 */
  private static final Map<String, String> BILLING_EXPECTED =
      Map.ofEntries(
          Map.entry("LOG_MESSAGE|P_MSG VARCHAR2", "BILLING.BILLING_LOG:C"),
          Map.entry("GET_TAX_RATE|P_DATE DATE", "BILLING.TAX_RATES:R"),
          Map.entry(
              "CREATE_INVOICE|P_CUSTOMER_ID NUMBER", "BILLING.CUSTOMERS:R, BILLING.INVOICES:C"),
          Map.entry(
              "ADD_LINE|P_INVOICE_ID NUMBER, P_AMOUNT NUMBER",
              "BILLING.INVOICES:U, BILLING.INVOICE_LINES:C"),
          Map.entry(
              "ADD_LINE|P_INVOICE_ID NUMBER, P_PRODUCT VARCHAR2, P_QTY NUMBER",
              "BILLING.INVOICE_LINES:CU"),
          Map.entry(
              "APPLY_PAYMENT|P_INVOICE_ID NUMBER, P_AMOUNT NUMBER",
              "BILLING.CUSTOMER_BALANCES:CU, BILLING.INVOICES:RU, BILLING.PAYMENTS:C,"
                  + " BILLING.PAYMENT_ERRORS:C"),
          Map.entry("PURGE_OLD|P_BEFORE DATE", "BILLING.INVOICES:RD, BILLING.INVOICE_LINES:D"),
          Map.entry(
              "REBUILD_BALANCES|",
              "BILLING.CUSTOMER_BALANCES:C, BILLING.INVOICES:R [dyn EXECUTE IMMEDIATE]"),
          Map.entry("OPEN_REPORT|P_SQL VARCHAR2", "[dyn OPEN FOR]"),
          Map.entry("CLOSE_MONTH|P_MONTH DATE", "BILLING.CUSTOMERS:R, BILLING.INVOICES:RU"),
          Map.entry("EMPLOYEE_NAME|P_ID NUMBER", "HR.EMPLOYEES:R"));

  static Stream<String> billingSubprograms() {
    return BILLING_EXPECTED.keySet().stream().sorted();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("billingSubprograms")
  @DisplayName("analyze: 11個のサブプログラムを持つパッケージから、サブプログラムごとに利用しているテーブルを抽出する")
  void testBillingPackage(String key) {
    assertEquals(BILLING_EXPECTED.get(key), analyzeBilling(key, BILLING_PKG));
  }

  @Test
  @DisplayName("analyze: 数十行の締め処理のプロシージャから、コメント・文字列の中を除いて利用しているテーブルを抽出する")
  void testMonthlyClosing() {
    assertEquals(MONTHLY_CLOSING_EXPECTED, analyzeMonthlyClosing(MONTHLY_CLOSING));
  }

  @TestFactory
  @DisplayName("analyze: 長い定義全体を変形しても、結果が変わらない")
  Stream<DynamicTest> testTransformedDefinitions() {
    final Stream<DynamicTest> postgres =
        SqlTransforms.forDbms(Dbms.POSTGRESQL).stream()
            .filter(transform -> !transform.addsStatements())
            .map(
                transform ->
                    dynamicTest(
                        "PG: " + transform.name(),
                        () ->
                            assertEquals(
                                MONTHLY_CLOSING_EXPECTED,
                                analyzeMonthlyClosing(
                                    transform.apply(
                                        MONTHLY_CLOSING, Dbms.POSTGRESQL, List.of("orders"))))));
    final Stream<DynamicTest> oracle =
        SqlTransforms.forDbms(Dbms.ORACLE).stream()
            .filter(transform -> !transform.addsStatements())
            .map(
                transform ->
                    dynamicTest(
                        "ORA: " + transform.name(),
                        () -> {
                          final String transformed =
                              transform.apply(BILLING_PKG, Dbms.ORACLE, List.of("INVOICES"));
                          BILLING_EXPECTED.forEach(
                              (key, expected) ->
                                  assertEquals(expected, analyzeBilling(key, transformed), key));
                        }));
    return Stream.concat(postgres, oracle);
  }

  private static String analyzeMonthlyClosing(String definition) {
    return FunctionTableUsageAnalyzerTest.describe(
        FunctionTableUsageAnalyzer.of(SALES_TABLES, Dbms.POSTGRESQL)
            .analyze(
                new FunctionEntity(
                    "db",
                    "sales",
                    "monthly_closing",
                    1,
                    1,
                    "PROCEDURE",
                    "IN p_target_month date",
                    "",
                    "plpgsql",
                    definition)));
  }

  private static String analyzeBilling(String key, String definition) {
    final String[] nameAndArguments = key.split("\\|", -1);
    return FunctionTableUsageAnalyzerTest.describe(
        FunctionTableUsageAnalyzer.of(BILLING_TABLES, Dbms.ORACLE)
            .analyze(
                new FunctionEntity(
                    "db",
                    "BILLING",
                    "BILLING_PKG." + nameAndArguments[0],
                    1,
                    1,
                    "PROCEDURE",
                    nameAndArguments[1],
                    "",
                    "PL/SQL",
                    definition)));
  }

  private static TableEntity table(String qualifiedName) {
    final int dot = qualifiedName.indexOf('.');
    return new TableEntity(
        "db",
        qualifiedName.substring(0, dot),
        "",
        qualifiedName.substring(dot + 1),
        TableType.TABLE,
        "");
  }

  private static String resource(String name) {
    try (InputStream in =
        RealisticFunctionBodiesTest.class.getResourceAsStream("/tableusage/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
