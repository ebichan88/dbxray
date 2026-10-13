package com.dbxray.infrastructure.db.type;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DatabaseType の逆引き・リポジトリクラス解決に関するテスト */
public class DatabaseTypeTest {

  @Test
  @DisplayName("fromId: 'postgresql' はPOSTGRESQLに解決される")
  void testFindByNamePostgresql() {
    assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromId("postgresql"));
  }

  @Test
  @DisplayName("fromId: 'oracle' はORACLEに解決される")
  void testFindByNameOracle() {
    assertEquals(DatabaseType.ORACLE, DatabaseType.fromId("oracle"));
  }

  @Test
  @DisplayName("fromId: 未知の値の場合はIllegalArgumentExceptionをスローする")
  void testFindByNameUnknownThrows() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> DatabaseType.fromId("mysql"));
    assertTrue(e.getMessage().contains("mysql"));
  }

  @Test
  @DisplayName("getName: 各Enum値に対応するDB名を返す")
  void testGetName() {
    assertEquals("postgresql", DatabaseType.POSTGRESQL.id());
    assertEquals("oracle", DatabaseType.ORACLE.id());
  }

  @Test
  @DisplayName("dbms: 各Enum値に対応するドメインのDBMS種別を返す")
  void testGetDbms() {
    assertEquals(Dbms.POSTGRESQL, DatabaseType.POSTGRESQL.dbms());
    assertEquals(Dbms.ORACLE, DatabaseType.ORACLE.dbms());
  }

  @Test
  @DisplayName("dbms: すべてのDbmsが、ちょうど1つのDatabaseTypeに対応する")
  void testEveryDbmsHasExactlyOneDatabaseType() {
    final List<Dbms> mapped = Arrays.stream(DatabaseType.values()).map(DatabaseType::dbms).toList();

    for (final Dbms dbms : Dbms.values()) {
      assertEquals(1, mapped.stream().filter(dbms::equals).count(), dbms.name());
    }
  }
}
