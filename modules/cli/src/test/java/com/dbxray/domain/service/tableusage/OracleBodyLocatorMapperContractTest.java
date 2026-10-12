package com.dbxray.domain.service.tableusage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OracleBodyLocator が前提とするパッケージの定義の組み立てが、Oracleのcatalogマッパーと一致していることのテスト<br>
 * 仕様部とパッケージ本体のつなぎ目（空行と{@code CREATE OR REPLACE }）は、マッパーの組み立てと文字列で一致させているため、
 * マッパー側を変えたときにここで気付く（ロケーター側は {@code FunctionBodyLocatorTest} が同じつなぎ目で確かめる）
 */
class OracleBodyLocatorMapperContractTest {

  private static String oracleMapper() throws IOException {
    try (InputStream in =
        OracleBodyLocatorMapperContractTest.class.getResourceAsStream(
            "/mapper/oracle/catalogMapper.xml")) {
      assertNotNull(in, "Oracleのcatalogマッパーが見つからない");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("パッケージの定義は、仕様部とパッケージ本体を空行（CHR(10) || CHR(10)）でつなぐ")
  void testSpecificationAndBodyAreJoinedByBlankLine() throws IOException {
    assertTrue(
        oracleMapper()
            .contains(
                "source_text(schema_name, object_name, 'PACKAGE') || CHR(10) || CHR(10) ||"
                    + " source_text(schema_name, object_name, 'PACKAGE BODY')"));
  }

  @Test
  @DisplayName("source_text: 格納されない作成時の「CREATE OR REPLACE 」を、定義の先頭に補う")
  void testCreateOrReplaceIsPrefixed() throws IOException {
    assertTrue(
        oracleMapper()
            .contains(
                "DBMS_LOB.WRITEAPPEND(result, LENGTH('CREATE OR REPLACE '), 'CREATE OR REPLACE ')"));
  }
}
