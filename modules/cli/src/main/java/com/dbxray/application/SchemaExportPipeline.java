package com.dbxray.application;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.Sidecar;
import com.dbxray.domain.model.table.Partitions;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.table.ViewReferences;
import com.dbxray.domain.model.target.ConsistencyNotice;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.model.target.TableScope;
import com.dbxray.domain.repository.CatalogRepository;
import com.dbxray.domain.repository.SidecarRepository;
import com.dbxray.domain.service.export.ExportSink;
import com.dbxray.domain.service.tableusage.FunctionTableUsageAnalyzer;
import com.dbxray.domain.service.target.ExportTargetConsistency;
import com.dbxray.domain.service.target.ExportTargetConsistency.ResolvedForeignKeys;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DBからスキーマ情報を取得し、出力形式ごとの{@link ExportSink}へ書き出す段取りを担うクラス<br>
 * DBドキュメント出力（通常実行）と差分検知（{@code --check}）の双方で共通の処理で、両者の違いは書き出し先の{@link ExportSink}のみ。
 * 対象範囲全体を一度にメモリへ載せないよう、軽量な情報は一括取得し（{@link #fetchTargets}）、
 * テーブル数に比例して重くなる情報はスキーマ・チャンク単位で取得・書き出し・破棄する（{@link #export}）。
 * 取得した情報同士の突き合わせは{@link ExportTargetConsistency}に委ねる
 */
final class SchemaExportPipeline {

  private static final Logger logger = LogManager.getLogger(SchemaExportPipeline.class);
  private final CatalogRepository repository;
  private final SidecarRepository sidecarRepository;
  private final ExportTargetConsistency consistency;
  private final Clock clock;

  /**
   * @param clock ドキュメントの生成日を決める時計
   */
  @Inject
  SchemaExportPipeline(
      CatalogRepository repository,
      SidecarRepository sidecarRepository,
      ExportTargetConsistency consistency,
      Clock clock) {
    this.repository = repository;
    this.sidecarRepository = sidecarRepository;
    this.consistency = consistency;
    this.clock = clock;
  }

  /**
   * 出力対象のうち、一括取得する軽量な情報（基本情報・テーブル一覧・外部キー・トリガー・パーティション・関数/シーケンス/型の一覧・手動付帯情報）を取得するメソッド<br>
   * テーブル数に比例して重くなる詳細情報（カラム・インデックス・制約）と関数の定義本体は、出力時（{@link #export}）にスキーマ・チャンク単位で取得する
   */
  ExportTargets fetchTargets(TargetSelection targetSelection, String sidecarPath) {
    final TableScope tableScope = targetSelection.tableScope();
    final List<String> schemaNames = tableScope.schemaNames();
    final boolean isFiltered = tableScope.isFiltered();
    final Set<OutputObjectType> outputObjectTypes = targetSelection.outputObjectTypes();
    final Sidecar sidecar = sidecarRepository.load(sidecarPath);
    final Annotations annotations = sidecar.annotations();

    final BaseInfoEntity baseInfoEntity =
        BaseInfoEntity.of(repository.selectDatabase(), LocalDate.now(clock));
    final Tables tables = fetchTables(tableScope);
    if (tables.isEmpty()) {
      // 0件でも失敗にはしない（テーブルの無いスキーマもありうる）が、パターンの書き誤りや権限不足で
      // 何も出力されないまま成功と表示されるのに気付けるよう警告する
      logger.warn(
          "No table or view matched the output target. Check the schema and table patterns "
              + "(target in the configuration file, --schema and --table) and that the database user "
              + "can see the tables.");
    }
    report(consistency.findOrphanTableAnnotations(annotations, tables, isFiltered));
    report(consistency.findUnmatchedViewpointPatterns(sidecar.viewpoints(), tables, isFiltered));
    final ForeignKeys foreignKeys =
        fetchForeignKeys(schemaNames, sidecar.logicalRelations(), tables, isFiltered);
    final Triggers triggers = fetchTriggers(schemaNames, outputObjectTypes);
    final Partitions partitions = Partitions.of(repository.selectPartitionList(schemaNames));
    final ViewReferences viewReferences = fetchViewReferences(schemaNames, tables);
    final SchemaObjects schemaObjects = fetchSchemaObjects(schemaNames, outputObjectTypes);

    return new ExportTargets(
        baseInfoEntity,
        tables,
        foreignKeys,
        triggers,
        partitions,
        viewReferences,
        schemaObjects.functions(),
        schemaObjects.sequences(),
        schemaObjects.types(),
        annotations,
        sidecar.viewpoints(),
        outputObjectTypes);
  }

  /**
   * 基本情報・テーブル一覧（1テーブル1行の軽量情報）を取得するメソッド<br>
   * スキーマの絞り込みのみSQLで行い、テーブル単位の絞り込みは{@link TableScope#matches}でJava側で行う
   * （テーブル名パターンはワイルドカード・除外・スキーマ修飾に対応しており、SQLの完全一致IN句では表現できないため）。
   * ここで絞り込んでおくことで、以降のテーブル一覧・ER図・詳細情報取得はすべて対象テーブルのみを扱う
   */
  private Tables fetchTables(TableScope tableScope) {
    return Tables.of(
        repository.selectTableList(tableScope.schemaNames()).stream()
            .filter(tableScope::matches)
            .toList());
  }

  /**
   * 出力対象のビューが参照するテーブルを取得するメソッド<br>
   * テーブルの定義書から参照しているビューを引けるよう、外部キーと同じく対象範囲全体を一括取得する。
   * 出力対象外のビュー（定義書を出力しないビュー）の参照は除く。参照されるテーブルは、出力対象外のものも事実として残す
   */
  private ViewReferences fetchViewReferences(List<String> schemaNames, Tables tables) {
    return ViewReferences.of(
        repository.selectViewReferenceList(schemaNames).stream()
            .filter(reference -> tables.contains(reference.tableKey()))
            .toList());
  }

  /**
   * 物理外部キーとサイドカー由来の論理リレーションを取得し、出力対象のテーブル同士のものへ絞り込むメソッド<br>
   * 外部キーは制約数に比例する軽量な情報のため、テーブルと異なりチャンク化せず対象範囲全体を一括取得する。
   * ER図で他チャンク・他スキーマのテーブルから自テーブルが参照されている関係も正しく解決するには、特定のチャンクに限定せず全件を保持しておく必要があるため。
   * 絞り込みの基準は{@link ExportTargetConsistency#resolveForeignKeys}を参照
   */
  private ForeignKeys fetchForeignKeys(
      List<String> schemaNames,
      List<ForeignKeyEntity> logicalRelations,
      Tables tables,
      boolean isFiltered) {
    final ResolvedForeignKeys resolvedForeignKeys =
        consistency.resolveForeignKeys(
            repository.selectForeignKeyList(schemaNames), logicalRelations, tables, isFiltered);
    report(resolvedForeignKeys.consistencyNotices());
    return resolvedForeignKeys.foreignKeys();
  }

  /**
   * トリガーを取得するメソッド<br>
   * テーブルに属する軽量な情報のため、外部キーと同様にチャンク化せず対象範囲全体を一括取得し、テーブル定義書内のセクションとトリガー一覧の両方で利用する。
   * {@code target.objects}で外した場合は取得自体を行わず、一覧・テーブル定義書双方から除外する
   */
  private Triggers fetchTriggers(
      List<String> schemaNames, Set<OutputObjectType> outputObjectTypes) {
    return Triggers.of(
        outputObjectTypes.contains(OutputObjectType.TRIGGER)
            ? repository.selectTriggerList(schemaNames)
            : List.of());
  }

  /**
   * スキーマレベルのオブジェクト（関数/シーケンス/型）を取得するメソッド<br>
   * テーブルフィルタの対象外で、スキーマフィルタのみ適用する。
   * 関数一覧は定義本体を含まない軽量情報のみ取得する（定義本体はスキーマ単位で{@link #exportFunctionDefinitions}が別途取得する）。{@link #fetchTriggers}と同様、
   * {@code target.objects}で外した種別は取得自体を行わない（一覧・個別定義とも出力されなくなる）
   */
  private SchemaObjects fetchSchemaObjects(
      List<String> schemaNames, Set<OutputObjectType> outputObjectTypes) {
    final List<FunctionEntity> functions =
        outputObjectTypes.contains(OutputObjectType.FUNCTION)
            ? repository.selectFunctionList(schemaNames)
            : List.of();
    final List<SequenceEntity> sequences =
        outputObjectTypes.contains(OutputObjectType.SEQUENCE)
            ? repository.selectSequenceList(schemaNames)
            : List.of();
    final List<TypeEntity> types =
        outputObjectTypes.contains(OutputObjectType.TYPE)
            ? repository.selectTypeList(schemaNames)
            : List.of();
    return new SchemaObjects(Functions.of(functions), Sequences.of(sequences), Types.of(types));
  }

  /** {@link #fetchSchemaObjects}の取得結果 */
  private record SchemaObjects(Functions functions, Sequences sequences, Types types) {}

  /**
   * ER図のテーブルの箱に表示する内容（論理テーブル名・関連カラム）を求めるメソッド<br>
   * 関連カラムを持つテーブルのカラムをスキーマ・チャンク単位で取得し、関連カラムだけを残して他は破棄する。
   * Markdownの描画でのみ用いるため、差分検知（{@code --check}）では取得しない
   *
   * @param chunkSize 1回の取得でまとめて処理するテーブル数の上限。0以下の場合はスキーマ単位で分割しない
   */
  DiagramBoxes fetchDiagramBoxes(ExportTargets targets, int chunkSize) {
    final DiagramBoxes.Builder builder =
        DiagramBoxes.builder(targets.tables(), targets.foreignKeys());
    final Map<String, List<TableKey>> tableKeysBySchema =
        builder.tablesNeedingColumns().stream()
            .collect(
                Collectors.groupingBy(TableKey::schema, LinkedHashMap::new, Collectors.toList()));
    tableKeysBySchema
        .values()
        .forEach(
            tableKeys -> {
              for (final List<TableKey> chunk : splitIntoChunks(tableKeys, chunkSize)) {
                builder.collectRelatedColumns(repository.selectColumnList(chunk));
              }
            });
    return builder.build();
  }

  /**
   * リストを{@code chunkSize}件ずつの塊に分割するメソッド<br>
   * {@code chunkSize}が0以下の場合は分割せず全体を1つの塊とする。空のリストは塊を持たない。
   * 返す塊は元のリストのビューのため、元のリストを変更してはならない
   */
  private static <T> List<List<T>> splitIntoChunks(List<T> list, int chunkSize) {
    final int total = list.size();
    final int step = chunkSize > 0 ? chunkSize : total;
    final List<List<T>> chunks = new ArrayList<>();
    for (int from = 0; from < total; from += step) {
      chunks.add(list.subList(from, Math.min(from + step, total)));
    }
    return chunks;
  }

  /**
   * 一括取得した情報をもとに、指定された出力形式で書き出すメソッド<br>
   * 一括取得した情報から出力できるもの（一覧・ER図等）を先に書き出し、その後に関数の定義本体をスキーマ単位で、
   * テーブルの詳細情報をスキーマ・チャンク単位で取得・書き出し・破棄する。最後に、取得の途中で数えた集計を書き出す。
   * どの形式で書き出す場合も取得処理は共通
   *
   * @param chunkSize 1回の取得でまとめて処理するテーブル数の上限。0以下の場合は分割しない
   * @param previewFeatures 有効にするプレビューの機能
   */
  void export(
      ExportTargets targets,
      List<ExportSink> sinks,
      int chunkSize,
      Set<PreviewFeature> previewFeatures) {
    sinks.forEach(sink -> sink.writeOverview(targets));
    exportFunctionDefinitions(targets, sinks, previewFeatures);

    // カラム・インデックス・制約は、スキーマ内でさらにchunkSize件ずつに分割して取得・出力・破棄する。
    // これにより、テーブルが1スキーマに集中していても、同時にメモリ保持する詳細情報を最大chunkSize件分に抑える
    final DatabaseMetrics.Builder metrics = DatabaseMetrics.builder(targets);
    targets
        .tables()
        .bySchema()
        .forEach(
            (schemaName, tablesInSchema) ->
                exportSchemaTableDefinitions(
                    schemaName, tablesInSchema, targets, chunkSize, sinks, metrics));
    final DatabaseMetrics summary = metrics.build();
    sinks.forEach(sink -> sink.writeSummary(targets, summary));
    logger.info(
        "Exported schema objects. [tables={}, functions={}, sequences={}, types={}, triggers={}]",
        targets.tables().asList().size(),
        targets.functions().asList().size(),
        targets.sequences().asList().size(),
        targets.types().asList().size(),
        targets.triggers().asList().size());
  }

  /**
   * 関数・プロシージャの個別定義を書き出すメソッド<br>
   * 定義本体は大きくなり得るため、スキーマ単位で取得・出力・破棄し、同時にメモリ保持する定義本体を抑える。
   * 利用しているテーブルを抽出する（プレビューの機能）場合、出力対象のテーブルの索引は全スキーマで1回だけ求める
   */
  private void exportFunctionDefinitions(
      ExportTargets targets, List<ExportSink> sinks, Set<PreviewFeature> previewFeatures) {
    final boolean extractsTableUsage =
        previewFeatures.contains(PreviewFeature.FUNCTION_TABLE_USAGE);
    final Map<String, List<TableEntity>> tablesByName =
        extractsTableUsage ? targets.tables().byTableName() : Map.of();
    for (final String schemaName : targets.functions().schemaNames()) {
      final List<FunctionEntity> functions = repository.selectFunctionDefList(List.of(schemaName));
      final List<FunctionDefinitionContent> contents =
          extractsTableUsage
              ? withTableUsage(functions, targets, tablesByName)
              : functions.stream().map(FunctionDefinitionContent::withoutTableUsage).toList();
      sinks.forEach(
          sink -> sink.writeFunctionDefinitions(schemaName, contents, targets.baseInfo()));
    }
  }

  /**
   * 1スキーマ分の関数・プロシージャから、利用しているテーブルを抽出した出力内容を作るメソッド<br>
   * 解析器は、Oracleのパッケージの字句をスキーマの処理の間だけ使い回すため、スキーマごとに作る
   *
   * @param tablesByName 出力対象のテーブルをテーブル名でまとめたもの（{@link Tables#byTableName()}）
   */
  private static List<FunctionDefinitionContent> withTableUsage(
      List<FunctionEntity> functions,
      ExportTargets targets,
      Map<String, List<TableEntity>> tablesByName) {
    final FunctionTableUsageAnalyzer analyzer =
        FunctionTableUsageAnalyzer.of(targets.tables(), tablesByName, targets.baseInfo().dbms());
    return functions.stream()
        .map(function -> new FunctionDefinitionContent(function, analyzer.analyze(function)))
        .toList();
  }

  /**
   * @param tablesInSchema 当該スキーマに属するテーブルのリスト（呼び出し元で絞り込み済み）
   */
  private void exportSchemaTableDefinitions(
      String schemaName,
      List<TableEntity> tablesInSchema,
      ExportTargets targets,
      int chunkSize,
      List<ExportSink> sinks,
      DatabaseMetrics.Builder metrics) {
    sinks.forEach(sink -> sink.beginSchemaTables(schemaName, targets.baseInfo()));
    for (final List<TableEntity> chunk : splitIntoChunks(tablesInSchema, chunkSize)) {
      exportTableDefinitionChunk(chunk, targets, sinks, metrics);
    }
  }

  /**
   * 当該チャンクのテーブルの詳細情報（カラム・インデックス・制約）のみを取得し、出力後にローカル変数のスコープを抜けることでメモリ解放対象とする
   *
   * @param chunk 1チャンク分のテーブルのリスト（同一スキーマ。呼び出し元で絞り込み済み）
   */
  private void exportTableDefinitionChunk(
      List<TableEntity> chunk,
      ExportTargets targets,
      List<ExportSink> sinks,
      DatabaseMetrics.Builder metrics) {
    for (final TableDetail detail : repository.selectTableDetails(chunk)) {
      report(consistency.findOrphanColumnAnnotations(detail, targets.annotations()));
      metrics.collectColumns(detail);
      final TableDefinitionContent content =
          TableDefinitionContent.assemble(
              targets.baseInfo(),
              detail,
              targets.foreignKeys(),
              targets.triggers(),
              targets.partitions(),
              targets.viewReferences(),
              targets.annotations(),
              targets.viewpoints());
      sinks.forEach(sink -> sink.writeTableDefinition(content));
    }
  }

  private static void report(List<ConsistencyNotice> consistencyNotices) {
    consistencyNotices.forEach(
        notice -> {
          switch (notice.severity()) {
            case INFO -> logger.info(notice.message());
            case WARN -> logger.warn(notice.message());
          }
        });
  }
}
