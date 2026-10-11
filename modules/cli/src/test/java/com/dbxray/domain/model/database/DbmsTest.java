package com.dbxray.domain.model.database;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dbms の表示名との相互変換のテスト */
class DbmsTest {

  @Test
  @DisplayName("fromDisplayName: mapperのSQLが返す表示名から種別を求め、表示名に戻すと元に戻る")
  void testFromDisplayName() {
    assertEquals(Dbms.POSTGRESQL, Dbms.fromDisplayName("PostgreSQL"));
    assertEquals(Dbms.ORACLE, Dbms.fromDisplayName("Oracle"));
    for (final Dbms dbms : Dbms.values()) {
      assertEquals(dbms, Dbms.fromDisplayName(dbms.displayName()));
    }
  }

  @Test
  @DisplayName("fromDisplayName: 対応していない表示名は例外にする（mapperのSQLとの食い違いに気付けるようにする）")
  void testFromDisplayNameRejectsUnknownName() {
    assertThrows(IllegalArgumentException.class, () -> Dbms.fromDisplayName("postgresql"));
  }

  @Test
  @DisplayName("BaseInfoEntity.of: 基本情報のDBMSの欄には種別の表示名を出す")
  void testBaseInfoUsesDisplayName() {
    final BaseInfoEntity baseInfo =
        BaseInfoEntity.of(new DatabaseEntity("testdb", Dbms.ORACLE, 23), LocalDate.EPOCH);

    assertEquals("Oracle", baseInfo.dbmsName());
  }
}
