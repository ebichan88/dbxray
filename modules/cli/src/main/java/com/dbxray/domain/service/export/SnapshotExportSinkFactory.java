package com.dbxray.domain.service.export;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.snapshot.SchemaSnapshotWriter;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;

/** スキーマのスナップショット（JSON Lines）を書き出す{@link ExportSink}を生成するクラス */
public class SnapshotExportSinkFactory {

  private final SchemaSnapshotWriter snapshotWriter;

  @Inject
  public SnapshotExportSinkFactory(SchemaSnapshotWriter snapshotWriter) {
    this.snapshotWriter = snapshotWriter;
  }

  /**
   * 指定したディレクトリへスキーマのスナップショットを書き出す{@link ExportSink}を生成するメソッド
   *
   * @param outputBaseDir 出力先のベースディレクトリパス
   * @return スキーマのスナップショットを書き出す{@link ExportSink}
   */
  public ExportSink create(Path outputBaseDir) {
    return new SnapshotExportSink(outputBaseDir);
  }

  /** 1回の出力先に紐づく、スキーマのスナップショットの{@link ExportSink}実装 */
  final class SnapshotExportSink implements ExportSink {

    private final Path outputBaseDir;

    SnapshotExportSink(Path outputBaseDir) {
      this.outputBaseDir = outputBaseDir;
    }

    /**
     * {@inheritDoc}<br>
     * DB全体の情報・シーケンス・型のスナップショットを書き出す
     */
    @Override
    public void writeOverview(ExportTargets targets) {
      final OutputRoot outputRoot = new OutputRoot(outputBaseDir, targets.baseInfo());
      snapshotWriter.writeDatabase(outputRoot);
      snapshotWriter.writeSequences(targets.sequences(), outputRoot);
      snapshotWriter.writeTypes(targets.types(), outputRoot);
    }

    /**
     * {@inheritDoc}<br>
     * 利用しているテーブルは定義本体から抽出した参考値のため、スナップショットには含めない
     */
    @Override
    public void writeFunctionDefinitions(
        String schemaName, List<FunctionDefinitionContent> contents, BaseInfoEntity baseInfo) {
      snapshotWriter.writeFunctions(
          schemaName,
          contents.stream().map(FunctionDefinitionContent::function).toList(),
          new OutputRoot(outputBaseDir, baseInfo));
    }

    /**
     * {@inheritDoc}<br>
     * テーブルはスキーマ単位のファイルへ1テーブルずつ追記するため、先に追記先を空の状態で用意する （前回実行時の内容へ追記されないようにするため）
     */
    @Override
    public void beginSchemaTables(String schemaName, BaseInfoEntity baseInfo) {
      snapshotWriter.initTableFile(schemaName, new OutputRoot(outputBaseDir, baseInfo));
    }

    /** {@inheritDoc} */
    @Override
    public void writeTableDefinition(TableDefinitionContent content) {
      snapshotWriter.appendTable(content, outputBaseDir);
    }
  }
}
