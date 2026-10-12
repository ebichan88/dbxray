package com.dbxray.domain.service.path;

import com.dbxray.domain.model.snapshot.SnapshotKind;
import java.util.Arrays;
import java.util.Optional;

/**
 * スキーマのスナップショットの配置（ディレクトリ名・ファイル名と、出力ベースディレクトリからの相対パス）を一元的に定めるクラス<br>
 * Markdownドキュメントの配置を定める{@link DocumentLocations}と対になり、
 * 出力先の絶対パスの解決（{@link OutputPathResolver}）はこのクラスの規則を参照する。<br>
 * 配置は次のとおり。DB全体の情報は{@code snapshot/{DB名}/database.json}、
 * スキーマ配下のオブジェクトは種別ごとに{@code snapshot/{DB名}/{スキーマ名}/{種別のファイル名}.jsonl}に置く（DB名・スキーマ名は{@link PathSegments}で置き換える）
 */
public final class SnapshotLocations {

  /** スナップショット全体を置くディレクトリ名（出力ベースディレクトリ直下） */
  private static final String SNAPSHOT_DIRECTORY = "snapshot";

  /** DB全体の情報のファイル名 */
  private static final String DATABASE_FILE_NAME = "database.json";

  /** スキーマ配下のオブジェクトのファイル（JSON Lines）の拡張子 */
  private static final String OBJECT_FILE_EXTENSION = ".jsonl";

  private static final String PATH_SEPARATOR = "/";

  private SnapshotLocations() {}

  /**
   * スナップショット全体を置くディレクトリの、出力ベースディレクトリからの相対パスを取得するメソッド
   *
   * @return {@code snapshot}
   */
  public static String snapshotDirectory() {
    return SNAPSHOT_DIRECTORY;
  }

  /**
   * DB全体の情報のファイルの、スナップショットのディレクトリからの相対パスを取得するメソッド
   *
   * @return {@code {DB名}/database.json}
   */
  public static String databaseFile(String dbName) {
    return PathSegments.encode(dbName) + PATH_SEPARATOR + DATABASE_FILE_NAME;
  }

  /**
   * スキーマ配下のオブジェクトのファイルの、スナップショットのディレクトリからの相対パスを取得するメソッド
   *
   * @return {@code {DB名}/{スキーマ名}/{種別のファイル名}.jsonl}
   */
  public static String objectFile(String dbName, String schemaName, SnapshotKind kind) {
    return String.join(
        PATH_SEPARATOR,
        PathSegments.encode(dbName),
        PathSegments.encode(schemaName),
        objectFileName(kind));
  }

  /**
   * スナップショットのファイル名から、出力しているオブジェクトの種別を判定するメソッド<br>
   * {@link #objectFile}の逆変換。スナップショット同士の比較で、ファイルごとの比較方法を決めるために用いる
   *
   * @return オブジェクトの種別。スキーマ配下のオブジェクトのファイルでない場合（{@code database.json}等）は空
   */
  public static Optional<SnapshotKind> kindOf(String fileName) {
    return Arrays.stream(SnapshotKind.values())
        .filter(kind -> objectFileName(kind).equals(fileName))
        .findFirst();
  }

  /**
   * スキーマ配下のオブジェクトのファイル名を取得するメソッド
   *
   * @return {@code {種別のファイル名}.jsonl}
   */
  private static String objectFileName(SnapshotKind kind) {
    return kind.getFileName() + OBJECT_FILE_EXTENSION;
  }
}
