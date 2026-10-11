package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbxray.domain.model.database.Dbms;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.TestFactory;

/** 改行・空白・コメント・大文字小文字の違いや、コメント・文字列の中のDMLで抽出の結果が変わらないことを、全ケースを変形して確かめるテスト */
class TableUsageMetamorphicTest {

  @TestFactory
  @DisplayName("TableReferenceScanner: TableReferenceScannerTestの全ケースを変形しても、結果が変わらない")
  Stream<DynamicContainer> testScannerCases() {
    return TableReferenceScannerTest.allCases()
        .map(
            scanCase ->
                dynamicContainer(
                    scanCase.toString(),
                    SqlTransforms.forDbms(scanCase.dbms()).stream()
                        .map(
                            transform ->
                                dynamicTest(
                                    transform.name(),
                                    () -> {
                                      final String transformed =
                                          transform.apply(
                                              scanCase.sql(),
                                              scanCase.dbms(),
                                              scanCase.expectedNames());
                                      if (transform.preservesTokens()) {
                                        SqlTransforms.assertSameTokens(
                                            scanCase.sql(), transformed, scanCase.dbms());
                                      }
                                      assertEquals(
                                          scanCase.expected(),
                                          ScanCase.describe(
                                              ScanCase.scan(transformed, scanCase.dbms())),
                                          transformed);
                                    }))));
  }

  @TestFactory
  @DisplayName(
      "FunctionTableUsageAnalyzer: FunctionTableUsageAnalyzerTestの全ケースを変形しても、結果が変わらない"
          + "（Oracleは定義全体を変形するため、前後に文を足す変形は除く）")
  Stream<DynamicContainer> testAnalyzerCases() {
    return FunctionTableUsageAnalyzerTest.allCases()
        .map(
            analyzerCase ->
                dynamicContainer(
                    analyzerCase.toString(),
                    SqlTransforms.forDbms(analyzerCase.dbms()).stream()
                        .filter(
                            transform ->
                                analyzerCase.dbms() == Dbms.POSTGRESQL
                                    || !transform.addsStatements())
                        .map(
                            transform ->
                                dynamicTest(
                                    transform.name(),
                                    () -> {
                                      final String transformed =
                                          transform.apply(
                                              analyzerCase.body(),
                                              analyzerCase.dbms(),
                                              analyzerCase.expectedNames());
                                      assertEquals(
                                          analyzerCase.expected(),
                                          FunctionTableUsageAnalyzerTest.describe(
                                              analyzerCase.analyze(transformed)),
                                          transformed);
                                    }))));
  }
}
