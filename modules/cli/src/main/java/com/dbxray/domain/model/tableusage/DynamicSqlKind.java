package com.dbxray.domain.model.tableusage;

/** 定義本体に含まれる動的SQLの種類（実行時に組み立てたSQLの中の参照は、定義本体からは読み取れない） */
public enum DynamicSqlKind {
  /** PostgreSQLの{@code EXECUTE}（{@code RETURN QUERY EXECUTE}・{@code OPEN … FOR EXECUTE}を含む） */
  EXECUTE("EXECUTE", "EXECUTE"),
  /** Oracleの{@code EXECUTE IMMEDIATE} */
  EXECUTE_IMMEDIATE("EXECUTE IMMEDIATE", "EXECUTE IMMEDIATE"),
  /** Oracleの{@code DBMS_SQL}パッケージ */
  DBMS_SQL("DBMS_SQL", "DBMS_SQL"),
  /** Oracleの{@code OPEN カーソル FOR 文字列}（文字列の変数を含む） */
  OPEN_FOR("OPEN FOR", "OPEN FOR"),
  /** PostgreSQLの{@code dblink}・{@code dblink_exec}（別のDBで実行するSQLを文字列で渡す） */
  DBLINK("dblink", "dblink");

  private final String label;
  private final String id;

  DynamicSqlKind(String label, String id) {
    this.label = label;
    this.id = id;
  }

  /**
   * 定義書に示す名前を取得するメソッド
   *
   * @return 定義本体での書き方（例: {@code EXECUTE IMMEDIATE}）
   */
  public String label() {
    return label;
  }

  /**
   * 参考情報（JSON）で種類を示す名前を取得するメソッド<br>
   * MCPサーバーが読む値のため、定義書の表記（{@link #label()}）を変えても変えない（変えると形式の互換が壊れる）
   *
   * @return 現在は{@link #label()}と同じ値
   */
  public String id() {
    return id;
  }
}
