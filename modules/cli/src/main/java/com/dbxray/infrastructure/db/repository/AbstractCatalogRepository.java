package com.dbxray.infrastructure.db.repository;

import com.dbxray.domain.model.database.DatabaseEntity;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.repository.CatalogRepository;
import com.dbxray.infrastructure.db.repository.dto.ColumnDto;
import com.dbxray.infrastructure.db.repository.dto.ConstraintDto;
import com.dbxray.infrastructure.db.repository.dto.DatabaseDto;
import com.dbxray.infrastructure.db.repository.dto.ForeignKeyDto;
import com.dbxray.infrastructure.db.repository.dto.FunctionDto;
import com.dbxray.infrastructure.db.repository.dto.IndexDto;
import com.dbxray.infrastructure.db.repository.dto.PartitionDto;
import com.dbxray.infrastructure.db.repository.dto.SequenceDto;
import com.dbxray.infrastructure.db.repository.dto.TableDto;
import com.dbxray.infrastructure.db.repository.dto.TriggerDto;
import com.dbxray.infrastructure.db.repository.dto.TypeDto;
import com.dbxray.infrastructure.db.repository.dto.ViewReferenceDto;
import com.dbxray.infrastructure.db.type.DatabaseType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/** DBのカタログの取得に関するリポジトリの基底クラス */
public abstract class AbstractCatalogRepository implements CatalogRepository {

  private final String baseSqlPath;
  private final SqlSessionFactory sqlSessionFactory;

  protected AbstractCatalogRepository(
      DatabaseType databaseType, SqlSessionFactory sqlSessionFactory) {
    this.baseSqlPath =
        "com.dbxray.domain.repository." + databaseType.getName() + ".CatalogRepository.";
    this.sqlSessionFactory = sqlSessionFactory;
  }

  /** {@inheritDoc} */
  @Override
  public DatabaseEntity selectDatabase() {
    final DatabaseDto dto =
        select("selectDatabaseInfo", (session, sqlPath) -> session.selectOne(sqlPath));
    return dto.toEntity();
  }

  /** {@inheritDoc} */
  @Override
  public List<TableEntity> selectTableList(List<String> schemaList) {
    return selectTableDefinition(schemaList, List.of(), "selectTableInfo", TableDto::toEntity);
  }

  /**
   * {@inheritDoc}<br>
   * カラム・インデックス・制約を種類ごとに対象テーブル分まとめて取得し、テーブルごとに振り分ける
   */
  @Override
  public List<TableDetail> selectTableDetails(List<TableEntity> tables) {
    final List<String> schemaList =
        tables.stream().map(TableEntity::schemaName).distinct().toList();
    final List<String> tableList =
        tables.stream().map(TableEntity::physicalTableName).distinct().toList();
    final List<ColumnEntity> columns =
        selectTableDefinition(schemaList, tableList, "selectColumnInfo", ColumnDto::toEntity);
    final List<IndexEntity> indexes =
        selectTableDefinition(schemaList, tableList, "selectIndexInfo", IndexDto::toEntity);
    final List<ConstraintEntity> constraints =
        selectTableDefinition(
            schemaList, tableList, "selectConstraintInfo", ConstraintDto::toEntity);
    return TableDetail.assembleAll(tables, columns, indexes, constraints);
  }

  /**
   * {@inheritDoc}<br>
   * スキーマ名・テーブル名をそれぞれIN句で絞り込むため、複数スキーマのテーブルを渡すと他スキーマの同名テーブルも一致する。指定したテーブルのものだけを残す
   */
  @Override
  public List<ColumnEntity> selectColumnList(List<TableKey> tables) {
    // 空のリストを渡すとSQLの絞り込みが外れ、全テーブルのカラムを取得してしまう
    if (tables.isEmpty()) {
      return List.of();
    }
    final List<String> schemaList = tables.stream().map(TableKey::schema).distinct().toList();
    final List<String> tableList = tables.stream().map(TableKey::table).distinct().toList();
    final Set<TableKey> keys = Set.copyOf(tables);
    return selectTableDefinition(schemaList, tableList, "selectColumnInfo", ColumnDto::toEntity)
        .stream()
        .filter(column -> keys.contains(column.tableKey()))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public List<ForeignKeyEntity> selectForeignKeyList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectForeignKeyInfo", ForeignKeyDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<TriggerEntity> selectTriggerList(List<String> schemaList) {
    return selectTableDefinition(schemaList, List.of(), "selectTriggerInfo", TriggerDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<PartitionEntity> selectPartitionList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectPartitionInfo", PartitionDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<ViewReferenceEntity> selectViewReferenceList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectViewReferenceInfo", ViewReferenceDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<FunctionEntity> selectFunctionList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectFunctionInfo", FunctionDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<FunctionEntity> selectFunctionDefList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectFunctionDefInfo", FunctionDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<SequenceEntity> selectSequenceList(List<String> schemaList) {
    return selectTableDefinition(
        schemaList, List.of(), "selectSequenceInfo", SequenceDto::toEntity);
  }

  /** {@inheritDoc} */
  @Override
  public List<TypeEntity> selectTypeList(List<String> schemaList) {
    return selectTableDefinition(schemaList, List.of(), "selectTypeInfo", TypeDto::toEntity);
  }

  private <D, E> List<E> selectTableDefinition(
      List<String> schemaList, List<String> tableList, String sqlId, Function<D, E> mapper) {
    final List<D> dtoList =
        select(
            sqlId,
            (session, sqlPath) ->
                session.selectList(
                    sqlPath,
                    Map.ofEntries(
                        Map.entry("schemaList", schemaList), Map.entry("tableList", tableList))));
    // DTO→エンティティの変換の失敗（未知の区分等）は、SQLの失敗と取り違えないよう包まずに伝える
    return dtoList.stream().map(mapper).toList();
  }

  /**
   * SQLを実行するメソッド<br>
   * SQLの失敗は、どのSQLで失敗したかを添えて包む。DBが返したエラー（原因）は包んだ例外の原因として残り、エントリーポイントの境界が表示・ログ出力する
   *
   * @param sqlId 実行するSQLのID（DB種別ごとの名前空間を除く）
   * @param query SqlSessionとSQLの完全修飾IDを受け取り、SQLを実行する処理
   */
  private <T> T select(String sqlId, BiFunction<SqlSession, String, T> query) {
    final String sqlPath = baseSqlPath + sqlId;
    try (SqlSession session = sqlSessionFactory.openSession()) {
      return query.apply(session, sqlPath);
    } catch (PersistenceException e) {
      throw new RuntimeException("Failed to select: " + sqlPath, e);
    }
  }
}
