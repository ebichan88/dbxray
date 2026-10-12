package com.dbxray.config.module;

import com.dbxray.domain.repository.CatalogRepository;
import com.dbxray.infrastructure.db.type.DatabaseType;
import com.google.inject.AbstractModule;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * DB種別が決まってから束縛する依存関係のモジュール<br>
 * DBへ接続して接続先のDB種別を判定した後、{@link DbxrayModule}で組み立てたDIコンテナの子として組み立てる。
 * 接続先の{@link SqlSessionFactory}と、DB種別で実装が変わる{@link CatalogRepository}を束縛する
 * （親のコンテナでは、接続先・DB種別が未定のためこれらを解決できない）。
 * これらに依存するユースケースは具象クラスのため束縛せず、このコンテナのジャストインタイム束縛で生成する
 */
public class DatabaseDependentModule extends AbstractModule {

  private final DatabaseType databaseType;
  private final SqlSessionFactory sqlSessionFactory;

  /**
   * 束縛の定義（{@link #configure()}）の中でDBへ接続しないよう、接続先DBの種別は呼び出し元で判定して受け取る。<br>
   * DIコンテナの中（束縛の定義やProvider）で接続すると、接続の失敗がGuiceの例外（CreationException・ProvisionException）に包まれて届き、
   * エントリーポイントが「DBに接続できない」を利用者が直せる誤りとして報告できなくなるため
   *
   * @param databaseType 接続先DBの種別（{@link CatalogRepository}の実装クラスの選択に用いる）
   * @param sqlSessionFactory 接続先DBのSqlSessionFactory（エントリーポイントで1回だけ生成したものを、リポジトリで使い回す）
   */
  public DatabaseDependentModule(DatabaseType databaseType, SqlSessionFactory sqlSessionFactory) {
    this.databaseType = databaseType;
    this.sqlSessionFactory = sqlSessionFactory;
  }

  /** {@inheritDoc} */
  @Override
  protected void configure() {
    bind(SqlSessionFactory.class).toInstance(sqlSessionFactory);
    bind(CatalogRepository.class).to(databaseType.getRepositoryClass());
  }
}
