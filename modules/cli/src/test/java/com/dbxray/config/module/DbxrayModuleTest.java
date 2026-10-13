package com.dbxray.config.module;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.repository.CatalogRepository;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.infrastructure.db.ConnectionSettings;
import com.dbxray.infrastructure.db.MyBatisSqlSessionFactories;
import com.dbxray.infrastructure.db.repository.OracleCatalogRepository;
import com.dbxray.infrastructure.db.repository.PostgresCatalogRepository;
import com.dbxray.infrastructure.db.type.DatabaseType;
import com.dbxray.presentation.ExportSchemaController;
import com.google.inject.ConfigurationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DbxrayModule・DatabaseDependentModule の束縛定義に関するテスト<br>
 * エントリーポイントと同じ手順（DB種別に依存しない部品のコンテナを組み立て、DB種別が決まった後に子のコンテナを足す）で
 * 実際のDIコンテナを組み立て、コントローラーまでの依存関係が解決できることを確認する。
 * 束縛の追加漏れや、{@code @Inject}の付け忘れを実行前に検知するため（DBへの接続は行わない）
 */
public class DbxrayModuleTest {

  @Test
  @DisplayName("DB種別に依存しない部品は、DB種別が決まる前（DBへの接続前）のコンテナから取得できる")
  void testResolvesDatabaseIndependentBindingsBeforeConnecting() {
    final Injector injector = Guice.createInjector(new DbxrayModule());

    assertNotNull(injector.getInstance(FileRepository.class));
    assertNotNull(injector.getInstance(OutputPathResolver.class));
    // DB種別に依存するものは、子のコンテナを足すまで取得できない
    assertThrows(ConfigurationException.class, () -> injector.getInstance(CatalogRepository.class));
  }

  /** 生成するだけでDBへは接続しないSqlSessionFactory（接続先は使われない） */
  private static final SqlSessionFactory SQL_SESSION_FACTORY =
      MyBatisSqlSessionFactories.create(
          ConnectionSettings.of(
              Map.of(
                  "driver", "org.postgresql.Driver", "url", "jdbc:postgresql://localhost/unused")));

  @Test
  @DisplayName("PostgreSQL接続時: コントローラーまでの依存関係を解決でき、PostgreSQL用のリポジトリが束縛される")
  void testResolvesControllerForPostgresql() {
    final Injector injector = createInjector(DatabaseType.POSTGRESQL);

    assertNotNull(injector.getInstance(ExportSchemaController.class));
    assertInstanceOf(
        PostgresCatalogRepository.class, injector.getInstance(CatalogRepository.class));
  }

  @Test
  @DisplayName("SqlSessionFactoryは、エントリーポイントで生成した1つのインスタンスが使い回される")
  void testSharesSingleSqlSessionFactory() {
    final Injector injector = createInjector(DatabaseType.POSTGRESQL);

    assertSame(SQL_SESSION_FACTORY, injector.getInstance(SqlSessionFactory.class));
    assertSame(
        injector.getInstance(SqlSessionFactory.class),
        injector.getInstance(SqlSessionFactory.class));
  }

  @Test
  @DisplayName("Oracle接続時: コントローラーまでの依存関係を解決でき、Oracle用のリポジトリが束縛される")
  void testResolvesControllerForOracle() {
    final Injector injector = createInjector(DatabaseType.ORACLE);

    assertNotNull(injector.getInstance(ExportSchemaController.class));
    assertInstanceOf(OracleCatalogRepository.class, injector.getInstance(CatalogRepository.class));
  }

  /**
   * エントリーポイントと同じ手順でDIコンテナを組み立てる
   *
   * @param databaseType 接続先DBの種別
   * @return DB種別に依存する部品を束縛した子のコンテナ
   */
  private static Injector createInjector(DatabaseType databaseType) {
    return Guice.createInjector(new DbxrayModule())
        .createChildInjector(new DatabaseDependentModule(databaseType, SQL_SESSION_FACTORY));
  }
}
