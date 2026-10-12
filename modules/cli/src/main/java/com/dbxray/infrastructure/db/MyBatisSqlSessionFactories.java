package com.dbxray.infrastructure.db;

import java.io.IOException;
import java.io.InputStream;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MyBatisの{@link SqlSessionFactory}を生成するクラス<br>
 * 生成は設定XMLとmapperの解析を伴い重いため、エントリーポイントで1回だけ生成し、DIコンテナを通じて使い回す。
 * 生成するだけでDBへは接続しない（接続はコネクションプールが{@code openSession()}の時に行う）
 */
public final class MyBatisSqlSessionFactories {

  private static final Logger logger = LogManager.getLogger(MyBatisSqlSessionFactories.class);
  private static final String MYBATIS_CONFIG = "mybatis-config.xml";

  private MyBatisSqlSessionFactories() {}

  /**
   * DB接続情報から{@link SqlSessionFactory}を生成するメソッド
   *
   * @param settings 検証済みのDB接続情報
   * @return SqlSessionFactory
   */
  public static SqlSessionFactory create(ConnectionSettings settings) {
    logger.info("Initializing SqlSessionFactory from {}", MYBATIS_CONFIG);
    try (final InputStream inputStream = Resources.getResourceAsStream(MYBATIS_CONFIG)) {
      final SqlSessionFactory sqlSessionFactory =
          new SqlSessionFactoryBuilder().build(inputStream, settings.toProperties());
      logger.info("SqlSessionFactory initialization completed.");
      return sqlSessionFactory;
    } catch (IOException e) {
      throw new IllegalStateException("SqlSessionFactory initialization failed.", e);
    }
  }
}
