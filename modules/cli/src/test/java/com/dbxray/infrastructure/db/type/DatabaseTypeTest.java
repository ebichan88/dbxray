package com.dbxray.infrastructure.db.type;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.infrastructure.db.repository.OracleCatalogRepository;
import com.dbxray.infrastructure.db.repository.PostgresCatalogRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DatabaseType の逆引き・リポジトリクラス解決に関するテスト */
public class DatabaseTypeTest {

  @Test
  @DisplayName("findByName: 'postgresql' はPOSTGRESQLに解決される")
  void testFindByNamePostgresql() {
    assertEquals(DatabaseType.POSTGRESQL, DatabaseType.findByName("postgresql"));
  }

  @Test
  @DisplayName("findByName: 'oracle' はORACLEに解決される")
  void testFindByNameOracle() {
    assertEquals(DatabaseType.ORACLE, DatabaseType.findByName("oracle"));
  }

  @Test
  @DisplayName("findByName: 未知の値の場合はIllegalArgumentExceptionをスローする")
  void testFindByNameUnknownThrows() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> DatabaseType.findByName("mysql"));
    assertTrue(e.getMessage().contains("mysql"));
  }

  @Test
  @DisplayName("getRepositoryClass: POSTGRESQLはPostgresCatalogRepositoryを返す")
  void testGetRepositoryClassPostgresql() {
    assertEquals(PostgresCatalogRepository.class, DatabaseType.POSTGRESQL.getRepositoryClass());
  }

  @Test
  @DisplayName("getRepositoryClass: ORACLEはOracleCatalogRepositoryを返す")
  void testGetRepositoryClassOracle() {
    assertEquals(OracleCatalogRepository.class, DatabaseType.ORACLE.getRepositoryClass());
  }

  @Test
  @DisplayName("getName: 各Enum値に対応するDB名を返す")
  void testGetName() {
    assertEquals("postgresql", DatabaseType.POSTGRESQL.getName());
    assertEquals("oracle", DatabaseType.ORACLE.getName());
  }

  @Test
  @DisplayName("getDbms: 各Enum値に対応するドメインのDBMS種別を返す")
  void testGetDbms() {
    assertEquals(Dbms.POSTGRESQL, DatabaseType.POSTGRESQL.getDbms());
    assertEquals(Dbms.ORACLE, DatabaseType.ORACLE.getDbms());
  }

  @Test
  @DisplayName("getDbms: すべてのDbmsが、ちょうど1つのDatabaseTypeに対応する")
  void testEveryDbmsHasExactlyOneDatabaseType() {
    final List<Dbms> mapped =
        Arrays.stream(DatabaseType.values()).map(DatabaseType::getDbms).toList();

    for (final Dbms dbms : Dbms.values()) {
      assertEquals(1, mapped.stream().filter(dbms::equals).count(), dbms.name());
    }
  }
}
