package com.dbxray.domain.model.target;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import java.util.Set;

/**
 * 出力対象のうち、一括取得する軽量な情報の組<br>
 * テーブル数に比例して重くなる詳細情報（カラム・インデックス・制約）と関数の定義本体は含まない。 それらは出力時にスキーマ・チャンク単位で取得する
 *
 * @param tables 出力対象のテーブル情報のリスト（テーブルの絞り込み済み）
 * @param foreignKeys 出力対象のテーブル同士の外部キー（論理リレーションを含む）
 * @param triggers 対象範囲全体のトリガー情報
 * @param partitions 対象範囲全体のパーティション表の下位のパーティション（テーブルには含まれない）
 * @param viewReferences 出力対象のビューが参照するテーブル（参照されるテーブルは出力対象外のものを含む）
 * @param functions 関数・プロシージャの一覧情報（定義本体を含まない）
 * @param annotations 対象範囲全体の手動付帯情報
 * @param viewpoints サイドカーYAMLで宣言された観点
 * @param objectTypes 取得した追加オブジェクトの種別（{@code target.objects}で外した種別は含まない。
 *     外した種別は取得自体をしないため、その種別の一覧が空でも0件とは限らない）
 */
public record ExportTargets(
    BaseInfoEntity baseInfo,
    Tables tables,
    ForeignKeys foreignKeys,
    Triggers triggers,
    Partitions partitions,
    ViewReferences viewReferences,
    Functions functions,
    Sequences sequences,
    Types types,
    Annotations annotations,
    Viewpoints viewpoints,
    Set<OutputObjectType> objectTypes) {

  /** 取得した種別の集合は変更不可な複製として保持する */
  public ExportTargets {
    objectTypes = Set.copyOf(objectTypes);
  }
}
