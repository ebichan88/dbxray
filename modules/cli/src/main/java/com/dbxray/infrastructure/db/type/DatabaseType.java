package com.dbxray.infrastructure.db.type;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.repository.CatalogRepository;
import com.dbxray.infrastructure.db.repository.OracleCatalogRepository;
import com.dbxray.infrastructure.db.repository.PostgresCatalogRepository;
import java.util.Arrays;
import java.util.Objects;

/** Databaseの種別をもつ列挙型クラス */
public enum DatabaseType {
  /** PostgreSQL */
  POSTGRESQL("postgresql", Dbms.POSTGRESQL, PostgresCatalogRepository.class),
  /** Oracle */
  ORACLE("oracle", Dbms.ORACLE, OracleCatalogRepository.class);

  private final String name;
  private final Dbms dbms;
  private final Class<? extends CatalogRepository> repositoryClass;

  DatabaseType(String name, Dbms dbms, Class<? extends CatalogRepository> repositoryClass) {
    this.name = name;
    this.dbms = dbms;
    this.repositoryClass = repositoryClass;
  }

  /**
   * Database名を返却する。
   *
   * @return Database名を返却する。
   */
  public String getName() {
    return name;
  }

  /**
   * Databaseに紐づくドメインのDBMS種別を返却する。
   *
   * @return DBMS種別を返却する。
   */
  public Dbms getDbms() {
    return dbms;
  }

  /**
   * Databaseに紐づくリポジトリクラスを返却する。
   *
   * @return リポジトリクラスを返却する。
   */
  public Class<? extends CatalogRepository> getRepositoryClass() {
    return repositoryClass;
  }

  /**
   * Database名に紐づくEnumを返却する。
   *
   * @return DatabaseTypeを返却する。
   * @throws IllegalArgumentException 対象のEnumが存在しない場合にthrowする。
   */
  public static DatabaseType findByName(String name) {
    return Arrays.stream(DatabaseType.values())
        .filter(e -> Objects.equals(name, e.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported database type: " + name));
  }
}
