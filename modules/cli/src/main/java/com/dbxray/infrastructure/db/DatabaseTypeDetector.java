package com.dbxray.infrastructure.db;

import com.dbxray.infrastructure.db.type.DatabaseType;
import com.dbxray.shared.exception.UserCorrectableException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** DBへ接続し、接続先のDB種別を判定するクラス */
public final class DatabaseTypeDetector {

  private static final Logger logger = LogManager.getLogger(DatabaseTypeDetector.class);

  private DatabaseTypeDetector() {}

  /**
   * 接続先のDB種別を判定するメソッド
   *
   * @param sqlSessionFactory 接続先のSqlSessionFactory
   * @throws UserCorrectableException DBに接続できない場合や、接続先が対応していないDBの場合
   */
  public static DatabaseType detect(SqlSessionFactory sqlSessionFactory) {
    try (final SqlSession session = sqlSessionFactory.openSession()) {
      final DatabaseMetaData metaData = connect(session).getMetaData();
      // DBMS・ドライバの版による取得結果の違いを調べられるよう残す。接続URL・ユーザー名は設定値のため出さない
      logger.info(
          "Connected to the database. [product={} {}, driver={} {}]",
          metaData.getDatabaseProductName(),
          metaData.getDatabaseProductVersion(),
          metaData.getDriverName(),
          metaData.getDriverVersion());
      return toDatabaseType(metaData.getDatabaseProductName());
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to get the database name.", e);
    }
  }

  /**
   * DBへ接続するメソッド<br>
   * 接続できないのは接続情報の誤りかDBが起動していない場合で、
   * いずれも利用者が見直せば解消するため{@link UserCorrectableException}として伝える（接続できなかった理由は原因の例外のメッセージが示す）
   *
   * @throws UserCorrectableException DBに接続できない場合
   */
  private static Connection connect(SqlSession session) {
    try {
      return session.getConnection();
    } catch (PersistenceException e) {
      throw new UserCorrectableException(
          "Could not connect to the database. Check the connection settings "
              + "(database in the configuration file, --db-* arguments and the DBXRAY_DB_PASSWORD "
              + "environment variable) "
              + "and that the database is running.",
          e);
    }
  }

  /**
   * DBの製品名を、対応するDB種別へ変換するメソッド
   *
   * @param productName JDBCドライバが返すDBの製品名
   * @throws UserCorrectableException 対応していないDBの場合（接続先の設定を見直せば解消する）
   */
  static DatabaseType toDatabaseType(String productName) {
    try {
      return DatabaseType.findByName(productName.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new UserCorrectableException(
          e.getMessage() + ". Connect to a supported database (PostgreSQL or Oracle).", e);
    }
  }
}
