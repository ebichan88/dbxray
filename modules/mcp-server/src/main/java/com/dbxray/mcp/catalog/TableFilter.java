package com.dbxray.mcp.catalog;

/**
 * テーブルの一覧・検索の対象を絞り込む条件<br>
 * DB・スキーマ・区分・観点・ER上の区分の条件をすべて満たすテーブルを対象とする。呼び出しの前に1回組み立てて使い回す
 */
public final class TableFilter {

  /** 絞り込まない条件 */
  public static final TableFilter ALL = new TableFilter(SearchScope.ALL, null, null, null, null);

  private final SearchScope scope;

  /** nullの場合は区分で絞り込まない */
  private final TableType type;

  /** nullの場合は観点で絞り込まない */
  private final ViewpointEntry viewpoint;

  /** nullの場合はER上の区分で絞り込まない */
  private final ErRole erRole;

  /** {@link #erRole}の判定に使う。{@link #erRole}がnullの場合はnull */
  private final JunctionTables junctions;

  private TableFilter(
      SearchScope scope,
      TableType type,
      ViewpointEntry viewpoint,
      ErRole erRole,
      JunctionTables junctions) {
    this.scope = scope;
    this.type = type;
    this.viewpoint = viewpoint;
    this.erRole = erRole;
    this.junctions = junctions;
  }

  /** DB・スキーマだけで絞り込む条件を返すメソッド */
  public static TableFilter of(SearchScope scope) {
    return new TableFilter(scope, null, null, null, null);
  }

  /** 区分の条件を加えた条件を返すメソッド */
  public TableFilter withType(TableType type) {
    return new TableFilter(scope, type, viewpoint, erRole, junctions);
  }

  /** 観点の所属テーブルだけに絞り込む条件を加えた条件を返すメソッド */
  public TableFilter withViewpoint(ViewpointEntry viewpoint) {
    return new TableFilter(scope, type, viewpoint, erRole, junctions);
  }

  /**
   * ER上の区分の条件を加えた条件を返すメソッド
   *
   * @param junctions テーブルのER上の区分を判定する窓口
   */
  public TableFilter withErRole(ErRole erRole, JunctionTables junctions) {
    return new TableFilter(scope, type, viewpoint, erRole, junctions);
  }

  /** テーブルが条件に当てはまるか判定するメソッド */
  boolean matches(TableEntry table) {
    return scope.matches(table.key())
        && (type == null || type.value().equals(table.type()))
        && (viewpoint == null || viewpoint.contains(table.key()))
        && (erRole == null || junctions.roleOf(table).filter(erRole::equals).isPresent());
  }
}
