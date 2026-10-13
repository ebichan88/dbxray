package com.dbxray.domain.model.viewpoint;

import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.table.TableEntity;
import java.util.List;

/**
 * 1観点分の出力内容をまとめたレコード<br>
 * 観点ページ（所属テーブル同士のER図・所属テーブルの一覧・観点外のテーブルとの関連）と、観点一覧のテーブル数の掲載に用いる。
 * {@link Viewpoint#resolve}で組み立てる
 *
 * @param tables 所属テーブルのリスト（出力対象のテーブルの並び順）
 * @param relations 両端が所属テーブルの関連（外部キー・論理リレーション）のまとまり。観点のER図に描画する
 * @param outsideRelations 片端だけが所属テーブルの関連（観点外のテーブルとの関連）のリスト
 */
public record ViewpointContent(
    Viewpoint viewpoint,
    List<TableEntity> tables,
    ForeignKeyGroup relations,
    List<ForeignKeyEntity> outsideRelations) {

  /** 外部から渡されたリストの変更が影響しないよう、変更不可のコピーを保持する */
  public ViewpointContent {
    tables = List.copyOf(tables);
    outsideRelations = List.copyOf(outsideRelations);
  }
}
