package com.dbxray.domain.service.writer.objectlist;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.Sequences;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.schemaobject.Types;
import com.dbxray.domain.model.table.Triggers;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.DocumentLocations;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import com.dbxray.domain.service.writer.PagedSectionWriter;
import com.dbxray.domain.service.writer.PagedSectionWriter.PageLayout;
import com.dbxray.domain.service.writer.PagedSectionWriter.PagedSection;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** トリガー・関数/プロシージャ・シーケンス・ユーザー定義型の一覧および個別定義を書き込むクラス */
public class ObjectListWriter {

  private static final Logger logger = LogManager.getLogger(ObjectListWriter.class);
  private final FileRepository fileRepository;
  private final OutputPathResolver outputPathResolver;
  private final PagedSectionWriter pagedSectionWriter;

  @Inject
  public ObjectListWriter(
      FileRepository fileRepository,
      OutputPathResolver outputPathResolver,
      PagedSectionWriter pagedSectionWriter) {
    this.fileRepository = fileRepository;
    this.outputPathResolver = outputPathResolver;
    this.pagedSectionWriter = pagedSectionWriter;
  }

  /** トリガー一覧を書き込む */
  public void writeTriggerList(Triggers triggers, OutputRoot outputRoot) {
    writeObjectList(
        ListDocumentType.TRIGGER,
        ObjectListTemplates.triggerTableHeader(),
        triggers.asList(),
        ObjectListTemplates::triggerListLine,
        outputRoot);
  }

  /** 関数・プロシージャ一覧を書き込む */
  public void writeFunctionList(Functions functions, OutputRoot outputRoot) {
    writeObjectList(
        ListDocumentType.FUNCTION,
        ObjectListTemplates.functionTableHeader(),
        functions.asList(),
        ObjectListTemplates::functionListLine,
        outputRoot);
  }

  /** シーケンス一覧を書き込む */
  public void writeSequenceList(Sequences sequences, OutputRoot outputRoot) {
    writeObjectList(
        ListDocumentType.SEQUENCE,
        ObjectListTemplates.sequenceTableHeader(),
        sequences.asList(),
        ObjectListTemplates::sequenceListLine,
        outputRoot);
  }

  /** ユーザー定義型一覧を書き込む */
  public void writeTypeList(Types types, OutputRoot outputRoot) {
    writeObjectList(
        ListDocumentType.TYPE,
        ObjectListTemplates.typeTableHeader(),
        types.asList(),
        ObjectListTemplates::typeListLine,
        outputRoot);
  }

  /** 行数がMarkdownの表に表示できる最大件数を超える場合は、別ファイルへ分割する。 対象が存在しない一覧を出力しないことの判定は呼び出し側（出力する一覧の決定）が行う */
  private <T> void writeObjectList(
      ListDocumentType type,
      String tableHeader,
      List<T> objects,
      BiFunction<Integer, T, String> lineMapper,
      OutputRoot outputRoot) {
    final PagedSection<T> section =
        new PagedSection<>(type.getTitle(), tableHeader, objects, lineMapper);
    final PageLayout layout =
        new PageLayout(
            ObjectListTemplates.fileHeader(type.getTitle(), outputRoot.baseInfo()),
            outputPathResolver.resolveListFile(outputRoot, type),
            type.getBackLinkLabel());
    final String objectListSection = pagedSectionWriter.writePagedSection(section, layout);
    final List<String> contents =
        List.of(
            layout.fileHeader(), // ヘッダー
            ObjectListTemplates.baseInfo(outputRoot.baseInfo()), // 基本情報
            objectListSection, // 一覧
            ObjectListTemplates.footer(outputRoot.baseInfo()) // フッター
            );
    fileRepository.writeFile(layout.file(), contents);
  }

  /** 関数・プロシージャ1件分の個別定義を書き込む */
  public void writeFunctionDefinition(FunctionDefinitionContent content, OutputRoot outputRoot) {
    writeSchemaObjectDefinition(
        ListDocumentType.FUNCTION,
        content.function().schemaName(),
        DocumentLocations.functionDefinitionName(content.function()),
        ObjectDefinitionTemplates.functionFile(content, outputRoot.baseInfo()),
        outputRoot);
  }

  /** シーケンス1件分の個別定義を書き込む */
  public void writeSequenceDefinition(SequenceEntity sequence, OutputRoot outputRoot) {
    writeSchemaObjectDefinition(
        ListDocumentType.SEQUENCE,
        sequence.schemaName(),
        sequence.sequenceName(),
        ObjectDefinitionTemplates.sequenceFile(sequence, outputRoot.baseInfo()),
        outputRoot);
  }

  /** ユーザー定義型1件分の個別定義を書き込む */
  public void writeTypeDefinition(TypeEntity type, OutputRoot outputRoot) {
    writeSchemaObjectDefinition(
        ListDocumentType.TYPE,
        type.schemaName(),
        type.typeName(),
        ObjectDefinitionTemplates.typeFile(type, outputRoot.baseInfo()),
        outputRoot);
  }

  private void writeSchemaObjectDefinition(
      ListDocumentType kind,
      String schemaName,
      String name,
      String content,
      OutputRoot outputRoot) {
    final Path filePath =
        outputPathResolver.resolveSchemaObjectFile(outputRoot, schemaName, kind, name);
    fileRepository.createDirectory(
        outputPathResolver.resolveSchemaObjectDirectory(outputRoot, schemaName, kind));
    fileRepository.writeFile(filePath, List.of(content));
    logger.debug("exportSchemaObjectDefinition complete. [kind={}, filePath={}]", kind, filePath);
  }
}
