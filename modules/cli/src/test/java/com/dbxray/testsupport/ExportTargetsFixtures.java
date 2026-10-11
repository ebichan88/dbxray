package com.dbxray.testsupport;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** テスト用の{@link ExportTargets}を生成するユーティリティ（指定しない情報は空） */
public final class ExportTargetsFixtures {

  private ExportTargetsFixtures() {}

  /**
   * テーブル・関連・関数だけを持つ出力対象を生成する
   *
   * @param objectTypes 取得した追加オブジェクトの種別
   * @return 出力対象
   */
  public static ExportTargets of(
      List<TableEntity> tables,
      List<ForeignKeyEntity> foreignKeys,
      List<FunctionEntity> functions,
      Set<OutputObjectType> objectTypes) {
    return new ExportTargets(
        new BaseInfoEntity("testdb", Dbms.POSTGRESQL, 16, LocalDate.EPOCH),
        Tables.of(tables),
        ForeignKeys.of(foreignKeys),
        Triggers.of(List.of()),
        Partitions.of(List.of()),
        ViewReferences.of(List.of()),
        Functions.of(functions),
        Sequences.of(List.of()),
        Types.of(List.of()),
        Annotations.empty(),
        Viewpoints.empty(),
        objectTypes);
  }
}
