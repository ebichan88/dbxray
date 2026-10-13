package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.application.TargetSelection;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.infrastructure.path.DefaultOutputPathResolver;
import com.dbxray.shared.exception.UserCorrectableException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OutputDirectoryValidator の出力先（outputPath）の検証に関するテスト */
public class OutputDirectoryValidatorTest {

  /** 既存のファイル・ディレクトリをメモリ上に持つFileRepositoryのスタブ（パスの状態の問い合わせだけに応じる） */
  private static class PathStateFileRepository implements FileRepository {
    private final Set<Path> files = new HashSet<>();
    private final Set<Path> directories = new HashSet<>();

    @Override
    public boolean exists(Path path) {
      return files.contains(path) || directories.contains(path);
    }

    @Override
    public boolean isDirectory(Path path) {
      return directories.contains(path);
    }

    @Override
    public void writeFile(Path filePath, List<String> contents) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void appendFile(Path filePath, List<String> contents) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void createDirectory(Path filePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Path> listFiles(Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> readFile(Path filePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Path createTempDirectory(String prefix) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteDirectory(Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(Path filePath) {
      throw new UnsupportedOperationException();
    }
  }

  private final PathStateFileRepository fileRepository = new PathStateFileRepository();
  private final OutputDirectoryValidator validator =
      new OutputDirectoryValidator(new DefaultOutputPathResolver(), fileRepository);

  private static ExportSchemaRequest exportRequest(String outputPath, boolean rmDist) {
    return new ExportSchemaRequest(
        TargetSelection.of(List.of(), List.of(), List.of()),
        null,
        outputPath,
        0,
        80,
        1,
        rmDist,
        Set.of());
  }

  private static CheckDocumentDiffRequest checkDiffRequest(String outputPath) {
    return new CheckDocumentDiffRequest(
        TargetSelection.of(List.of(), List.of(), List.of()), null, outputPath, 0);
  }

  @Test
  @DisplayName("出力先が存在しない場合は、書き込み時に作成するため誤りとしない（--rm-distの有無・--checkによらない）")
  void testAcceptsAbsentOutputPath() {
    assertDoesNotThrow(() -> validator.validate(exportRequest("./docs/db", false)));
    assertDoesNotThrow(() -> validator.validate(exportRequest("./docs/db", true)));
    assertDoesNotThrow(() -> validator.validate(checkDiffRequest("./docs/db")));
  }

  @Test
  @DisplayName("出力先が既存のディレクトリの場合は誤りとしない")
  void testAcceptsExistingDirectory() {
    fileRepository.directories.add(Path.of("./docs/db"));

    assertDoesNotThrow(() -> validator.validate(exportRequest("./docs/db", true)));
    assertDoesNotThrow(() -> validator.validate(checkDiffRequest("./docs/db")));
  }

  @Test
  @DisplayName("出力先が既存のファイルを指す場合は、--rm-distの有無によらず利用者が直せる誤りとする（ファイルを削除させないため）")
  void testRejectsExistingFileForExport() {
    fileRepository.files.add(Path.of("./README.md"));

    for (final boolean rmDist : new boolean[] {false, true}) {
      final UserCorrectableException e =
          assertThrows(
              UserCorrectableException.class,
              () -> validator.validate(exportRequest("./README.md", rmDist)));
      assertTrue(e.getMessage().startsWith("output.path points to an existing file"));
      assertTrue(
          e.getMessage().contains(Path.of("./README.md").toAbsolutePath().normalize().toString()));
    }
  }

  @Test
  @DisplayName("--checkでも、比較対象の出力先が既存のファイルを指す場合は利用者が直せる誤りとする")
  void testRejectsExistingFileForCheck() {
    fileRepository.files.add(Path.of("./README.md"));

    assertThrows(
        UserCorrectableException.class, () -> validator.validate(checkDiffRequest("./README.md")));
  }

  @Test
  @DisplayName("--rm-dist指定時に、出力先がカレントディレクトリ自体に解決される場合は削除を拒否する")
  void testRefusesRmDistForCurrentDirectory() {
    fileRepository.directories.add(Path.of("."));

    final UserCorrectableException e =
        assertThrows(
            UserCorrectableException.class, () -> validator.validate(exportRequest(".", true)));
    assertTrue(e.getMessage().startsWith("Refusing to run --rm-dist"));
  }

  @Test
  @DisplayName("--rm-distを指定しなければ、出力先がカレントディレクトリ自体でも誤りとしない")
  void testAcceptsCurrentDirectoryWithoutRmDist() {
    fileRepository.directories.add(Path.of("."));

    assertDoesNotThrow(() -> validator.validate(exportRequest(".", false)));
  }
}
