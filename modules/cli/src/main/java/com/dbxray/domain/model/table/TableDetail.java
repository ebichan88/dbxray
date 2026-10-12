package com.dbxray.domain.model.table;

import java.util.List;

/**
 * 1テーブル分の詳細情報（カラム・インデックス・制約）をまとめたrecord<br>
 * テーブル一覧・外部キー等の一括取得する情報と異なり、テーブル数に比例して重くなるため、スキーマ・チャンク単位で取得し、出力後に破棄する
 *
 * @param columns 当該テーブルのカラム情報のリスト
 * @param indexes 当該テーブルのインデックス情報のリスト
 * @param constraints 当該テーブルの制約情報のリスト
 */
public record TableDetail(
    TableEntity table,
    List<ColumnEntity> columns,
    List<IndexEntity> indexes,
    List<ConstraintEntity> constraints) {

  /** 各リストは変更不可な複製として保持する */
  public TableDetail {
    columns = List.copyOf(columns);
    indexes = List.copyOf(indexes);
    constraints = List.copyOf(constraints);
  }

  /**
   * 複数テーブル分の詳細情報を、テーブルごとに組み立てるメソッド<br>
   * DBからは詳細情報の種類ごとに複数テーブル分をまとめて取得するため、それぞれを所属するテーブルへ振り分ける
   *
   * @param tables 詳細情報を組み立てるテーブルのリスト
   * @param columns 対象テーブルのカラム情報のリスト（複数テーブル分）
   * @param indexes 対象テーブルのインデックス情報のリスト（複数テーブル分）
   * @param constraints 対象テーブルの制約情報のリスト（複数テーブル分）
   * @return テーブルごとの詳細情報のリスト（{@code tables}と同じ順）
   */
  public static List<TableDetail> assembleAll(
      List<TableEntity> tables,
      List<ColumnEntity> columns,
      List<IndexEntity> indexes,
      List<ConstraintEntity> constraints) {
    final Columns columnsByTable = Columns.of(columns);
    final Indexes indexesByTable = Indexes.of(indexes);
    final Constraints constraintsByTable = Constraints.of(constraints);
    return tables.stream()
        .map(
            table ->
                new TableDetail(
                    table,
                    columnsByTable.belongingTo(table),
                    indexesByTable.belongingTo(table),
                    constraintsByTable.belongingTo(table)))
        .toList();
  }
}
