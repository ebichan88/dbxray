package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.PreviewFeature;
import com.dbxray.config.ConfigFile;
import com.dbxray.presentation.ExportSchemaController;
import com.dbxray.presentation.dto.DiffCheckResultDto;
import com.dbxray.testsupport.ExportBaseline;
import com.dbxray.testsupport.OracleSampleDatabase;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Oracleのサンプルデータベースに対してツール全体（設定の解釈→DIコンテナ→SQL→ドキュメント・スナップショットの出力）を通す結合テスト<br>
 * 出力を、コミット済みのベースライン（{@code docs/sample/oracle/output}）とファイル単位で完全一致するか比べる。
 * 出力仕様を意図して変えた場合は、verifyスキルの手順でベースラインを出力し直してコミットする
 */
@Tag("oracle")
class OracleSampleDatabaseExportIT {

  /** テストの作業ディレクトリはmodules/cli/のため、リポジトリルートのdocsは{@code ../../}で参照する */
  private static final Path BASELINE = Path.of("../../docs/sample/oracle/output");

  /** {@code --preview}で変わる・増えるファイルだけを置いた差分のベースライン */
  private static final Path PREVIEW_BASELINE = Path.of("../../docs/sample/oracle/output-preview");

  @Test
  @DisplayName("通常実行: 出力がベースラインと一致する")
  void testExportMatchesBaseline(@TempDir Path outputDir) {
    final ExportSchemaRequest request =
        properties(outputDir).toExportSchemaRequest(false, Set.of());

    controller().execute(request);

    ExportBaseline.assertMatches(BASELINE, outputDir);
  }

  @Test
  @DisplayName("--preview: 関数の定義書・参考情報が差分のベースラインと一致し、それ以外（スナップショットを含む）は既定の出力と同じ")
  void testPreviewExportMatchesBaseline(@TempDir Path outputDir) {
    final ExportSchemaRequest request =
        properties(outputDir).toExportSchemaRequest(false, EnumSet.allOf(PreviewFeature.class));

    controller().execute(request);

    ExportBaseline.assertMatches(BASELINE, PREVIEW_BASELINE, outputDir);
  }

  @Test
  @DisplayName("差分検知（--check）: DBとベースラインのスナップショットに差分が無い")
  void testCheckFindsNoDifferenceFromBaseline() {
    final CheckDocumentDiffRequest request = properties(BASELINE).toCheckDocumentDiffRequest();

    final DiffCheckResultDto result = controller().checkDiff(request);

    assertFalse(result.hasDifference(), result.resultMessage());
  }

  private static ExportSchemaController controller() {
    return ExportBaseline.controller(OracleSampleDatabase.sqlSessionFactory());
  }

  /**
   * verifyスキルの手順と同じ設定（{@code conf/config.yml}）を組み立てる
   *
   * @param outputPath 出力先
   * @return 検証済みの設定
   */
  private static DbxrayProperties properties(Path outputPath) {
    return DbxrayProperties.of(
        ConfigFile.parse(
            Path.of("config.yml"),
            """
            target:
              schemas: [%s]
            output:
              path: '%s'
            """
                .formatted(OracleSampleDatabase.SCHEMA, outputPath)));
  }
}
