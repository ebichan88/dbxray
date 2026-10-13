package com.dbxray.infrastructure.db.repository;

import com.dbxray.infrastructure.db.type.DatabaseType;
import org.apache.ibatis.session.SqlSessionFactory;

/** [oracle]DBのカタログの取得に関するリポジトリクラス */
public final class OracleCatalogRepository extends AbstractCatalogRepository {

  public OracleCatalogRepository(SqlSessionFactory sqlSessionFactory) {
    super(DatabaseType.ORACLE, sqlSessionFactory);
  }
}
