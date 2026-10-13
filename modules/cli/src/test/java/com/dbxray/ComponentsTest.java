package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.infrastructure.db.ConnectionSettings;
import com.dbxray.infrastructure.db.MyBatisSqlSessionFactories;
import com.dbxray.infrastructure.db.type.DatabaseType;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Components の依存関係の組み立てに関するテスト<br>
 * エントリーポイントと同じ手順（DBへ接続する前に検証用の部品を取得し、DB種別が決まった後にコントローラーを組み立てる）で
 * 組み立てられることを確認する（DBへの接続は行わない）
 */
public class ComponentsTest {

  /** 生成するだけでDBへは接続しないSqlSessionFactory（接続先は使われない） */
  private static final SqlSessionFactory SQL_SESSION_FACTORY =
      MyBatisSqlSessionFactories.create(
          ConnectionSettings.of(
              Map.of(
                  "driver", "org.postgresql.Driver", "url", "jdbc:postgresql://localhost/unused")));

  @Test
  @DisplayName("outputDirectoryValidator: DB種別が決まる前（DBへの接続前）に取得できる")
  void testProvidesOutputDirectoryValidatorBeforeConnecting() {
    assertNotNull(Components.create().outputDirectoryValidator());
  }

  @ParameterizedTest
  @EnumSource(DatabaseType.class)
  @DisplayName("controller: すべてのDB種別で、コントローラーまでの依存関係を組み立てられる")
  void testBuildsControllerForEveryDatabaseType(DatabaseType databaseType) {
    assertNotNull(Components.create().controller(databaseType, SQL_SESSION_FACTORY));
  }
}
