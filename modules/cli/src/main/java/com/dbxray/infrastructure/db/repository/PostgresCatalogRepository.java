package com.dbxray.infrastructure.db.repository;

import com.dbxray.infrastructure.db.type.DatabaseType;
import org.apache.ibatis.session.SqlSessionFactory;

/** [Postgres]DBのカタログの取得に関するリポジトリクラス */
public final class PostgresCatalogRepository extends AbstractCatalogRepository {

  public PostgresCatalogRepository(SqlSessionFactory sqlSessionFactory) {
    super(DatabaseType.POSTGRESQL, sqlSessionFactory);
  }
}
