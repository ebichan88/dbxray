package com.dbxray.testsupport;

import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.TriggerEntity;
import java.util.List;

/**
 * テストで用いるテーブル・カラム・インデックス・制約・トリガー・関数の生成ヘルパー<br>
 * 本番コードではこれらをSQLから受け取るため、表示用の属性をすべて指定するコンストラクタのみを持つ。
 * 大半のテストはテーブルへの紐付け（スキーマ名・テーブル名）や一部の属性しか参照しないため、
 * ここで残りの属性に既定値を与え、テスト側の関心だけを記述できるようにする
 */
public final class EntityFixtures {

  private EntityFixtures() {}

  /**
   * 論理カラム名・桁数/精度・NOT NULL・デフォルト値を持たないカラムを生成する
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param physicalColumnName 物理カラム名
   * @param columnType データ型
   * @param primaryKey 主キーを構成するカラムか
   * @return カラム
   */
  public static ColumnEntity column(
      String schema,
      String table,
      String physicalColumnName,
      String columnType,
      boolean primaryKey) {
    return new ColumnEntity(
        schema, table, "", physicalColumnName, columnType, "", primaryKey, false, "");
  }

  /**
   * スキーマ名・テーブル名のみを持つインデックスを生成する
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @return インデックス
   */
  public static IndexEntity index(String schema, String table) {
    return new IndexEntity(schema, table, "", "", false, false, "", "");
  }

  /**
   * スキーマ名・テーブル名のみを持つ制約を生成する
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @return 制約
   */
  public static ConstraintEntity constraint(String schema, String table) {
    return new ConstraintEntity(schema, table, "", "", "", "");
  }

  /**
   * スキーマ名・テーブル名のみを持つトリガーを生成する
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @return トリガー
   */
  public static TriggerEntity trigger(String schema, String table) {
    return new TriggerEntity(schema, table, "", "", List.of(), "", "", "", "");
  }

  /**
   * コメントを持たないテーブルを生成する
   *
   * @param schema スキーマ名
   * @param name テーブル名
   * @param tableType 種別
   * @return テーブル
   */
  public static TableEntity table(String schema, String name, TableType tableType) {
    return new TableEntity("db", schema, "", name, tableType, "", "");
  }

  /**
   * 種別がテーブルで、コメントを持たないテーブルを生成する
   *
   * @param schema スキーマ名
   * @param name テーブル名
   * @return テーブル
   */
  public static TableEntity table(String schema, String name) {
    return table(schema, name, TableType.TABLE);
  }

  /**
   * 定義本体と引数以外の属性に既定値を与えた関数・プロシージャを生成する
   *
   * @param schema スキーマ名
   * @param name 関数・プロシージャの名前（Oracleのパッケージのサブプログラムは{@code パッケージ名.名前}）
   * @param overloadCount 同じスキーマ内の同名の関数・プロシージャの数
   * @param arguments カタログの引数の並び
   * @param languageName 言語名
   * @param definition 定義本体
   * @return 関数・プロシージャ
   */
  public static FunctionEntity function(
      String schema,
      String name,
      int overloadCount,
      String arguments,
      String languageName,
      String definition) {
    return new FunctionEntity(
        "db", schema, name, 1, overloadCount, "FUNCTION", arguments, "", languageName, definition);
  }

  /**
   * オーバーロードの無い関数・プロシージャを生成する
   *
   * @param schema スキーマ名
   * @param name 関数・プロシージャの名前
   * @param arguments カタログの引数の並び
   * @param languageName 言語名
   * @param definition 定義本体
   * @return 関数・プロシージャ
   */
  public static FunctionEntity function(
      String schema, String name, String arguments, String languageName, String definition) {
    return function(schema, name, 1, arguments, languageName, definition);
  }
}
