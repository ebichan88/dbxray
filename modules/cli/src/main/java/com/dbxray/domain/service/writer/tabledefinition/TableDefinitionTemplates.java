package com.dbxray.domain.service.writer.tabledefinition;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.row;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.DiagramColumn;
import com.dbxray.domain.model.relation.DiagramNeighborhood;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.PartitionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.domain.service.writer.template.MermaidSupport;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;
import com.dbxray.domain.service.writer.template.RowRenderer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** テーブル定義書き込みに利用するMarkdownのテンプレートを扱うクラス */
public final class TableDefinitionTemplates {

  private TableDefinitionTemplates() {}

  /** テーブル定義ヘッダー */
  public static String fileHeader(TableEntity table) {
    return MarkdownTemplateSupport.fileHeader(table.headerName());
  }

  /** 基本情報セクション */
  public static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /**
   * テーブル説明セクション<br>
   * サイドカー由来の説明が存在する場合はその本文を、存在しない場合は従来通り空のセクションを出力する。
   * 説明本文は自由記述のブロックとしてそのまま出力するため、改行はエスケープしない
   */
  public static String tableExplanation(TableAnnotation annotation) {
    final String description = annotation.description();
    if (description.isBlank()) {
      return """
                    ## テーブル説明

                    """;
    }
    return "## テーブル説明"
        + LINE_SEPARATOR_DOUBLE
        + description.stripTrailing()
        + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * テーブル情報セクション<br>
   * 論理テーブル名はDBコメント由来の自由記述文字列（{@code |}・改行を含みうる）のためエスケープする。
   * 末尾の備考セルはSQLでは付与されないため、サイドカー由来のテーブル備考を
   * エスケープした上でここで後付けする（FK多重度と同様の後付け方式）
   */
  public static String tableInfo(TableEntity table, TableAnnotation annotation) {
    return """
                ## テーブル情報

                | スキーマ名 | 論理テーブル名 | 物理テーブル名 | 区分 | 備考 |
                |:---|:---|:---|:---|:---|
                """
        + row(
            table.schemaName(),
            MarkdownTemplateSupport.escapeTableCell(table.logicalTableName()),
            table.physicalTableName(),
            table.tableType().id(),
            MarkdownTemplateSupport.escapeTableCell(annotation.remarks()))
        + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * カラム情報セクション<br>
   * 論理名（DBコメント）・デフォルト値（DBのデフォルト式。PostgreSQLの{@code ||}連結等で{@code |}を含みうる）は、
   * いずれも自由記述文字列で改行を含む場合もあるためエスケープする
   */
  public static String columns(List<ColumnEntity> columns, TableAnnotation annotation) {
    final String header =
        """
                ## カラム情報

                | No. | 論理名 | 物理名 | データ型 | 桁数/精度 | PK | Not Null | デフォルト | 備考 |
                |:---|:---|:---|:---|:---|:---|:---|:---|:---|
                """;
    // 末尾の備考セルはSQL由来ではないため、物理カラム名をキーにサイドカー由来の備考を後付けする
    return tableSection(
        columns,
        header,
        (no, c) ->
            row(
                no,
                MarkdownTemplateSupport.escapeTableCell(c.logicalColumnName()),
                c.physicalColumnName(),
                c.columnType(),
                c.precisionScale(),
                MarkdownTemplateSupport.marker(c.primaryKey()),
                MarkdownTemplateSupport.marker(c.notNull()),
                MarkdownTemplateSupport.escapeTableCell(c.defaultValue()),
                MarkdownTemplateSupport.escapeTableCell(
                    annotation.columnRemark(c.physicalColumnName()))));
  }

  /**
   * パーティション情報セクション<br>
   * パーティション表（宣言的パーティションの親）にだけ出力する。パーティションは個別の定義書を持たないため、ここにまとめる。
   * 多段パーティションは、親から子へ階層順に並べ、親の列で入れ子を示す。
   * パーティション境界・下位のパーティションキーは式のため、{@code |}・改行を含みうるのでエスケープする
   *
   * @param partitions 当該テーブルの下位のパーティション（親から子へ階層順）
   * @return パーティション表でない場合は空文字
   */
  public static String partitions(TableEntity table, List<PartitionEntity> partitions) {
    if (!table.isPartitioned()) {
      return "";
    }
    final String keyLine =
        "## パーティション情報"
            + LINE_SEPARATOR_DOUBLE
            + "パーティションキー: "
            + MarkdownTemplateSupport.codeSpan(table.partitionKey())
            + LINE_SEPARATOR_DOUBLE;
    if (partitions.isEmpty()) {
      return keyLine + "パーティションはありません。" + LINE_SEPARATOR_DOUBLE;
    }
    final String header =
        keyLine
            + """
                | No. | パーティション | 親 | パーティション境界 | 下位のパーティションキー |
                |:---|:---|:---|:---|:---|
                """;
    return tableSection(
        partitions,
        header,
        (no, p) ->
            row(
                no,
                p.displayName(),
                p.displayParentName(),
                MarkdownTemplateSupport.escapeTableCell(p.bound()),
                MarkdownTemplateSupport.escapeTableCell(p.partitionKey())));
  }

  /** ソース（view・materialized viewの定義）セクション。viewでないテーブルでは空文字 */
  public static String view(TableEntity table) {
    if (!table.isView()) {
      return "";
    }
    final String fence = MarkdownTemplateSupport.codeFence(table.definition());
    return """
                ## ソース

                %ssql
                %s%s%s
                %s

                """
        .formatted(fence, LINE_SEPARATOR, table.definition(), LINE_SEPARATOR, fence);
  }

  /**
   * インデックス情報セクション<br>
   * 備考はDBコメント（{@code COMMENT ON INDEX}）由来の自由記述文字列のためエスケープする
   */
  public static String indexes(List<IndexEntity> indexes) {
    final String header =
        """
                ## インデックス情報

                | No. | インデックス名 | 種別 | UNIQUE | PRIMARY | 定義 | 備考 |
                |:---|:---|:---|:---|:---|:---|:---|
                """;
    return tableSection(
        indexes,
        header,
        (no, idx) ->
            row(
                no,
                idx.indexName(),
                idx.indexMethod(),
                MarkdownTemplateSupport.marker(idx.isUnique()),
                MarkdownTemplateSupport.marker(idx.isPrimary()),
                MarkdownTemplateSupport.escapePipe(idx.indexDefinition()),
                MarkdownTemplateSupport.escapeTableCell(idx.remarks())));
  }

  /**
   * 制約情報セクション<br>
   * 備考はDBコメント（{@code COMMENT ON CONSTRAINT}）由来の自由記述文字列のためエスケープする
   */
  public static String constraints(List<ConstraintEntity> constraints) {
    final String header =
        """
                ## 制約情報

                | No. | 制約名 | 種類 | 制約定義 | 備考 |
                |:---|:---|:---|:---|:---|
                """;
    return tableSection(
        constraints,
        header,
        (no, c) ->
            row(
                no,
                c.constraintName(),
                c.constraintType(),
                MarkdownTemplateSupport.escapePipe(c.constraintDefinition()),
                MarkdownTemplateSupport.escapeTableCell(c.remarks())));
  }

  /** 外部キー情報セクション（DBに実在する外部キー制約のみ） */
  public static String foreignKeys(List<ForeignKeyEntity> foreignKeys) {
    final String header =
        """
                ## 外部キー情報

                | No. | 外部キー名 | カラムリスト | 参照先 | 参照先カラムリスト | 多重度 |
                |:---|:---|:---|:---|:---|:---|
                """;
    return tableSection(foreignKeys, header, TableDefinitionTemplates::physicalRelationTableLine);
  }

  /**
   * 論理リレーション情報セクション<br>
   * DBに外部キー制約が存在せず、サイドカーYAMLで宣言された関連のみを掲載する。
   * 読み手が「DBに制約がある」と誤読しないよう外部キー情報とは別セクションとし、注意書きを添える。
   * 対象が1件も存在しない場合はセクションごと出力しない（制約を張っているDBでは常に不要なため）。
   * 関連名は現状カラムリストと同じ内容のため列を設けず、カラムリストのみ掲載する
   *
   * @return 対象が存在しない場合は空文字
   */
  public static String logicalRelations(List<ForeignKeyEntity> logicalRelations) {
    if (logicalRelations.isEmpty()) {
      return "";
    }
    final String header =
        "## 論理リレーション情報"
            + LINE_SEPARATOR_DOUBLE
            + "※DBに外部キー制約は存在せず、サイドカーYAMLで宣言された関連です。"
            + LINE_SEPARATOR_DOUBLE
            + """
                | No. | カラムリスト | 参照先 | 参照先カラムリスト | 多重度 |
                |:---|:---|:---|:---|:---|
                """;
    // 物理外部キーの行番号とは独立に、当セクション内で1から採番する
    return tableSection(
        logicalRelations, header, TableDefinitionTemplates::logicalRelationTableLine);
  }

  /**
   * 被参照情報セクション<br>
   * 自テーブルを参照している関連（物理外部キー・論理リレーションの双方）を、由来を「区分」列で区別して掲載する。
   * 自己参照は外部キー情報・論理リレーション情報に掲載されるため含まれない。
   * 参照元が無い場合はセクションごと出力しない（外部キー情報と異なり、参照されないテーブルの定義書は変わらない）
   *
   * @param incomingRelations 自テーブルを参照している関連のリスト
   * @return 対象が存在しない場合は空文字
   */
  public static String incomingRelations(List<ForeignKeyEntity> incomingRelations) {
    if (incomingRelations.isEmpty()) {
      return "";
    }
    final String header =
        """
                ## 被参照情報

                | No. | 参照元 | 参照元カラムリスト | 参照されるカラムリスト | 関連名 | 多重度 | 区分 |
                |:---|:---|:---|:---|:---|:---|:---|
                """;
    return tableSection(
        incomingRelations,
        header,
        (no, fk) ->
            row(
                no,
                fk.tableKey().qualifiedName(),
                String.join(",", fk.columnNames()),
                String.join(",", fk.referenceColumnNames()),
                fk.foreignKeyName(),
                fk.cardinality().label(),
                fk.relationType().label()));
  }

  /**
   * 参照するテーブルセクション<br>
   * ビューが参照するテーブル（ビューを含む）を掲載する。出力対象外のテーブルも、ビューが参照する事実として掲載する
   *
   * @param referencedTables ビューが参照するテーブル（スキーマ名・テーブル名の順）
   * @return 対象が存在しない場合（ビューでない場合を含む）は空文字
   */
  public static String referencedTables(List<ViewReferenceEntity> referencedTables) {
    if (referencedTables.isEmpty()) {
      return "";
    }
    final String header =
        """
                ## 参照するテーブル

                | No. | 参照先 | 区分 |
                |:---|:---|:---|
                """;
    return tableSection(
        referencedTables,
        header,
        (no, reference) ->
            row(
                no,
                reference.referenceTableKey().qualifiedName(),
                reference.referenceTableType().id()));
  }

  /**
   * 参照しているビューセクション<br>
   * 自テーブル（ビューを含む）を参照している出力対象のビューを掲載する。テーブルを変更したときに影響を受けるビューを知るため
   *
   * @param referencingViews 自テーブルを参照しているビュー
   * @return 対象が存在しない場合は空文字
   */
  public static String referencingViews(List<ViewReferenceEntity> referencingViews) {
    if (referencingViews.isEmpty()) {
      return "";
    }
    final String header =
        """
                ## 参照しているビュー

                | No. | 参照元 | 区分 |
                |:---|:---|:---|
                """;
    return tableSection(
        referencingViews,
        header,
        (no, reference) ->
            row(no, reference.tableKey().qualifiedName(), reference.tableType().id()));
  }

  /**
   * 多重度のラベル表記は{@link com.dbxray.domain.model.relation.Cardinality}に集約している
   *
   * @return 1行分の文字列（改行を含まない）
   */
  private static String physicalRelationTableLine(int no, ForeignKeyEntity fk) {
    return row(
        no,
        fk.foreignKeyName(),
        String.join(",", fk.columnNames()),
        fk.referenceQualifiedName(),
        String.join(",", fk.referenceColumnNames()),
        fk.cardinality().label());
  }

  /**
   * @return 1行分の文字列（改行を含まない）
   */
  private static String logicalRelationTableLine(int no, ForeignKeyEntity fk) {
    return row(
        no,
        String.join(",", fk.columnNames()),
        fk.referenceQualifiedName(),
        String.join(",", fk.referenceColumnNames()),
        fk.cardinality().label());
  }

  /**
   * トリガー情報セクション<br>
   * 本体を持つトリガー（Oracle）は、表の後にトリガーごとの見出しとコードブロックで本体を出す（複数行のため表のセルに収まらない）
   */
  public static String triggers(List<TriggerEntity> triggers) {
    final String header =
        """
                ## トリガー情報

                | No. | トリガー名 | タイミング | イベント | 単位 | 定義 |
                |:---|:---|:---|:---|:---|:---|
                """;
    final StringBuilder sb =
        new StringBuilder(
            tableSection(
                triggers,
                header,
                (no, t) ->
                    row(
                        no,
                        t.triggerName(),
                        t.timing(),
                        String.join("/", t.events()),
                        t.orientation(),
                        MarkdownTemplateSupport.escapePipe(t.triggerDefinition()))));
    triggers.stream().filter(t -> !t.body().isEmpty()).forEach(t -> sb.append(triggerBody(t)));
    return sb.toString();
  }

  /** トリガーの本体（見出しとコードブロック） */
  private static String triggerBody(TriggerEntity trigger) {
    return "### "
        + MarkdownTemplateSupport.escapeInline(trigger.triggerName())
        + LINE_SEPARATOR_DOUBLE
        + MarkdownTemplateSupport.sqlCodeBlock(trigger.body());
  }

  /**
   * ER図セクション（Mermaid記法）<br>
   * 自テーブルは全カラムを、関連テーブル（参照元・参照先）は描画する関連をつなぐカラムだけを箱に表示する。
   * 関連テーブルのカラムはチャンク単位の分割取得の対象外のため、
   * {@code boxes}が別途取得した関連カラムを用いる
   *
   * @param neighborhood 描く関連（描画距離以内のテーブルが持つ関連）
   */
  public static String erDiagram(
      TableEntity table,
      List<ColumnEntity> columns,
      DiagramNeighborhood neighborhood,
      DiagramBoxes boxes) {
    final StringBuilder sb = new StringBuilder("## ER図").append(LINE_SEPARATOR_DOUBLE);
    final List<ForeignKeyEntity> drawnRelations = neighborhood.relations();
    if (drawnRelations.isEmpty()) {
      return sb.append("関連するテーブルはありません。").append(LINE_SEPARATOR_DOUBLE).toString();
    }
    if (neighborhood.isShortened()) {
      sb.append(
              String.format(
                  "関連するテーブルが多く、%d段先までを描画するとテーブル数が上限（erDiagramMaxNodes）を超えるため、%d段先までを描画しています。",
                  neighborhood.requestedDistance(), neighborhood.distance()))
          .append(LINE_SEPARATOR_DOUBLE);
    }
    final TableKey selfKey = TableKey.of(table);
    final Set<TableKey> nodeKeys = new LinkedHashSet<>();
    nodeKeys.add(selfKey);
    drawnRelations.forEach(
        fk -> {
          nodeKeys.add(fk.referenceTableKey());
          nodeKeys.add(fk.tableKey());
        });
    final List<DiagramColumn> selfColumns = DiagramColumn.of(selfKey, columns, drawnRelations);
    final Map<TableKey, List<DiagramColumn>> relationColumns =
        boxes.relationColumnsOf(drawnRelations);
    return sb.append(
            MermaidSupport.erDiagramCodeBlock(
                nodeKeys,
                drawnRelations,
                boxes,
                key ->
                    key.equals(selfKey)
                        ? selfColumns
                        : relationColumns.getOrDefault(key, List.of())))
        .toString();
  }

  /**
   * 所属する観点セクション<br>
   * テーブル定義書から、当該テーブルが所属する観点のページへ戻る導線とする。所属する観点が無いテーブル
   * （観点を宣言していない場合を含む）では、セクションごと出力しない（観点を導入しても、所属しないテーブルの定義書は変わらない）
   *
   * @param viewpoints 当該テーブルが所属する観点のリスト（宣言順）
   * @return 所属する観点が無い場合は空文字
   */
  public static String viewpoints(List<Viewpoint> viewpoints, BaseInfoEntity baseInfo) {
    if (viewpoints.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder("## 所属する観点").append(LINE_SEPARATOR_DOUBLE);
    viewpoints.forEach(
        viewpoint ->
            sb.append(
                MarkdownTemplateSupport.linkListItem(
                    MarkdownTemplateSupport.escapeInline(viewpoint.name()),
                    DocumentLocations.linkFromDefinition(
                        DocumentLocations.viewpointFile(baseInfo.dbName(), viewpoint)))));
    return sb.append(LINE_SEPARATOR).toString();
  }

  /** テーブル一覧へ戻るリンクのフッター */
  public static String footer(BaseInfoEntity baseInfo) {
    return PagedSectionTemplates.backOnlyFooter(
        MarkdownTemplateSupport.listLinkFromDefinition(ListDocumentType.TABLE, baseInfo),
        ListDocumentType.TABLE.backLinkLabel());
  }

  /**
   * 行番号はリスト内での位置（1始まり）から採番する。
   * 当該テーブルへの絞り込みは{@link com.dbxray.domain.model.target.TableDefinitionContent#assemble}で済んでいる前提とする
   */
  private static <T> String tableSection(List<T> list, String header, RowRenderer<T> rowRenderer) {
    final StringBuilder sb = new StringBuilder(header);
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append(LINE_SEPARATOR);
      }
      sb.append(rowRenderer.render(i + 1, list.get(i)));
    }
    return sb.append(LINE_SEPARATOR_DOUBLE).toString();
  }
}
