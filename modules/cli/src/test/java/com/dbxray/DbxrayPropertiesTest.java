package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.DbxrayProperties.SettingOverride;
import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.PreviewFeature;
import com.dbxray.config.ConfigFile;
import com.dbxray.config.InvalidConfigurationException;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.target.OutputObjectType;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DbxrayProperties の設定ファイルの読み込み・検証（docs/usage/cli.mdに記載した設定項目の仕様）に関するテスト */
public class DbxrayPropertiesTest {

  private static final Path CONFIG_PATH = Path.of("conf", "config.yml");

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "");
  }

  private static ConfigFile config(String yaml) {
    return ConfigFile.parse(CONFIG_PATH, yaml);
  }

  private static ExportSchemaRequest request(String yaml) {
    return DbxrayProperties.of(config(yaml)).toExportSchemaRequest(false, false);
  }

  private static InvalidConfigurationException error(String yaml) {
    return assertThrows(
        InvalidConfigurationException.class, () -> DbxrayProperties.of(config(yaml)));
  }

  @Test
  @DisplayName("of: すべての項目が未指定の場合は既定値を用いる")
  void testOfUsesDefaultsWhenNothingSpecified() {
    ExportSchemaRequest request = request("");

    assertEquals(List.of(), request.targetSelection().tableScope().schemaNames());
    assertFalse(request.targetSelection().tableScope().isFiltered());
    assertEquals(
        EnumSet.allOf(OutputObjectType.class), request.targetSelection().outputObjectTypes());
    assertEquals("", request.sidecarPath());
    assertEquals("", request.outputPath());
    assertEquals(3000, request.chunkSize());
    assertEquals(80, request.erDiagramMaxNodes());
    assertEquals(1, request.erDiagramDistance());
  }

  @Test
  @DisplayName("of: キーを書かない場合と、キーだけ書いた場合・空のリスト・空白の値は、同じ「未指定」として扱う")
  void testOfTreatsBlankValueAsUnspecified() {
    ExportSchemaRequest omitted = request("");
    ExportSchemaRequest blank =
        request(
            """
            target:
              schemas: []
              tables:
              objects: []
            output:
              path: ""
              chunkSize:
              erDiagramMaxNodes: "  "
            annotations:
            """);

    assertEquals(
        omitted.targetSelection().tableScope().schemaNames(),
        blank.targetSelection().tableScope().schemaNames());
    assertFalse(blank.targetSelection().tableScope().isFiltered());
    assertEquals(
        omitted.targetSelection().outputObjectTypes(), blank.targetSelection().outputObjectTypes());
    assertEquals(omitted.sidecarPath(), blank.sidecarPath());
    assertEquals(omitted.outputPath(), blank.outputPath());
    assertEquals(omitted.chunkSize(), blank.chunkSize());
    assertEquals(omitted.erDiagramMaxNodes(), blank.erDiagramMaxNodes());
  }

  @Test
  @DisplayName("of: 指定した値を型へ変換し、前後の空白を除去する")
  void testOfParsesSpecifiedValues() {
    ExportSchemaRequest request =
        DbxrayProperties.of(
                config(
                    """
                    target:
                      schemas: [" sample "]
                      tables: ["!tmp_*"]
                      objects: [trigger, " function"]
                    output:
                      path: " ./docs/db "
                      chunkSize: 100
                      erDiagramMaxNodes: " 0 "
                      erDiagramDistance: 2
                    annotations: " conf/annotations.yml "
                    """))
            .toExportSchemaRequest(true, false);

    assertEquals(List.of("sample"), request.targetSelection().tableScope().schemaNames());
    assertFalse(request.targetSelection().tableScope().matches(table("sample", "tmp_work")));
    assertEquals("./docs/db", request.outputPath());
    assertEquals(100, request.chunkSize());
    assertEquals(0, request.erDiagramMaxNodes());
    assertEquals(2, request.erDiagramDistance());
    assertEquals(
        Set.of(OutputObjectType.TRIGGER, OutputObjectType.FUNCTION),
        request.targetSelection().outputObjectTypes());
    assertEquals("conf/annotations.yml", request.sidecarPath());
    assertTrue(request.rmDist());
    assertEquals(Set.of(), request.previewFeatures());
  }

  @Test
  @DisplayName("toExportSchemaRequest: --preview指定時は、プレビューの機能をすべて有効にする")
  void testToExportSchemaRequestWithPreview() {
    ExportSchemaRequest request =
        DbxrayProperties.of(config("output:\n  path: ./docs/db\n"))
            .toExportSchemaRequest(false, true);

    assertEquals(EnumSet.allOf(PreviewFeature.class), request.previewFeatures());
  }

  @Test
  @DisplayName("of: リストの項目は、YAMLのブロック形式でも書け、空要素を除く")
  void testOfReadsBlockStyleListAndSkipsEmptyElements() {
    ExportSchemaRequest request =
        request(
            """
            target:
              schemas:
                - alpha
                - ""
                -
                - beta
            """);

    assertEquals(List.of("alpha", "beta"), request.targetSelection().tableScope().schemaNames());
  }

  @Test
  @DisplayName("of: リストの項目に1つの値を書いた場合（カンマ区切りを含む）は、1つの名前として黙って扱わずに誤りとする")
  void testOfRejectsScalarForListSetting() {
    InvalidConfigurationException e =
        error(
            """
            target:
              schemas: public, sample
            """);

    assertTrue(e.getMessage().contains("target.schemas must be a list"));
  }

  @Test
  @DisplayName("of: 1つの値の項目にリストやキーと値の組を書いた場合は誤りとする")
  void testOfRejectsListForSingleValueSetting() {
    InvalidConfigurationException e =
        error(
            """
            output:
              path: [./a, ./b]
            annotations:
              file: conf/annotations.yml
            """);

    assertTrue(e.getMessage().contains("output.path must be a single value."));
    assertTrue(e.getMessage().contains("annotations must be a single value."));
  }

  @Test
  @DisplayName("of: セクションにキーと値の組でない値を書いた場合は誤りとする")
  void testOfRejectsSectionThatIsNotMapping() {
    InvalidConfigurationException e = error("output: ./docs\n");

    assertTrue(e.getMessage().contains("output must be a mapping of keys and values."));
  }

  @Test
  @DisplayName("of: 未知のキー（セクション内・最上位の書き誤り等）は、項目の位置で示し、書けるキーを添えて誤りとする")
  void testOfRejectsUnknownKey() {
    InvalidConfigurationException e =
        error(
            """
            output:
              paht: ./docs
            targets:
              schemas: [sample]
            """);

    assertTrue(e.getMessage().contains("conf"));
    assertTrue(e.getMessage().contains("config.yml"));
    assertTrue(e.getMessage().contains("Unknown key: output.paht, targets"));
    assertTrue(e.getMessage().contains("output.path"));
    assertTrue(e.getMessage().contains("target.schemas"));
  }

  @Test
  @DisplayName("of: databaseの中身は検証しない（DB接続情報の検証はConnectionSettingsが行う）")
  void testOfIgnoresDatabaseSection() {
    ExportSchemaRequest request =
        request(
            """
            database:
              url: jdbc:postgresql://localhost:5432/testdb
              unknown: value
            """);

    assertEquals(3000, request.chunkSize());
  }

  @Test
  @DisplayName("of: 整数として解釈できない値は、既定値へ置き換えずに誤りとする")
  void testOfRejectsNonIntegerValues() {
    InvalidConfigurationException e = error("output:\n  chunkSize: abc\n");

    assertTrue(e.getMessage().contains("output.chunkSize must be an integer: abc"));
  }

  @Test
  @DisplayName("of: 出力対象の条件として解釈できない値（未知の種別名・空のテーブル名）は誤りとする")
  void testOfRejectsInvalidTargetSelection() {
    InvalidConfigurationException e =
        error(
            """
            target:
              tables: [sample.]
              objects: [trigers]
            """);

    assertTrue(e.getMessage().contains("trigers"));
    assertTrue(e.getMessage().contains("sample."));
  }

  @Test
  @DisplayName("of: erDiagramDistanceは1〜3の整数だけを受け付け、範囲外は既定値へ置き換えずに誤りとする")
  void testOfRejectsErDiagramDistanceOutOfRange() {
    assertTrue(
        error("output:\n  erDiagramDistance: 0\n")
            .getMessage()
            .contains("output.erDiagramDistance must be between 1 and 3."));
    assertTrue(
        error("output:\n  erDiagramDistance: 4\n")
            .getMessage()
            .contains("output.erDiagramDistance must be between 1 and 3."));
    assertTrue(
        error("output:\n  erDiagramDistance: two\n")
            .getMessage()
            .contains("output.erDiagramDistance must be an integer"));
    assertEquals(3, request("output:\n  erDiagramDistance: 3\n").erDiagramDistance());
  }

  @Test
  @DisplayName("of: 複数の誤りは1件ずつではなく、まとめて報告する")
  void testOfReportsAllErrorsAtOnce() {
    InvalidConfigurationException e =
        error(
            """
            target:
              objects: [trigers]
            output:
              chunksize: 100
              erDiagramMaxNodes: 1.5
            """);

    assertTrue(e.getMessage().contains("Unknown key: output.chunksize"));
    assertTrue(e.getMessage().contains("output.erDiagramMaxNodes must be an integer: 1.5"));
    assertTrue(e.getMessage().contains("trigers"));
  }

  @Test
  @DisplayName("toCheckDocumentDiffRequest: 差分検知の入力へ、出力対象の条件・出力先・chunkSizeを渡す")
  void testToCheckDocumentDiffRequest() {
    DbxrayProperties properties =
        DbxrayProperties.of(
            config(
                """
                target:
                  schemas: [sample]
                output:
                  path: ./docs/db
                  chunkSize: 50
                """));

    CheckDocumentDiffRequest request = properties.toCheckDocumentDiffRequest();

    assertEquals(List.of("sample"), request.targetSelection().tableScope().schemaNames());
    assertEquals("./docs/db", request.outputPath());
    assertEquals(50, request.chunkSize());
  }

  @Test
  @DisplayName("of: 配布する設定ファイル（全項目が未指定）は、誤りなく既定値で読み込める")
  void testDistributedConfigFile() {
    ExportSchemaRequest request =
        DbxrayProperties.of(
                ConfigFile.load(Path.of("src", "main", "resources", "conf", "config.yml")))
            .toExportSchemaRequest(false, false);

    assertEquals(3000, request.chunkSize());
    assertEquals(80, request.erDiagramMaxNodes());
    assertEquals("", request.outputPath());
    assertEquals("", request.sidecarPath());
    assertFalse(request.targetSelection().tableScope().isFiltered());
  }

  @Test
  @DisplayName("of: CLI引数による上書き値は設定ファイルの値より優先し、上書きしない項目は設定ファイルの値を用いる")
  void testOfAppliesOverrides() {
    ExportSchemaRequest request =
        DbxrayProperties.of(
                config(
                    """
                    target:
                      schemas: [sample]
                    output:
                      path: ./docs/db
                      chunkSize: 100
                    """),
                Map.of(
                    "output.path", new SettingOverride("./docs/prod", "--output-path"),
                    "target.tables", new SettingOverride("!tmp_*", "--table")))
            .toExportSchemaRequest(false, false);

    assertEquals(List.of("sample"), request.targetSelection().tableScope().schemaNames());
    assertFalse(request.targetSelection().tableScope().matches(table("sample", "tmp_work")));
    assertEquals("./docs/prod", request.outputPath());
    assertEquals(100, request.chunkSize());
  }

  @Test
  @DisplayName("of: CLI引数でリストの項目を上書きする場合はカンマで区切り、各要素の前後の空白を除去して空要素を除く")
  void testOfSplitsCommaSeparatedOverride() {
    ExportSchemaRequest request =
        DbxrayProperties.of(
                config("target:\n  schemas: [other]\n"),
                Map.of(
                    "target.schemas", new SettingOverride("alpha,beta, gamma ,,delta", "--schema")))
            .toExportSchemaRequest(false, false);

    assertEquals(
        List.of("alpha", "beta", "gamma", "delta"),
        request.targetSelection().tableScope().schemaNames());
  }

  @Test
  @DisplayName("of: 上書き値も設定ファイルの値と同じ仕様で検証し、誤りの報告に上書きの指定元を添える")
  void testOfValidatesOverriddenValues() {
    InvalidConfigurationException e =
        assertThrows(
            InvalidConfigurationException.class,
            () ->
                DbxrayProperties.of(
                    config("output:\n  chunkSize: 100\n"),
                    Map.of(
                        "output.chunkSize", new SettingOverride("abc", "--chunk-size"),
                        "target.objects", new SettingOverride("trigers", "--output-objects"))));

    assertTrue(e.getMessage().contains("output.chunkSize must be an integer: abc"));
    assertTrue(e.getMessage().contains("trigers"));
    assertTrue(e.getMessage().contains("overridden by "));
    assertTrue(e.getMessage().contains("--chunk-size"));
    assertTrue(e.getMessage().contains("--output-objects"));
  }

  @Test
  @DisplayName("of: 上書きしていない場合は、誤りの報告に上書きの指定元を添えない")
  void testOfDoesNotMentionOverridesWhenNothingOverridden() {
    InvalidConfigurationException e = error("output:\n  chunkSize: abc\n");

    assertTrue(e.getMessage().startsWith("Invalid configuration in " + CONFIG_PATH + "."));
    assertFalse(e.getMessage().contains("overridden by"));
  }

  @Test
  @DisplayName("SETTINGS: CLI引数名は項目ごとに一意で、項目の位置も一意")
  void testSettingsHaveUniqueNames() {
    List<String> cliNames =
        DbxrayProperties.SETTINGS.stream().map(DbxrayProperties.Setting::cliName).toList();
    List<String> paths =
        DbxrayProperties.SETTINGS.stream().map(DbxrayProperties.Setting::path).toList();

    assertEquals(cliNames.size(), Set.copyOf(cliNames).size());
    assertEquals(paths.size(), Set.copyOf(paths).size());
  }
}
