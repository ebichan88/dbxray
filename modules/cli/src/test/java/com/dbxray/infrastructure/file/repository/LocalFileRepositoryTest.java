package com.dbxray.infrastructure.file.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LocalFileRepository のファイル読み書き・一覧取得・一時ディレクトリ操作・削除に関するテスト */
public class LocalFileRepositoryTest {

  private final LocalFileRepository repository = new LocalFileRepository();

  @Test
  @DisplayName("listFiles: ディレクトリ配下のファイルをサブディレクトリを含め再帰的に列挙する")
  void testListFilesRecursively(@TempDir Path dir) throws IOException {
    Files.createDirectories(dir.resolve("sub"));
    Files.writeString(dir.resolve("a.md"), "a", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("sub/b.md"), "b", StandardCharsets.UTF_8);

    List<Path> files = repository.listFiles(dir);

    assertEquals(List.of(dir.resolve("a.md"), dir.resolve("sub/b.md")), files);
  }

  @Test
  @DisplayName("listFiles: ディレクトリが存在しない場合は空リストを返す")
  void testListFilesReturnsEmptyWhenDirectoryDoesNotExist(@TempDir Path dir) {
    assertEquals(List.of(), repository.listFiles(dir.resolve("not-exist")));
  }

  @Test
  @DisplayName("readFile: ファイルの内容を行リストとして読み込む")
  void testReadFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("t1.md");
    Files.writeString(file, "line1\nline2\n", StandardCharsets.UTF_8);

    assertEquals(List.of("line1", "line2"), repository.readFile(file));
  }

  @Test
  @DisplayName("writeFile -> readFile: writeFileで書き込んだ内容がreadFileでそのまま読み込める")
  void testWriteThenReadRoundTrip(@TempDir Path dir) {
    Path file = dir.resolve("t2.md");
    repository.writeFile(file, List.of("line1\n", "line2\n"));

    assertEquals(List.of("line1", "line2"), repository.readFile(file));
  }

  @Test
  @DisplayName("appendFile: 既存ファイルの末尾へ追記し、ファイルが存在しない場合は新規作成する")
  void testAppendFile(@TempDir Path dir) {
    Path file = dir.resolve("t3.jsonl");
    repository.appendFile(file, List.of("line1\n"));
    repository.appendFile(file, List.of("line2\n", "line3\n"));

    assertEquals(List.of("line1", "line2", "line3"), repository.readFile(file));
  }

  @Test
  @DisplayName("writeFile: 既存ファイルは追記ではなく上書きされる")
  void testWriteFileOverwritesAppendedFile(@TempDir Path dir) {
    Path file = dir.resolve("t4.jsonl");
    repository.appendFile(file, List.of("stale\n"));
    repository.writeFile(file, List.of());

    assertEquals(List.of(), repository.readFile(file));
  }

  @Test
  @DisplayName("writeFile: マルチバイト文字をUTF-8で書き込む")
  void testWriteFileEncodesUtf8(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("t5.md");
    repository.writeFile(file, List.of("# 社員（employee）\n"));

    assertEquals("# 社員（employee）\n", Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("createTempDirectory: 指定した接頭辞を持つ、実在する一意なディレクトリを作成する")
  void testCreateTempDirectory() {
    Path tempDir = repository.createTempDirectory("dbxray-test-");
    try {
      assertTrue(Files.isDirectory(tempDir));
      assertTrue(tempDir.getFileName().toString().startsWith("dbxray-test-"));
    } finally {
      repository.deleteDirectory(tempDir);
    }
  }

  @Test
  @DisplayName("deleteDirectory: ディレクトリを配下のファイル・サブディレクトリごと再帰的に削除する")
  void testDeleteDirectoryRecursively(@TempDir Path parent) throws IOException {
    Path dir = parent.resolve("to-delete");
    Files.createDirectories(dir.resolve("sub"));
    Files.writeString(dir.resolve("a.md"), "a", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("sub/b.md"), "b", StandardCharsets.UTF_8);

    repository.deleteDirectory(dir);

    assertFalse(Files.exists(dir));
  }

  @Test
  @DisplayName("deleteDirectory: ディレクトリが存在しない場合は何もしない")
  void testDeleteDirectoryDoesNothingWhenDirectoryDoesNotExist(@TempDir Path dir) {
    assertDoesNotThrow(() -> repository.deleteDirectory(dir.resolve("not-exist")));
  }

  @Test
  @DisplayName("deleteFile: ファイルを1つ削除し、同じディレクトリの他のファイルは残す")
  void testDeleteFile(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("target.json");
    Path other = dir.resolve("other.json");
    Files.writeString(target, "x");
    Files.writeString(other, "y");

    repository.deleteFile(target);

    assertFalse(Files.exists(target));
    assertTrue(Files.exists(other));
  }

  @Test
  @DisplayName("deleteFile: ファイルが存在しない場合は何もしない")
  void testDeleteFileDoesNothingWhenFileDoesNotExist(@TempDir Path dir) {
    assertDoesNotThrow(() -> repository.deleteFile(dir.resolve("not-exist.json")));
  }

  @Test
  @DisplayName("deleteFile: ディレクトリは削除しない（空のディレクトリも残す）")
  void testDeleteFileKeepsDirectory(@TempDir Path dir) throws IOException {
    Path emptyDirectory = Files.createDirectory(dir.resolve("empty"));
    Path directory = Files.createDirectory(dir.resolve("withFile"));
    Path inside = Files.writeString(directory.resolve("inside.json"), "x");

    repository.deleteFile(emptyDirectory);
    repository.deleteFile(directory);

    assertTrue(Files.isDirectory(emptyDirectory));
    assertTrue(Files.exists(inside));
  }

  @Test
  @DisplayName("deleteFile: 親がファイルのパス（ディレクトリのはずの場所にファイルがある）でも何もしない")
  void testDeleteFileDoesNothingWhenParentIsFile(@TempDir Path dir) throws IOException {
    Path file = Files.writeString(dir.resolve("viewpoints.json"), "x");

    assertDoesNotThrow(() -> repository.deleteFile(file.resolve("functionTableUsages.json")));
    assertTrue(Files.exists(file));
  }
}
