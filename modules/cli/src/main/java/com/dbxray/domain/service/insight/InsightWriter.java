package com.dbxray.domain.service.insight;

import com.dbxray.domain.model.insight.FunctionTableUsagesInsight;
import com.dbxray.domain.model.insight.ViewpointsInsight;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.snapshot.SnapshotSerializer;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 参考情報（{@code insights}。スナップショットの事実とは別にAIへ渡す情報）を書き込むクラス<br>
 * {@code --check}の比較対象ではないため、スナップショットとは別の出力先（{@link
 * com.dbxray.domain.service.path.InsightLocations}）へ書き出す
 */
public class InsightWriter {

  /** JSON Linesの他のファイルと合わせ、OSに依らず行区切りはLFとする */
  private static final String LINE_SEPARATOR = "\n";

  private static final Logger logger = LogManager.getLogger(InsightWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final SnapshotSerializer serializer;

  @Inject
  public InsightWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      SnapshotSerializer serializer) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.serializer = serializer;
  }

  /**
   * 観点の参考情報を書き込むメソッド<br>
   * 観点を宣言していない場合（{@code contents}が空）は、ファイル自体を出力しない
   *
   * @param contents 出力対象の観点ごとの出力内容（{@code Viewpoint.resolve}で求めたもの）
   */
  public void writeViewpoints(List<ViewpointContent> contents, OutputRoot root) {
    if (contents.isEmpty()) {
      return;
    }
    write(outputPathResolver.resolveViewpointsInsightFile(root), ViewpointsInsight.of(contents));
  }

  /**
   * 1スキーマ分の関数・プロシージャの利用しているテーブルの参考情報を書き込むメソッド<br>
   * 抽出を行っていない実行（プレビューの機能を有効にしていない実行）では、ファイルを出力せず、以前のプレビューの実行が出力したファイルを削除する。
   * 残すと、定義本体やテーブルが変わった後の抽出結果を、MCPサーバーがスナップショットの最新の内容に対するものとして返してしまうため。 抽出を行った関数・プロシージャだけを出力する
   *
   * @param contents 当該スキーマの関数・プロシージャの出力内容
   */
  public void writeFunctionTableUsages(
      String schemaName, List<FunctionDefinitionContent> contents, OutputRoot root) {
    final Path filePath =
        outputPathResolver.resolveFunctionTableUsagesInsightFile(root, schemaName);
    final List<FunctionDefinitionContent> analyzed =
        contents.stream().filter(content -> content.tableUsage().isAttempted()).toList();
    if (analyzed.isEmpty()) {
      deletePrevious(filePath);
      return;
    }
    write(filePath, FunctionTableUsagesInsight.of(root.baseInfo().dbName(), schemaName, analyzed));
  }

  private void deletePrevious(Path filePath) {
    if (fileRepository.exists(filePath)) {
      fileRepository.deleteFile(filePath);
      logger.debug("Deleted the insight of a previous preview run. [filePath={}]", filePath);
    }
  }

  private void write(Path filePath, Object insight) {
    fileRepository.createDirectory(filePath.getParent());
    fileRepository.writeFile(filePath, List.of(serializer.serialize(insight) + LINE_SEPARATOR));
    logger.debug("exportInsight complete. [filePath={}]", filePath);
  }
}
