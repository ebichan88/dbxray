package com.dbxray.domain.service.export;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.model.viewpoint.ViewpointContent;
import com.dbxray.domain.service.insight.InsightWriter;
import com.dbxray.domain.service.path.OutputRoot;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;

/** 参考情報（{@code insights}）を書き出す{@link ExportSink}を生成するクラス */
public class InsightExportSinkFactory {

  private final InsightWriter insightWriter;

  @Inject
  public InsightExportSinkFactory(InsightWriter insightWriter) {
    this.insightWriter = insightWriter;
  }

  /**
   * 指定したディレクトリへ参考情報を書き出す{@link ExportSink}を生成するメソッド
   *
   * @param outputBaseDir 出力先のベースディレクトリパス
   * @return 参考情報を書き出す{@link ExportSink}
   */
  public ExportSink create(Path outputBaseDir) {
    return new InsightExportSink(outputBaseDir);
  }

  /** 1回の出力先に紐づく、参考情報の{@link ExportSink}実装 */
  final class InsightExportSink implements ExportSink {

    private final Path outputBaseDir;

    InsightExportSink(Path outputBaseDir) {
      this.outputBaseDir = outputBaseDir;
    }

    /**
     * {@inheritDoc}<br>
     * 観点は一括取得済みのテーブル・関連だけから求まるため、ここで観点の参考情報を書き出す
     */
    @Override
    public void writeOverview(ExportTargets targets) {
      final OutputRoot outputRoot = new OutputRoot(outputBaseDir, targets.baseInfo());
      final List<ViewpointContent> contents =
          targets.viewpoints().asList().stream()
              .map(viewpoint -> viewpoint.resolve(targets.tables(), targets.foreignKeys()))
              .toList();
      insightWriter.writeViewpoints(contents, outputRoot);
    }

    /**
     * {@inheritDoc}<br>
     * 関数・プロシージャの利用しているテーブルの参考情報を、スキーマごとのファイルへ書き出す
     */
    @Override
    public void writeFunctionDefinitions(
        String schemaName, List<FunctionDefinitionContent> contents, BaseInfoEntity baseInfo) {
      insightWriter.writeFunctionTableUsages(
          schemaName, contents, new OutputRoot(outputBaseDir, baseInfo));
    }

    /**
     * {@inheritDoc}<br>
     * テーブルの詳細情報を使う参考情報は無いため、何もしない
     */
    @Override
    public void writeTableDefinition(TableDefinitionContent content) {}
  }
}
