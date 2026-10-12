package com.dbxray.domain.service.writer.readme;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.metrics.SchemaMetrics;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** データベース単位ディレクトリのREADME書き込みに利用するMarkdownのテンプレートを扱うクラス */
public class ReadmeTemplates {

  /** 全スキーマの合計の行の、スキーマ名の欄の表示（スキーマ名と区別するため括弧で囲む） */
  private static final String TOTAL_LABEL = "（合計）";

  /** READMEのファイルヘッダー */
  public static String fileHeader(BaseInfoEntity baseInfo) {
    return "# "
        + MarkdownTemplateSupport.escapeInline(String.format("%s ドキュメント一覧", baseInfo.dbName()))
        + LINE_SEPARATOR_DOUBLE;
  }

  /** 基本情報セクション */
  public static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /**
   * ドキュメント一覧セクション<br>
   * 出力される一覧ドキュメント（テーブル一覧・ER図一覧・観点一覧・トリガー/関数/シーケンス/型の一覧のうち、対象が存在するもの）へのリンクを列挙する。個別のテーブル定義書・個別定義書へは、
   * それぞれの一覧ドキュメントを経由してたどれるため、ここには一覧のみを掲載する
   *
   * @param documents 出力する一覧ドキュメントの種別（掲載順）
   */
  public static String documentLinks(BaseInfoEntity baseInfo, Set<ListDocumentType> documents) {
    final StringBuilder sb = new StringBuilder("## ドキュメント一覧").append(LINE_SEPARATOR_DOUBLE);
    documents.forEach(
        type ->
            sb.append(
                    String.format(
                        "* [%s](%s)  ",
                        type.getTitle(),
                        DocumentLocations.linkFromDatabaseRoot(
                            DocumentLocations.listFile(type, baseInfo.dbName()))))
                .append(LINE_SEPARATOR));
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * 集計セクション<br>
   * 出力対象のオブジェクトの数・論理名の記述状況・関連と読み解きの状況を、スキーマごとの表で示す。スキーマが2つ以上の場合は合計の行を足す。
   * 取得しなかった種別（{@code target.objects}で外した種別）の列は、0件と区別できるよう列ごと省く。
   * 集計の対象が1つも無い場合は空文字を返す
   */
  public static String metrics(DatabaseMetrics metrics) {
    if (metrics.isEmpty()) {
      return "";
    }
    final List<SchemaMetrics> rows = new ArrayList<>(metrics.schemas());
    if (rows.size() > 1) {
      rows.add(metrics.total());
    }
    return "## 集計"
        + LINE_SEPARATOR_DOUBLE
        + "出力対象（設定の`target`で絞り込んだ範囲）の数で、DB全体の数ではありません。"
        + "「テーブル・ビュー」にはマテリアライズドビューを含みます。"
        + LINE_SEPARATOR_DOUBLE
        + "### オブジェクトの数"
        + LINE_SEPARATOR_DOUBLE
        + table(objectCountColumns(metrics), rows)
        + "### 論理名の記述状況"
        + LINE_SEPARATOR_DOUBLE
        + "論理名（DBのコメント）を持つものの数／全体の数です。"
        + LINE_SEPARATOR_DOUBLE
        + table(
            List.of(
                new Column("テーブル・ビュー", m -> ratio(m.tablesWithLogicalName(), m.allTables())),
                new Column("カラム", m -> ratio(m.columnsWithLogicalName(), m.columns()))),
            rows)
        + "### 関連と読み解きの状況"
        + LINE_SEPARATOR_DOUBLE
        + "外部キー・論理リレーションは参照元のテーブルのスキーマで数えます。"
        + "「関連を持たない」は、外部キー・論理リレーションのどちらの端にもならないものです。"
        + "観点・説明・備考はサイドカーYAMLで宣言・記述したものです。"
        + LINE_SEPARATOR_DOUBLE
        + table(
            List.of(
                new Column("外部キー", m -> String.valueOf(m.foreignKeys())),
                new Column("論理リレーション", m -> String.valueOf(m.logicalRelations())),
                new Column("関連を持たないテーブル・ビュー", m -> ratio(m.unrelatedTables(), m.allTables())),
                new Column("観点に所属するテーブル・ビュー", m -> ratio(m.viewpointTables(), m.allTables())),
                new Column("説明・備考を補ったテーブル・ビュー", m -> ratio(m.annotatedTables(), m.allTables()))),
            rows);
  }

  /** オブジェクトの数の表の列（取得しなかった種別の列は含まない） */
  private static List<Column> objectCountColumns(DatabaseMetrics metrics) {
    final List<Column> columns =
        new ArrayList<>(
            List.of(
                new Column("テーブル", m -> String.valueOf(m.tables())),
                new Column("うちパーティション表", m -> String.valueOf(m.partitionedTables())),
                new Column("ビュー", m -> String.valueOf(m.views())),
                new Column("マテリアライズドビュー", m -> String.valueOf(m.materializedViews())),
                new Column("カラム", m -> String.valueOf(m.columns()))));
    if (metrics.isCounted(OutputObjectType.FUNCTION)) {
      columns.add(new Column("関数", m -> String.valueOf(m.functions())));
      columns.add(new Column("プロシージャ", m -> String.valueOf(m.procedures())));
    }
    if (metrics.isCounted(OutputObjectType.SEQUENCE)) {
      columns.add(new Column("シーケンス", m -> String.valueOf(m.sequences())));
    }
    if (metrics.isCounted(OutputObjectType.TYPE)) {
      columns.add(new Column("ユーザー定義型", m -> String.valueOf(m.types())));
    }
    if (metrics.isCounted(OutputObjectType.TRIGGER)) {
      columns.add(new Column("トリガー", m -> String.valueOf(m.triggers())));
    }
    return columns;
  }

  /** 先頭にスキーマ名の列を置き、数の列を右寄せにした表 */
  private static String table(List<Column> columns, List<SchemaMetrics> rows) {
    final StringBuilder sb = new StringBuilder("| スキーマ名 |");
    columns.forEach(column -> sb.append(' ').append(column.header()).append(" |"));
    sb.append(LINE_SEPARATOR).append("|:---|");
    columns.forEach(column -> sb.append("---:|"));
    sb.append(LINE_SEPARATOR);
    for (final SchemaMetrics row : rows) {
      final List<Object> cells = new ArrayList<>();
      cells.add(
          row.schemaName().isEmpty()
              ? TOTAL_LABEL
              : MarkdownTemplateSupport.escapeTableCell(row.schemaName()));
      columns.forEach(column -> cells.add(column.value().apply(row)));
      sb.append(MarkdownTemplateSupport.row(cells.toArray())).append(LINE_SEPARATOR);
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /** 「該当する数 / 全体の数」の表示（割合・良し悪しの判定は付けない） */
  private static String ratio(int count, int total) {
    return count + " / " + total;
  }

  /**
   * 集計の表の1列
   *
   * @param value 1スキーマ分（または合計）の集計から、セルの値を求める関数
   */
  private record Column(String header, Function<SchemaMetrics, String> value) {}
}
