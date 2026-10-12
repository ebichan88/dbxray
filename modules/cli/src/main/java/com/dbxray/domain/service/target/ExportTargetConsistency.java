package com.dbxray.domain.service.target;

import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.target.ConsistencyNotice;
import com.dbxray.domain.model.target.ConsistencyNotice.Kind;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 出力対象のテーブルと、それを参照する情報（外部キー・サイドカーの論理リレーション／付帯情報）を突き合わせるドメインサービス<br>
 * 外部キーはスキーマ全体から、サイドカーは出力対象に関係なく読み込むため、出力対象の絞り込みで除外されたテーブルや、
 * リネーム・削除されたテーブル／カラムを参照していることがある。それらを出力から除外し、
 * 利用者が気付けるよう通知（{@link ConsistencyNotice}）として返す。通知をどこへ出力するか（ログ等）は呼び出し側が決める
 */
public class ExportTargetConsistency {

  /**
   * 外部キーの突き合わせ結果
   *
   * @param foreignKeys 出力対象のテーブル同士の外部キーの集合（物理外部キー、論理リレーションの順）
   */
  public record ResolvedForeignKeys(
      ForeignKeys foreignKeys, List<ConsistencyNotice> consistencyNotices) {

    public ResolvedForeignKeys {
      consistencyNotices = List.copyOf(consistencyNotices);
    }
  }

  /**
   * 物理外部キーとサイドカー由来の論理リレーションのうち、参照元・参照先の双方が出力対象のテーブルであるものを 1つの外部キーの集合にまとめるメソッド<br>
   * 片側だけが出力対象のものを残すと、テーブル一覧・個別の定義書には現れないテーブルがER図にだけ箱として残ってしまうため除外する。
   * 論理リレーションも物理外部キーと同じ集合へ合流させることで、ER図のグループ分割（連結成分）やスキーマ跨ぎ関連の抽出にも自動的に反映される。<br>
   * 除外したものを通知とするかは由来ごとに異なる。物理外部キーは、出力対象が絞り込まれている場合は意図した除外のため通知しない。
   * 論理リレーションは、除外の理由が絞り込みによるものかリネーム・削除による乖離かを区別できないため、一律で通知する
   */
  public ResolvedForeignKeys resolveForeignKeys(
      List<ForeignKeyEntity> physicalForeignKeys,
      List<ForeignKeyEntity> logicalRelations,
      Tables tables,
      boolean isFiltered) {
    final List<ConsistencyNotice> consistencyNotices = new ArrayList<>();
    final List<ForeignKeyEntity> resolvedRelations =
        resolveLogicalRelations(logicalRelations, tables, consistencyNotices);
    final List<ForeignKeyEntity> resolvedForeignKeys =
        physicalForeignKeys.stream()
            .filter(
                fk -> isResolvablePhysicalForeignKey(fk, tables, isFiltered, consistencyNotices))
            .toList();
    return new ResolvedForeignKeys(
        ForeignKeys.of(
            Stream.concat(resolvedForeignKeys.stream(), resolvedRelations.stream()).toList()),
        consistencyNotices);
  }

  /**
   * 実在しないテーブルに対する付帯情報（＝孤児付帯情報）を検出するメソッド<br>
   * リネームや削除により、サイドカーの付帯情報が現在のスキーマと乖離した場合の気付きとする。
   * スキーマ・テーブルの出力対象が絞り込まれている場合は、対象外テーブルの付帯情報を
   * 誤って孤児と判定しないよう検出を行わない（検出を行わなかったこと自体を通知として返す）
   */
  public List<ConsistencyNotice> findOrphanTableAnnotations(
      Annotations annotations, Tables tables, boolean isFiltered) {
    if (annotations.isEmpty()) {
      return List.of();
    }
    if (isFiltered) {
      return List.of(
          new ConsistencyNotice(
              Kind.ORPHAN_TABLE_ANNOTATION_CHECK_SKIPPED,
              "Skipping orphan table annotation check because the output target is filtered."));
    }
    return annotations.tableKeys().stream()
        .filter(key -> !tables.contains(key))
        .map(
            key ->
                new ConsistencyNotice(
                    Kind.ORPHAN_TABLE_ANNOTATION,
                    "Annotation exists for a table that was not found (renamed or dropped?). [table="
                        + key.qualifiedName()
                        + "]"))
        .toList();
  }

  /**
   * 観点の所属テーブルのパターンのうち、どのテーブルにも一致しないものを検出するメソッド<br>
   * リネームや削除により、サイドカーの観点の宣言が現在のスキーマと乖離した場合の気付きとする。
   * スキーマ・テーブルの出力対象が絞り込まれている場合は、対象外のテーブルを指すパターンを誤って乖離と判定しないよう検出を行わない
   */
  public List<ConsistencyNotice> findUnmatchedViewpointPatterns(
      Viewpoints viewpoints, Tables tables, boolean isFiltered) {
    if (isFiltered) {
      return List.of();
    }
    return viewpoints.stream()
        .flatMap(
            viewpoint ->
                viewpoint.unmatchedPatterns(tables).stream()
                    .map(
                        pattern ->
                            new ConsistencyNotice(
                                Kind.UNMATCHED_VIEWPOINT_PATTERN,
                                "Viewpoint table pattern matches no table (renamed or dropped?). "
                                    + "[viewpoint="
                                    + viewpoint.id()
                                    + ", pattern="
                                    + pattern
                                    + "]")))
        .toList();
  }

  /**
   * 実在しないカラムに対するカラム備考（＝孤児付帯情報）を検出するメソッド<br>
   * 出力対象のテーブルに対してのみ、実在カラムと付帯情報のカラム名を突き合わせて検出する
   */
  public List<ConsistencyNotice> findOrphanColumnAnnotations(
      TableDetail detail, Annotations annotations) {
    final TableEntity table = detail.table();
    final Set<String> actualColumnNames =
        detail.columns().stream().map(ColumnEntity::physicalColumnName).collect(Collectors.toSet());
    return annotations.belongingTo(table).orphanColumnNames(actualColumnNames).stream()
        .map(
            columnName ->
                new ConsistencyNotice(
                    Kind.ORPHAN_COLUMN_ANNOTATION,
                    "Column annotation exists for a column that was not found (renamed or dropped?). "
                        + "[table="
                        + table.getSchemaTableName()
                        + ", column="
                        + columnName
                        + "]"))
        .toList();
  }

  /**
   * 論理リレーションのうち、参照元・参照先の双方が出力対象のテーブルであるものを抽出するメソッド<br>
   * 除外したものは、どちら側が解決できなかったかを通知に加える。合流させた件数も通知（報告）として加える
   */
  private List<ForeignKeyEntity> resolveLogicalRelations(
      List<ForeignKeyEntity> logicalRelations,
      Tables tables,
      List<ConsistencyNotice> consistencyNotices) {
    if (logicalRelations.isEmpty()) {
      return List.of();
    }
    final List<ForeignKeyEntity> resolved =
        logicalRelations.stream()
            .filter(relation -> isResolvableLogicalRelation(relation, tables, consistencyNotices))
            .toList();
    consistencyNotices.add(
        new ConsistencyNotice(
            Kind.LOGICAL_RELATIONS_MERGED,
            "Merged logical relations declared in the sidecar. [relationCount="
                + resolved.size()
                + "]"));
    return resolved;
  }

  /**
   * 物理外部キーの参照元・参照先が、いずれも出力対象のテーブルとして実在するか判定するメソッド<br>
   * 出力対象が絞り込まれていない場合のみ、除外する外部キーを通知に加える
   */
  private boolean isResolvablePhysicalForeignKey(
      ForeignKeyEntity foreignKey,
      Tables tables,
      boolean isFiltered,
      List<ConsistencyNotice> consistencyNotices) {
    if (tables.contains(foreignKey.tableKey()) && tables.contains(foreignKey.referenceTableKey())) {
      return true;
    }
    if (!isFiltered) {
      consistencyNotices.add(
          new ConsistencyNotice(
              Kind.UNRESOLVED_FOREIGN_KEY,
              "Skipping a foreign key because the referenced table was not found "
                  + "(renamed or dropped?). [foreignKey="
                  + foreignKey.foreignKeyName()
                  + ", table="
                  + foreignKey.getSchemaTableName()
                  + ", referenceTable="
                  + foreignKey.getReferenceSchemaTableName()
                  + "]"));
    }
    return false;
  }

  /**
   * 論理リレーションの参照元・参照先が、いずれも出力対象のテーブルとして実在するか判定するメソッド<br>
   * 実在しない場合は、どちら側が解決できなかったかを通知に加える
   */
  private boolean isResolvableLogicalRelation(
      ForeignKeyEntity relation, Tables tables, List<ConsistencyNotice> consistencyNotices) {
    final boolean childExists = tables.contains(relation.tableKey());
    final boolean parentExists = tables.contains(relation.referenceTableKey());
    if (childExists && parentExists) {
      return true;
    }
    consistencyNotices.add(
        new ConsistencyNotice(
            Kind.UNRESOLVED_LOGICAL_RELATION,
            "Skipping logical relation because the table was not found in the output target "
                + "(filtered, renamed or dropped?). [relation="
                + relation.foreignKeyName()
                + ", table="
                + relation.getSchemaTableName()
                + (childExists ? "" : " (not found)")
                + ", parentTable="
                + relation.getReferenceSchemaTableName()
                + (parentExists ? "" : " (not found)")
                + "]"));
    return false;
  }
}
