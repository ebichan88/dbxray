package com.dbxray.testsupport;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.config.module.DatabaseDependentModule;
import com.dbxray.config.module.DbxrayModule;
import com.dbxray.infrastructure.db.DatabaseTypeDetector;
import com.dbxray.presentation.ExportSchemaController;
import com.google.inject.Guice;
import com.google.inject.util.Modules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.apache.ibatis.session.SqlSessionFactory;

/** サンプルDBに対してツール全体を通し、出力をコミット済みのベースラインと比べる結合テストの共通部品 */
public final class ExportBaseline {

  /** 基本情報の作成日（実行日。ベースラインを出力した日と異なるため、比較の前に置き換える） */
  private static final String CREATED_DATE_PATTERN = "\\|\\d{4}/\\d{2}/\\d{2}\\|";

  private static final String CREATED_DATE_PLACEHOLDER = "|<作成日>|";

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);

  private ExportBaseline() {}

  /**
   * エントリーポイントと同じ手順（DB種別に依存しない部品のコンテナに、接続後に子のコンテナを足す）でコントローラーを取得するメソッド
   *
   * @param sqlSessionFactory 接続先のサンプルDB
   * @return 生成日を固定したコントローラー
   */
  public static ExportSchemaController controller(SqlSessionFactory sqlSessionFactory) {
    return Guice.createInjector(
            Modules.override(new DbxrayModule())
                .with(binder -> binder.bind(Clock.class).toInstance(FIXED_CLOCK)))
        .createChildInjector(
            new DatabaseDependentModule(
                DatabaseTypeDetector.detect(sqlSessionFactory), sqlSessionFactory))
        .getInstance(ExportSchemaController.class);
  }

  /**
   * 出力したファイルの一覧と各ファイルの内容（作成日を除く）が、ベースラインと完全に一致することを確かめるメソッド
   *
   * @param baseline ベースラインのディレクトリ
   * @param outputDir 出力先のディレクトリ
   */
  public static void assertMatches(Path baseline, Path outputDir) {
    assertEquals(listFiles(baseline), listFiles(outputDir), "出力されるファイルの一覧");
    for (final Path file : listFiles(baseline)) {
      assertEquals(read(baseline.resolve(file)), read(outputDir.resolve(file)), "ファイルの内容: " + file);
    }
  }

  /**
   * プレビューの機能を有効にした出力を、既定のベースラインに差分のベースラインを重ねたものと比べるメソッド<br>
   * 差分のベースラインには、プレビューの機能で内容が変わる・増えるファイルだけを置く（既定の出力と重複して持たないため）
   *
   * @param baseline 既定（プレビューの機能を有効にしない）のベースラインのディレクトリ
   * @param overlay プレビューの機能で変わる・増えるファイルだけを置いたディレクトリ
   * @param outputDir 出力先のディレクトリ
   */
  public static void assertMatches(Path baseline, Path overlay, Path outputDir) {
    final List<Path> expectedFiles =
        Stream.concat(listFiles(baseline).stream(), listFiles(overlay).stream())
            .distinct()
            .sorted()
            .toList();
    assertEquals(expectedFiles, listFiles(outputDir), "出力されるファイルの一覧");
    for (final Path file : expectedFiles) {
      final Path expected =
          Files.isRegularFile(overlay.resolve(file))
              ? overlay.resolve(file)
              : baseline.resolve(file);
      assertEquals(read(expected), read(outputDir.resolve(file)), "ファイルの内容: " + file);
    }
  }

  private static List<Path> listFiles(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths.filter(Files::isRegularFile).map(directory::relativize).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8)
          .replaceAll(CREATED_DATE_PATTERN, CREATED_DATE_PLACEHOLDER);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
