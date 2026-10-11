package com.dbxray.domain.service.export;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.relation.NodeLimit;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.erdiagram.ErDiagramWriter;
import com.dbxray.domain.service.writer.objectlist.ObjectListWriter;
import com.dbxray.domain.service.writer.readme.ReadmeWriter;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionWriter;
import com.dbxray.domain.service.writer.viewpoint.ViewpointWriter;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Markdownのドキュメント（テーブル一覧・テーブル定義書・ER図・各種一覧と個別定義・観点・README）を書き出す{@link ExportSink}を生成するクラス */
public class MarkdownExportSinkFactory {

  private final TableDefinitionWriter tableDefinitionWriter;
  private final ErDiagramWriter erDiagramWriter;
  private final ObjectListWriter objectListWriter;
  private final ViewpointWriter viewpointWriter;
  private final ReadmeWriter readmeWriter;

  @Inject
  public MarkdownExportSinkFactory(
      TableDefinitionWriter tableDefinitionWriter,
      ErDiagramWriter erDiagramWriter,
      ObjectListWriter objectListWriter,
      ViewpointWriter viewpointWriter,
      ReadmeWriter readmeWriter) {
    this.tableDefinitionWriter = tableDefinitionWriter;
    this.erDiagramWriter = erDiagramWriter;
    this.objectListWriter = objectListWriter;
    this.viewpointWriter = viewpointWriter;
    this.readmeWriter = readmeWriter;
  }

  /**
   * 指定したディレクトリへMarkdownのドキュメントを書き出す{@link ExportSink}を生成するメソッド
   *
   * @param outputBaseDir 出力先のベースディレクトリパス
   * @param erDiagramLimit ER図1枚に描画するノード数の上限
   * @param tableDiagramDistance テーブル定義書のER図に描く関連の距離（段数。1以上）
   * @param diagramBoxes ER図のテーブルの箱に表示する内容
   * @param foreignKeys 出力対象のテーブル同士の関連（テーブル定義書のER図で、描画距離以内の関連をたどるために使う）
   * @return Markdownのドキュメントを書き出す{@link ExportSink}
   */
  public ExportSink create(
      Path outputBaseDir,
      NodeLimit erDiagramLimit,
      int tableDiagramDistance,
      DiagramBoxes diagramBoxes,
      ForeignKeys foreignKeys) {
    return new MarkdownExportSink(
        outputBaseDir, erDiagramLimit, tableDiagramDistance, diagramBoxes, foreignKeys);
  }

  /**
   * 出力する一覧ドキュメントの種別を決めるメソッド<br>
   * テーブル一覧は常に出力し、それ以外の一覧は対象が1件以上存在する場合のみ出力する（空の一覧は出力しない）。
   * 一覧の書き出しと、テーブル一覧に掲載する関連ドキュメントへのリンクの双方がこの結果を用いるため、両者が食い違わない
   *
   * @param targets 一括取得した出力対象の情報
   * @return 出力する一覧ドキュメントの種別の集合
   */
  private static Set<ListDocumentType> listDocuments(ExportTargets targets) {
    final Set<ListDocumentType> documents = EnumSet.of(ListDocumentType.TABLE);
    if (!targets.tables().isEmpty()) {
      documents.add(ListDocumentType.ER_DIAGRAM);
    }
    if (!targets.functions().isEmpty()) {
      documents.add(ListDocumentType.FUNCTION);
    }
    if (!targets.sequences().isEmpty()) {
      documents.add(ListDocumentType.SEQUENCE);
    }
    if (!targets.types().isEmpty()) {
      documents.add(ListDocumentType.TYPE);
    }
    if (!targets.triggers().isEmpty()) {
      documents.add(ListDocumentType.TRIGGER);
    }
    if (!targets.viewpoints().isEmpty()) {
      documents.add(ListDocumentType.VIEWPOINT);
    }
    return documents;
  }

  /**
   * テーブル一覧に掲載する関連ドキュメント（テーブル一覧以外の一覧へのリンク）を決めるメソッド
   *
   * @param documents 出力する一覧ドキュメントの種別の集合
   * @return リンクを掲載する一覧の種別（掲載順＝{@link ListDocumentType}の宣言順）
   */
  private static List<ListDocumentType> relatedDocuments(Set<ListDocumentType> documents) {
    return documents.stream().filter(type -> type != ListDocumentType.TABLE).toList();
  }

  /** 1回の出力先・設定に紐づく、Markdownのドキュメントの{@link ExportSink}実装 */
  final class MarkdownExportSink implements ExportSink {

    private final Path outputBaseDir;
    private final NodeLimit erDiagramLimit;
    private final int tableDiagramDistance;
    private final DiagramBoxes diagramBoxes;
    private final ForeignKeys foreignKeys;

    MarkdownExportSink(
        Path outputBaseDir,
        NodeLimit erDiagramLimit,
        int tableDiagramDistance,
        DiagramBoxes diagramBoxes,
        ForeignKeys foreignKeys) {
      this.outputBaseDir = outputBaseDir;
      this.erDiagramLimit = erDiagramLimit;
      this.tableDiagramDistance = tableDiagramDistance;
      this.diagramBoxes = diagramBoxes;
      this.foreignKeys = foreignKeys;
    }

    /**
     * {@inheritDoc}<br>
     * テーブル一覧・ER図・各種一覧・観点・シーケンス/型の個別定義を書き出す。ER図はテーブル一覧・外部キー一覧と、
     * 別途取得した関連カラムのみで生成できるため、テーブル詳細をチャンク単位で取得する前のこの時点で書き出せる。 READMEはカラムの数を含む集計を載せるため、{@link
     * #writeSummary}で書き出す
     */
    @Override
    public void writeOverview(ExportTargets targets) {
      final OutputRoot outputRoot = new OutputRoot(outputBaseDir, targets.baseInfo());
      final Set<ListDocumentType> documents = listDocuments(targets);
      tableDefinitionWriter.writeTableDefinitionList(
          targets.tables(), outputRoot, relatedDocuments(documents));
      if (documents.contains(ListDocumentType.ER_DIAGRAM)) {
        erDiagramWriter.writeErDiagram(
            targets.tables(), targets.foreignKeys(), diagramBoxes, outputRoot, erDiagramLimit);
      }
      if (documents.contains(ListDocumentType.TRIGGER)) {
        objectListWriter.writeTriggerList(targets.triggers(), outputRoot);
      }
      if (documents.contains(ListDocumentType.FUNCTION)) {
        objectListWriter.writeFunctionList(targets.functions(), outputRoot);
      }
      if (documents.contains(ListDocumentType.SEQUENCE)) {
        objectListWriter.writeSequenceList(targets.sequences(), outputRoot);
      }
      if (documents.contains(ListDocumentType.TYPE)) {
        objectListWriter.writeTypeList(targets.types(), outputRoot);
      }
      if (documents.contains(ListDocumentType.VIEWPOINT)) {
        viewpointWriter.writeViewpoints(
            targets.viewpoints(),
            targets.tables(),
            targets.foreignKeys(),
            diagramBoxes,
            outputRoot,
            erDiagramLimit);
      }
      // シーケンス・型の個別ファイル出力（情報が小さいため一覧取得結果をそのまま利用する）
      targets
          .sequences()
          .forEach(sequence -> objectListWriter.writeSequenceDefinition(sequence, outputRoot));
      targets.types().forEach(type -> objectListWriter.writeTypeDefinition(type, outputRoot));
    }

    /** {@inheritDoc} */
    @Override
    public void writeFunctionDefinitions(
        String schemaName, List<FunctionDefinitionContent> contents, BaseInfoEntity baseInfo) {
      final OutputRoot outputRoot = new OutputRoot(outputBaseDir, baseInfo);
      contents.forEach(content -> objectListWriter.writeFunctionDefinition(content, outputRoot));
    }

    /** {@inheritDoc} */
    @Override
    public void writeTableDefinition(TableDefinitionContent content) {
      tableDefinitionWriter.writeTableDefinition(
          content,
          foreignKeys.neighborhoodOf(content.table(), tableDiagramDistance, erDiagramLimit),
          diagramBoxes,
          outputBaseDir);
    }

    /**
     * {@inheritDoc}<br>
     * 一覧ドキュメントへのリンクと集計を載せたREADMEを書き出す
     */
    @Override
    public void writeSummary(ExportTargets targets, DatabaseMetrics metrics) {
      readmeWriter.writeReadme(
          listDocuments(targets), metrics, new OutputRoot(outputBaseDir, targets.baseInfo()));
    }
  }
}
