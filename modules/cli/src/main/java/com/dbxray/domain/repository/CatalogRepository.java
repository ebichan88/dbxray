package com.dbxray.domain.repository;

import com.dbxray.domain.model.database.DatabaseEntity;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import java.util.List;

/** DBのカタログの取得に関するリポジトリインターフェース */
public interface CatalogRepository {

  /** ドキュメントの生成日はDBではなく実行時に決まるため含まない */
  DatabaseEntity selectDatabase();

  /**
   * テーブル単位の絞り込みは呼び出し側（{@link com.dbxray.domain.model.target.TableScope}）がJava側で行うため、スキーマ単位でのみ絞り込む
   */
  List<TableEntity> selectTableList(List<String> schemaList);

  /**
   * テーブル数に比例して重くなる情報のため、呼び出し側はスキーマ・チャンク単位でテーブルを渡す（同一スキーマのテーブルを渡すことを想定する）
   *
   * @return テーブルごとの詳細情報のリスト（{@code tables}と同じ順）
   */
  List<TableDetail> selectTableDetails(List<TableEntity> tables);

  /**
   * 指定したテーブルのカラムを取得する。テーブル数に比例して重くなる情報のため、呼び出し側は同一スキーマのテーブルをチャンク単位で渡す
   *
   * @return カラムのリスト（テーブルごとにカラムの定義順）。存在しないテーブルのカラムは含まない
   */
  List<ColumnEntity> selectColumnList(List<TableKey> tables);

  /** テーブル単位の絞り込みは行わず、スキーマ全体を取得する（{@link #selectTableList}を参照） */
  List<ForeignKeyEntity> selectForeignKeyList(List<String> schemaList);

  /** テーブル単位の絞り込みは行わず、スキーマ全体を取得する（{@link #selectTableList}を参照） */
  List<TriggerEntity> selectTriggerList(List<String> schemaList);

  /**
   * パーティション表の下位のパーティション（多段パーティションの中間を含む）を取得する。
   * パーティションはテーブルとして{@link #selectTableList}に含まれないため、パーティション表の定義書にまとめるために別に取得する。
   * テーブル単位の絞り込みは行わず、スキーマ全体を取得する（{@link #selectTableList}を参照）
   *
   * @param schemaList パーティション表（根）のスキーマで絞り込む。パーティションは別スキーマにあってもよい
   * @return パーティション表ごとに、親から子へ階層順に並べたパーティションのリスト
   */
  List<PartitionEntity> selectPartitionList(List<String> schemaList);

  /**
   * ビュー（マテリアライズドビューを含む）が参照するテーブル（ビュー・マテリアライズドビューを含む）を取得する。
   * テーブル単位の絞り込みは行わず、スキーマ全体を取得する（{@link #selectTableList}を参照）
   *
   * @param schemaList 参照する側のビューのスキーマで絞り込む。参照されるテーブルは別のスキーマにあってもよい
   * @return ビューごとに、参照されるテーブルのスキーマ名・テーブル名の順に並べたリスト。同じテーブルは1件にまとめる。
   *     自身への参照と、DBMSが管理するスキーマのテーブルへの参照は含まない。パーティションへの参照は、パーティション表（根）への参照とする
   */
  List<ViewReferenceEntity> selectViewReferenceList(List<String> schemaList);

  /** 定義本体を含まない軽量情報 */
  List<FunctionEntity> selectFunctionList(List<String> schemaList);

  /** {@link #selectFunctionList}と同じ項目（種別・引数・戻り値・言語）に加え、定義本体を含む */
  List<FunctionEntity> selectFunctionDefList(List<String> schemaList);

  /** 指定したスキーマに属するシーケンスの一覧を取得する */
  List<SequenceEntity> selectSequenceList(List<String> schemaList);

  /** 指定したスキーマに属するユーザー定義型の一覧を取得する */
  List<TypeEntity> selectTypeList(List<String> schemaList);
}
