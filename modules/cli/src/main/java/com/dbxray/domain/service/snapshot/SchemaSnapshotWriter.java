package com.dbxray.domain.service.snapshot;

import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.snapshot.DatabaseSnapshot;
import com.dbxray.domain.model.snapshot.FunctionSnapshot;
import com.dbxray.domain.model.snapshot.SequenceSnapshot;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.model.snapshot.TableSnapshot;
import com.dbxray.domain.model.snapshot.TypeSnapshot;
import com.dbxray.domain.model.target.TableDefinitionContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * スキーマのスナップショット（DBから取得したスキーマ情報を構造化した中間表現）を書き込むクラス<br>
 * Markdownのテーブル定義書と同じ取得結果から、機械可読なJSON Lines形式（1行1オブジェクト）で出力する。
 * テーブルはチャンク単位の取得・破棄（{@code chunkSize}）のメモリプロファイルを変えないよう、1テーブル書き終えるごとにスキーマ単位のファイルへ追記する
 */
public class SchemaSnapshotWriter {

  /** JSON Linesの仕様に合わせ、OSに依らず行区切りはLFとする */
  private static final String LINE_SEPARATOR = "\n";

  private static final Logger logger = LogManager.getLogger(SchemaSnapshotWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final SnapshotSerializer serializer;

  @Inject
  public SchemaSnapshotWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      SnapshotSerializer serializer) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.serializer = serializer;
  }

  /** DB全体の情報（DB名等）のスナップショットを書き込む */
  public void writeDatabase(OutputRoot outputRoot) {
    write(
        outputPathResolver.resolveSnapshotDatabaseFile(outputRoot),
        List.of(DatabaseSnapshot.of(outputRoot.baseInfo())));
  }

  /** スキーマごとに1ファイルへ出力する。対象が存在しないスキーマのファイルは出力しない */
  public void writeSequences(Sequences sequences, OutputRoot outputRoot) {
    writeBySchema(
        sequences.asList(),
        SequenceEntity::schemaName,
        SequenceSnapshot::of,
        SnapshotKind.SEQUENCE,
        outputRoot);
  }

  /** スキーマごとに1ファイルへ出力する。対象が存在しないスキーマのファイルは出力しない */
  public void writeTypes(Types types, OutputRoot outputRoot) {
    writeBySchema(
        types.asList(), TypeEntity::schemaName, TypeSnapshot::of, SnapshotKind.TYPE, outputRoot);
  }

  /** 定義本体が大きくなり得るため、スキーマ単位で取得したものを受け取って1ファイルへ出力する */
  public void writeFunctions(
      String schemaName, List<FunctionEntity> functions, OutputRoot outputRoot) {
    write(
        outputPathResolver.resolveSnapshotFile(outputRoot, schemaName, SnapshotKind.FUNCTION),
        functions.stream().map(FunctionSnapshot::of).toList());
  }

  /** 前回実行時のファイルが残っている場合でも、その内容へ追記してしまわないよう上書きで空にする。当該スキーマの{@link #appendTable}より前に1回だけ呼び出すこと */
  public void initTableFile(String schemaName, OutputRoot outputRoot) {
    write(
        outputPathResolver.resolveSnapshotFile(outputRoot, schemaName, SnapshotKind.TABLE),
        List.of());
  }

  /** 追記先は{@link #initTableFile}で作成済みであること */
  public void appendTable(TableDefinitionContent content, OutputRoot outputRoot) {
    final Path filePath =
        outputPathResolver.resolveSnapshotFile(
            outputRoot, content.table().schemaName(), SnapshotKind.TABLE);
    fileRepository.appendFile(filePath, List.of(toLine(TableSnapshot.of(content))));
  }

  private <T> void writeBySchema(
      List<T> entities,
      Function<T, String> schemaNameGetter,
      Function<T, ?> toSnapshot,
      SnapshotKind kind,
      OutputRoot outputRoot) {
    entities.stream()
        .collect(Collectors.groupingBy(schemaNameGetter, LinkedHashMap::new, Collectors.toList()))
        .forEach(
            (schemaName, entitiesInSchema) ->
                write(
                    outputPathResolver.resolveSnapshotFile(outputRoot, schemaName, kind),
                    entitiesInSchema.stream().map(toSnapshot).toList()));
  }

  private void write(Path filePath, List<?> snapshots) {
    fileRepository.createDirectory(filePath.getParent());
    fileRepository.writeFile(filePath, snapshots.stream().map(this::toLine).toList());
    logger.debug("exportSchemaSnapshot complete. [filePath={}]", filePath);
  }

  private String toLine(Object snapshot) {
    return serializer.serialize(snapshot) + LINE_SEPARATOR;
  }
}
