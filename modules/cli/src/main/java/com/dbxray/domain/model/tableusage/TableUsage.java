package com.dbxray.domain.model.tableusage;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 関数・プロシージャが利用しているテーブル1件
 *
 * @param tables 一致した出力対象のテーブル。スキーマが決まった場合は1件、決まらない場合は同じ名前の出力対象のテーブル（候補）のすべて。空にはしない
 * @param resolution スキーマの決まり方（決まらない場合は、何で決まるか）
 * @param operations 操作。空にはしない
 */
public record TableUsage(
    List<TableEntity> tables, SchemaResolution resolution, Set<CrudOperation> operations) {

  /**
   * 一致したテーブル・操作は変更不可な複製として保持し、操作はC・R・U・Dの順に並べる
   *
   * @throws IllegalArgumentException テーブル・操作が空の場合、スキーマが決まったのにテーブルが1件でない場合、テーブルの物理テーブル名が揃っていない場合
   */
  public TableUsage {
    if (tables.isEmpty() || operations.isEmpty()) {
      throw new IllegalArgumentException("tables and operations must not be empty");
    }
    tables = List.copyOf(tables);
    operations = Collections.unmodifiableSet(EnumSet.copyOf(operations));
    if (resolution == SchemaResolution.DETERMINED && tables.size() != 1) {
      throw new IllegalArgumentException("A determined usage must have exactly one table");
    }
    if (tables.stream().map(TableEntity::physicalTableName).distinct().count() != 1) {
      throw new IllegalArgumentException("All tables must have the same physical table name");
    }
  }

  /**
   * スキーマが決まったか判定するメソッド
   *
   * @return スキーマ修飾・関数の{@code SET search_path}・Oracleの所有者から決まった場合はtrue
   */
  public boolean schemaDetermined() {
    return resolution == SchemaResolution.DETERMINED;
  }

  /**
   * 物理テーブル名を取得するメソッド（スキーマが決まらない場合も、候補はすべて同じ名前）
   *
   * @return 物理テーブル名
   */
  public String tableName() {
    return tables.get(0).physicalTableName();
  }

  /**
   * スキーマ名を取得するメソッド
   *
   * @return スキーマ名。決まらない場合は空文字
   */
  public String schemaName() {
    return schemaDetermined() ? tables.get(0).schemaName() : "";
  }

  /**
   * スキーマが決まらない名前の候補のスキーマを取得するメソッド
   *
   * @return 同じ名前の出力対象のテーブルがあるスキーマ。スキーマが決まった場合は空
   */
  public List<String> schemaCandidates() {
    return schemaDetermined() ? List.of() : tables.stream().map(TableEntity::schemaName).toList();
  }

  /**
   * テーブルの区分を取得するメソッド
   *
   * @return 区分。スキーマが決まらず、候補の区分がばらつく場合は空
   */
  public Optional<TableType> tableType() {
    final List<TableType> types = tables.stream().map(TableEntity::tableType).distinct().toList();
    return types.size() == 1 ? Optional.of(types.get(0)) : Optional.empty();
  }

  /**
   * 操作を含むか判定するメソッド
   *
   * @return 含む場合はtrue
   */
  public boolean has(CrudOperation operation) {
    return operations.contains(operation);
  }

  /**
   * 操作をC・R・U・Dの順に並べたリストを取得するメソッド
   *
   * @return 操作のリスト
   */
  public List<CrudOperation> orderedOperations() {
    return List.copyOf(operations);
  }
}
