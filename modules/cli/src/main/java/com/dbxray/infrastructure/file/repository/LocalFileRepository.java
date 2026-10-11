package com.dbxray.infrastructure.file.repository;

import com.dbxray.domain.repository.FileRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/** ローカルファイルシステムに対するファイル操作のリポジトリ実装クラス */
public class LocalFileRepository implements FileRepository {

  /** {@inheritDoc} */
  @Override
  public void writeFile(Path filePath, List<String> contents) {
    write(filePath, contents);
  }

  /** {@inheritDoc} */
  @Override
  public void appendFile(Path filePath, List<String> contents) {
    write(filePath, contents, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * 内容をUTF-8で書き込むメソッド<br>
   * {@link Files#newBufferedWriter}はエンコードできない文字で例外を投げるため、従来どおり置換文字で書き込む {@link
   * OutputStreamWriter}を用いる
   *
   * @param options ファイルのオープン方法（未指定の場合は新規作成または上書き）
   */
  private void write(Path filePath, List<String> contents, OpenOption... options) {
    try (final BufferedWriter writer =
        new BufferedWriter(
            new OutputStreamWriter(
                Files.newOutputStream(filePath, options), StandardCharsets.UTF_8))) {
      for (final String content : contents) {
        writer.write(content);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void createDirectory(Path filePath) {
    try {
      Files.createDirectories(filePath);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isDirectory(Path path) {
    return Files.isDirectory(path);
  }

  /** {@inheritDoc} */
  @Override
  public List<Path> listFiles(Path directory) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var stream = Files.walk(directory)) {
      return stream.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<String> readFile(Path filePath) {
    try {
      return Files.readAllLines(filePath, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public Path createTempDirectory(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void deleteDirectory(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (var stream = Files.walk(directory)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void deleteFile(Path filePath) {
    try {
      Files.deleteIfExists(filePath);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
