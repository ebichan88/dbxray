package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.PreviewFeature;
import com.dbxray.config.ConfigFile;
import com.dbxray.presentation.ExportSchemaController;
import com.dbxray.presentation.dto.DiffCheckResultDto;
import com.dbxray.testsupport.ExportBaseline;
import com.dbxray.testsupport.SampleDatabase;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * サンプルDBに対してツール全体（設定の解釈→DIコンテナ→SQL→ドキュメント・スナップショットの出力）を通す結合テスト<br>
 * 出力を、コミット済みのベースライン（{@code docs/sample/postgres/output}。verifyスキルの手順で出力したもの）と
 * ファイル単位で完全一致するか比べる。SQL・DTOからの変換・テンプレートのどこが変わっても検知できる。
 * 出力仕様を意図して変えた場合は、verifyスキルの手順でベースラインを出力し直してコミットする
 */
class SampleDatabaseExportIT {

  /** ベースライン（verifyスキルの手順で出力したもの）。テストの作業ディレクトリはmodules/cli/のため、リポジトリルートのdocsは{@code ../../}で参照する */
  private static final Path BASELINE = Path.of("../../docs/sample/postgres/output");

  /** {@code --preview}で変わる・増えるファイルだけを置いた差分のベースライン（verifyスキルの手順で出力したもの） */
  private static final Path PREVIEW_BASELINE = Path.of("../../docs/sample/postgres/output-preview");

  /** verifyスキルの手順で指定しているサイドカーYAML */
  private static final String ANNOTATION_PATH = "../../docs/sample/postgres/annotations.sample.yml";

  @ParameterizedTest(name = "chunkSize={0}")
  @ValueSource(strings = {"", "1"})
  @DisplayName("通常実行: 出力がベースラインと一致する（詳細情報の分割取得の単位を変えても出力は変わらない）")
  void testExportMatchesBaseline(String chunkSize, @TempDir Path outputDir) {
    final ExportSchemaRequest request =
        properties(outputDir, chunkSize).toExportSchemaRequest(false, Set.of());

    controller().execute(request);

    ExportBaseline.assertMatches(BASELINE, outputDir);
  }

  @Test
  @DisplayName("--preview: 関数の定義書・参考情報が差分のベースラインと一致し、それ以外（スナップショットを含む）は既定の出力と同じ")
  void testPreviewExportMatchesBaseline(@TempDir Path outputDir) {
    final ExportSchemaRequest request =
        properties(outputDir, "").toExportSchemaRequest(false, EnumSet.allOf(PreviewFeature.class));

    controller().execute(request);

    ExportBaseline.assertMatches(BASELINE, PREVIEW_BASELINE, outputDir);
  }

  @Test
  @DisplayName("差分検知（--check）: DBとベースラインのスナップショットに差分が無い")
  void testCheckFindsNoDifferenceFromBaseline() {
    final CheckDocumentDiffRequest request = properties(BASELINE, "").toCheckDocumentDiffRequest();

    final DiffCheckResultDto result = controller().checkDiff(request);

    assertFalse(result.hasDifference(), result.resultMessage());
  }

  private static ExportSchemaController controller() {
    return ExportBaseline.controller(SampleDatabase.sqlSessionFactory());
  }

  /**
   * verifyスキルの手順と同じ設定（{@code conf/config.yml}）を組み立てる
   *
   * @param outputPath 出力先
   * @param chunkSize {@code output.chunkSize}の値（空の場合は既定値）
   * @return 検証済みの設定
   */
  private static DbxrayProperties properties(Path outputPath, String chunkSize) {
    return DbxrayProperties.of(
        ConfigFile.parse(
            Path.of("config.yml"),
            """
            target:
              schemas: [sample]
            output:
              path: '%s'
              chunkSize: %s
            annotations: '%s'
            """
                .formatted(outputPath, chunkSize, ANNOTATION_PATH)),
        Map.of());
  }
}
