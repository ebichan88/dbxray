package com.dbxray.domain.model.tableusage;

/** 定義本体に含まれる動的SQLの種類（実行時に組み立てたSQLの中の参照は、定義本体からは読み取れない） */
public enum DynamicSqlKind {
  /** PostgreSQLの{@code EXECUTE}（{@code RETURN QUERY EXECUTE}・{@code OPEN … FOR EXECUTE}を含む） */
  EXECUTE("EXECUTE"),
  /** Oracleの{@code EXECUTE IMMEDIATE} */
  EXECUTE_IMMEDIATE("EXECUTE IMMEDIATE"),
  /** Oracleの{@code DBMS_SQL}パッケージ */
  DBMS_SQL("DBMS_SQL"),
  /** Oracleの{@code OPEN カーソル FOR 文字列}（文字列の変数を含む） */
  OPEN_FOR("OPEN FOR"),
  /** PostgreSQLの{@code dblink}・{@code dblink_exec}（別のDBで実行するSQLを文字列で渡す） */
  DBLINK("dblink");

  private final String label;

  DynamicSqlKind(String label) {
    this.label = label;
  }

  /**
   * 定義書・参考情報に示す名前を取得するメソッド
   *
   * @return 定義本体での書き方（例: {@code EXECUTE IMMEDIATE}）
   */
  public String label() {
    return label;
  }
}
