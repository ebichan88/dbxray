package com.dbxray.domain.model.target;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import java.util.List;

/**
 * テーブル定義出力に必要な情報をまとめたレコード<br>
 * 1テーブル分の「何を出力するか」のみを持ち、出力先（ディレクトリ）は出力形式ごとの書き込み側が持つ
 *
 * @param foreignKeys 自テーブルが参照する関連のうち、DBに実在する外部キー制約（物理）のリスト
 * @param logicalRelations 自テーブルが参照する関連のうち、サイドカーYAMLで宣言された論理リレーションのリスト
 * @param incomingRelations 自テーブルを参照する関連（物理外部キー・論理リレーションの双方）のリスト
 * @param referencedTables 当該テーブルがビューの場合の、参照するテーブル（ビューでない場合は空）
 * @param referencingViews 当該テーブルを参照している出力対象のビュー
 * @param partitions 当該テーブルがパーティション表の場合の、下位のパーティション（親から子へ階層順。パーティション表でない場合は空）
 * @param annotation 手動付帯情報
 * @param viewpoints 当該テーブルが所属する観点のリスト（宣言順。所属する観点が無い場合は空）
 */
public record TableDefinitionContent(
    BaseInfoEntity baseInfo,
    TableEntity table,
    List<ColumnEntity> columns,
    List<IndexEntity> indexes,
    List<ConstraintEntity> constraints,
    List<ForeignKeyEntity> foreignKeys,
    List<ForeignKeyEntity> logicalRelations,
    List<ForeignKeyEntity> incomingRelations,
    List<ViewReferenceEntity> referencedTables,
    List<ViewReferenceEntity> referencingViews,
    List<TriggerEntity> triggers,
    List<PartitionEntity> partitions,
    TableAnnotation annotation,
    List<Viewpoint> viewpoints) {

  /**
   * テーブル定義出力に必要な情報をまとめたレコードを組み立てる<br>
   * 参照側（自テーブル → 参照先）の関連は、DBに実在する外部キー制約（{@code foreignKeys}）とサイドカーYAML由来の論理リレーション
   * （{@code logicalRelations}）に分けて保持する。テーブル定義書では別々のセクションへ掲載するため。
   * 被参照側（{@code incomingRelations}）は「被参照情報」セクションの1つの表に「区分」列で由来を示して掲載するため、由来を分けない（物理外部キーと論理リレーションの双方を含む）
   *
   * @param detail 当該テーブルの詳細情報（カラム・インデックス・制約）
   * @param foreignKeys 対象範囲全体の外部キー（論理リレーションを含む。当該テーブル分を抽出して保持する）
   * @param triggers 対象範囲全体のトリガー情報（当該テーブル分を抽出して保持する）
   * @param partitions 対象範囲全体のパーティション情報（当該テーブル分を抽出して保持する）
   * @param viewReferences 対象範囲全体のビューが参照するテーブル（当該テーブルが参照するもの・当該テーブルを参照するものを抽出して保持する）
   * @param annotations 対象範囲全体の手動付帯情報（当該テーブル分を抽出して保持する）
   * @param viewpoints サイドカーYAMLで宣言された観点（当該テーブルが所属するものを抽出して保持する）
   * @return TableDefinitionContent
   */
  public static TableDefinitionContent assemble(
      BaseInfoEntity baseInfo,
      TableDetail detail,
      ForeignKeys foreignKeys,
      Triggers triggers,
      Partitions partitions,
      ViewReferences viewReferences,
      Annotations annotations,
      Viewpoints viewpoints) {
    final TableEntity table = detail.table();
    return new TableDefinitionContent(
        baseInfo,
        table,
        detail.columns(),
        detail.indexes(),
        detail.constraints(),
        foreignKeys.physicalBelongingTo(table),
        foreignKeys.logicalBelongingTo(table),
        foreignKeys.referencingTo(table),
        viewReferences.belongingTo(table),
        viewReferences.referencingTo(table),
        triggers.belongingTo(table),
        partitions.belongingTo(table),
        annotations.belongingTo(table),
        viewpoints.containing(table));
  }
}
