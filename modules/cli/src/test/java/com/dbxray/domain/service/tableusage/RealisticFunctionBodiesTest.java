package com.dbxray.domain.service.tableusage;

import static com.dbxray.testsupport.EntityFixtures.function;
import static com.dbxray.testsupport.EntityFixtures.table;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.table.Tables;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

  /** 出力対象のテーブル。{@code orders_tmp}・{@code orders_hold}は、コメント・文字列の中にだけ書いてある（期待値には現れない）テーブル */
  private static final Tables SALES_TABLES =
      tables(
          "sales.orders",
          "sales.order_items",
          "sales.customers",
          "sales.monthly_summary",
          "sales.closing_log",
          "sales.products",
          "sales.old_summary",
          "sales.orders_tmp",
          "sales.orders_hold",
          "archive.orders_archive",
          "public.orders");

  /**
   * 出力対象のテーブル。{@code legacy_invoices}・{@code invoice_history}は、コメント・文字列の中にだけ書いてあり、 {@code
   * init_audit}は、パッケージの初期化の処理だけが書き込む（いずれも期待値には現れない）
   */
  private static final Tables BILLING_TABLES =
      tables(
          "BILLING.INVOICES",
          "BILLING.INVOICE_LINES",
          "BILLING.CUSTOMERS",
          "BILLING.PAYMENTS",
          "BILLING.PAYMENT_ERRORS",
          "BILLING.TAX_RATES",
          "BILLING.BILLING_LOG",
          "BILLING.CUSTOMER_BALANCES",
          "BILLING.PRODUCTS",
          "BILLING.LEGACY_INVOICES",
          "BILLING.INVOICE_HISTORY",
          "BILLING.INIT_AUDIT",
          "HR.EMPLOYEES");

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
          Map.entry("EMPLOYEE_NAME|P_ID NUMBER", "HR.EMPLOYEES:R"),
          Map.entry("ASSERT_OPEN|P_INVOICE_ID NUMBER", "BILLING.INVOICES:R"));

  /** 実行のたびに同じ順で確かめ、失敗したときにどのサブプログラムかを再現できるよう、並べ替えておく */
  private static final List<String> BILLING_KEYS =
      BILLING_EXPECTED.keySet().stream().sorted().toList();

  /** 本番と同じく、パッケージのサブプログラムを1つのインスタンスで続けて解析する */
  private static final FunctionTableUsageAnalyzer BILLING_ANALYZER =
      FunctionTableUsageAnalyzer.of(BILLING_TABLES, Dbms.ORACLE);

  static List<String> billingSubprograms() {
    return BILLING_KEYS;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("billingSubprograms")
  @DisplayName("analyze: 12個のサブプログラムを持つパッケージから、サブプログラムごとに利用しているテーブルを抽出する")
  void testBillingPackage(String key) {
    assertEquals(BILLING_EXPECTED.get(key), analyzeBilling(BILLING_ANALYZER, key, BILLING_PKG));
  }

  @Test
  @DisplayName("analyze: 数十行の締め処理のプロシージャから、コメント・文字列の中を除いて利用しているテーブルを抽出する")
  void testMonthlyClosing() {
    assertEquals(MONTHLY_CLOSING_EXPECTED, analyzeMonthlyClosing(MONTHLY_CLOSING));
  }

  @TestFactory
  @DisplayName("analyze: 長い定義全体（PostgreSQLは見出しと本体の両方）を変形しても、結果が変わらない")
  Stream<DynamicTest> testTransformedDefinitions() {
    return Stream.concat(
        transformedDefinitions(
            Dbms.POSTGRESQL,
            MONTHLY_CLOSING,
            List.of("sales.orders_tmp"),
            definition ->
                assertEquals(MONTHLY_CLOSING_EXPECTED, analyzeMonthlyClosing(definition))),
        transformedDefinitions(
            Dbms.ORACLE,
            BILLING_PKG,
            List.of("legacy_invoices"),
            definition -> {
              // 変形した定義ごとに、サブプログラムを1つのインスタンスで続けて解析する
              final FunctionTableUsageAnalyzer analyzer =
                  FunctionTableUsageAnalyzer.of(BILLING_TABLES, Dbms.ORACLE);
              for (final String key : BILLING_KEYS) {
                assertEquals(
                    BILLING_EXPECTED.get(key), analyzeBilling(analyzer, key, definition), key);
              }
            }));
  }

  /**
   * 定義全体を変形ごとに変形して、確かめる処理を実行するテスト
   *
   * @param names 変形がコメント・文字列の中にDMLを書く対象のテーブル（定義の結果に現れないもの）
   */
  private static Stream<DynamicTest> transformedDefinitions(
      Dbms dbms, String definition, List<String> names, Consumer<String> check) {
    return SqlTransforms.forDefinition(dbms).stream()
        .map(
            transform ->
                dynamicTest(
                    dbms + ": " + transform.name(),
                    () ->
                        check.accept(
                            SqlTransforms.applyToDefinition(transform, definition, dbms, names))));
  }

  private static String analyzeMonthlyClosing(String definition) {
    return FunctionTableUsageAnalyzerTest.describe(
        FunctionTableUsageAnalyzer.of(SALES_TABLES, Dbms.POSTGRESQL)
            .analyze(
                function(
                    "sales", "monthly_closing", "IN p_target_month date", "plpgsql", definition)));
  }

  private static String analyzeBilling(
      FunctionTableUsageAnalyzer analyzer, String key, String definition) {
    final int separator = key.indexOf('|');
    return FunctionTableUsageAnalyzerTest.describe(
        analyzer.analyze(
            function(
                "BILLING",
                "BILLING_PKG." + key.substring(0, separator),
                key.substring(separator + 1),
                "PL/SQL",
                definition)));
  }

  private static Tables tables(String... qualifiedNames) {
    return Tables.of(
        Arrays.stream(qualifiedNames)
            .map(
                qualifiedName -> {
                  final int dot = qualifiedName.indexOf('.');
                  return table(qualifiedName.substring(0, dot), qualifiedName.substring(dot + 1));
                })
            .toList());
  }

  private static String resource(String name) {
    try (InputStream in =
        RealisticFunctionBodiesTest.class.getResourceAsStream("/tableusage/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
