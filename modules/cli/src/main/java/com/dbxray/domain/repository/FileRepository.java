package com.dbxray.domain.repository;

import java.nio.file.Path;
import java.util.List;

/** ファイル操作に関するリポジトリインターフェース */
public interface FileRepository {
  /** ファイルに書き込むメソッド */
  public void writeFile(Path filePath, List<String> contents);

  /**
   * ファイルの末尾へ追記するメソッド<br>
   * ファイルが存在しない場合は新規作成する
   */
  public void appendFile(Path filePath, List<String> contents);

  /** ディレクトリを作成するメソッド */
  public void createDirectory(Path filePath);

  /**
   * パスにファイルまたはディレクトリが存在するか判定するメソッド
   *
   * @return 存在する場合はtrue
   */
  public boolean exists(Path path);

  /**
   * パスがディレクトリか判定するメソッド
   *
   * @return ディレクトリの場合はtrue（存在しない場合はfalse）
   */
  public boolean isDirectory(Path path);

  /**
   * ディレクトリ配下のファイルを再帰的に列挙するメソッド
   *
   * @return ディレクトリ配下に存在する全ファイルのパスのリスト。ディレクトリが存在しない場合は空リスト
   */
  public List<Path> listFiles(Path directory);

  /**
   * ファイルを行リストとして読み込むメソッド（{@link #writeFile(Path, List)}の逆）
   *
   * @return ファイルの内容を1行ずつ格納したリスト
   */
  public List<String> readFile(Path filePath);

  /**
   * 一時ディレクトリを作成するメソッド
   *
   * @return 作成した一時ディレクトリのパス
   */
  public Path createTempDirectory(String prefix);

  /**
   * ディレクトリを配下のファイルごと再帰的に削除するメソッド<br>
   * ディレクトリが存在しない場合は何もしない
   */
  public void deleteDirectory(Path directory);

  /**
   * ファイルを1つ削除するメソッド<br>
   * 通常のファイルでない場合（存在しない・ディレクトリ）は何もしない。ディレクトリの削除には{@link #deleteDirectory}を使う
   */
  public void deleteFile(Path filePath);
}
