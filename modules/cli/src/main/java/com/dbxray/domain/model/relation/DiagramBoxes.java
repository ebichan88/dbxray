package com.dbxray.domain.model.relation;

import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.Tables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ER図のテーブルの箱に表示する内容（論理テーブル名・関連カラム）を引くクラス<br>
 * 関連カラムは、関連の参照元・参照先として使われるカラム。カラムはテーブル数に比例して重くなるため、取得したカラムのうち関連カラムだけを保持する（{@link Builder}）
 */
public final class DiagramBoxes {

  private final Tables tables;

  /** テーブルキーごとの関連カラム（カラムの定義順） */
  private final Map<TableKey, List<ColumnEntity>> relationColumns;

  private DiagramBoxes(Tables tables, Map<TableKey, List<ColumnEntity>> relationColumns) {
    this.tables = tables;
    this.relationColumns = Map.copyOf(relationColumns);
  }

  /**
   * 関連カラムを集めて組み立てるビルダーを生成するメソッド
   *
   * @param tables 出力対象のテーブル（論理テーブル名を引く）
   * @param foreignKeys 出力対象のテーブル同士の関連（関連カラムを決める）
   */
  public static Builder builder(Tables tables, ForeignKeys foreignKeys) {
    return new Builder(tables, foreignKeys);
  }

  /**
   * 論理テーブル名を取得するメソッド
   *
   * @return 出力対象に無いテーブル・論理テーブル名が無いテーブルは空文字
   */
  public String logicalTableName(TableKey table) {
    return tables
        .find(table)
        .map(TableEntity::logicalTableName)
        .filter(name -> name != null && !name.isBlank())
        .orElse("");
  }

  /**
   * 1枚の図のテーブルの箱に表示する関連カラムを、テーブルごとに求めるメソッド
   *
   * @param drawnRelations 図に描画する関連。これらの関連で使われるカラムだけを返す
   * @return 関連の参照元・参照先のテーブルごとのカラム（カラムの定義順）。実在しないカラム（論理リレーションで宣言したカラムの誤り等）は含まない。
   *     表示するカラムが無いテーブルは含まない
   */
  public Map<TableKey, List<DiagramColumn>> relationColumnsOf(
      Collection<ForeignKeyEntity> drawnRelations) {
    // テーブルごとに関連を走査し直すと、関連数×テーブル数の走査となり大きな図で処理時間が膨らむため、1回の走査で振り分ける
    final Map<TableKey, Set<String>> usedNames = new HashMap<>();
    final Map<TableKey, Set<String>> foreignKeyNames = new HashMap<>();
    drawnRelations.forEach(
        fk -> {
          usedNames.computeIfAbsent(fk.tableKey(), key -> new HashSet<>()).addAll(fk.columnNames());
          foreignKeyNames
              .computeIfAbsent(fk.tableKey(), key -> new HashSet<>())
              .addAll(fk.columnNames());
          usedNames
              .computeIfAbsent(fk.referenceTableKey(), key -> new HashSet<>())
              .addAll(fk.referenceColumnNames());
        });
    final Map<TableKey, List<DiagramColumn>> result = new HashMap<>();
    usedNames.forEach(
        (table, names) -> {
          final List<ColumnEntity> used =
              relationColumns.getOrDefault(table, List.of()).stream()
                  .filter(column -> names.contains(column.physicalColumnName()))
                  .toList();
          if (!used.isEmpty()) {
            result.put(
                table, DiagramColumn.of(used, foreignKeyNames.getOrDefault(table, Set.of())));
          }
        });
    return result;
  }

  /** 取得したカラムから関連カラムだけを集めて{@link DiagramBoxes}を組み立てるビルダー */
  public static final class Builder {

    private final Tables tables;

    /** テーブルキーごとの関連カラムの名前（関連の出現順） */
    private final Map<TableKey, Set<String>> columnNamesByTable = new LinkedHashMap<>();

    private final Map<TableKey, List<ColumnEntity>> collected = new HashMap<>();

    private Builder(Tables tables, ForeignKeys foreignKeys) {
      this.tables = tables;
      foreignKeys.stream()
          .forEach(
              fk -> {
                columnNamesByTable
                    .computeIfAbsent(fk.tableKey(), key -> new LinkedHashSet<>())
                    .addAll(fk.columnNames());
                columnNamesByTable
                    .computeIfAbsent(fk.referenceTableKey(), key -> new LinkedHashSet<>())
                    .addAll(fk.referenceColumnNames());
              });
    }

    /**
     * カラムを取得する必要のあるテーブルを取得するメソッド
     *
     * @return 関連の参照元・参照先のテーブル（関連の出現順・重複なし）
     */
    public List<TableKey> tablesNeedingColumns() {
      return List.copyOf(columnNamesByTable.keySet());
    }

    /**
     * 取得したカラムのうち、関連カラムだけを保持するメソッド
     *
     * @param columns 取得したカラム（テーブルごとに定義順）。関連カラム以外は参照を保持しない
     */
    public Builder collectRelatedColumns(List<ColumnEntity> columns) {
      columns.stream()
          .filter(
              column ->
                  columnNamesByTable
                      .getOrDefault(column.tableKey(), Set.of())
                      .contains(column.physicalColumnName()))
          .forEach(
              column ->
                  collected
                      .computeIfAbsent(column.tableKey(), key -> new ArrayList<>())
                      .add(column));
      return this;
    }

    /** 集めた関連カラムから{@link DiagramBoxes}を組み立てるメソッド */
    public DiagramBoxes build() {
      final Map<TableKey, List<ColumnEntity>> copied = new HashMap<>();
      collected.forEach((key, columns) -> copied.put(key, List.copyOf(columns)));
      return new DiagramBoxes(tables, copied);
    }
  }
}
