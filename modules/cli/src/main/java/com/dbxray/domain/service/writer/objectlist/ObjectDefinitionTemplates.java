package com.dbxray.domain.service.writer.objectlist;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.domain.service.writer.template.PagedSectionTemplates;

/** 関数/プロシージャ・シーケンス・ユーザー定義型の個別定義書き込みに利用する Markdownのテンプレートを扱うクラス */
public final class ObjectDefinitionTemplates {

  private ObjectDefinitionTemplates() {}

  /** 基本情報セクション */
  private static String baseInfo(BaseInfoEntity baseInfo) {
    return MarkdownTemplateSupport.baseInfoSection(baseInfo);
  }

  /** 一覧へ戻るフッター */
  private static String footer(ListDocumentType listType, BaseInfoEntity baseInfo) {
    return PagedSectionTemplates.backOnlyFooter(
        MarkdownTemplateSupport.listLinkFromDefinition(listType, baseInfo),
        listType.backLinkLabel());
  }

  /** 関数・プロシージャの個別定義ファイル内容 */
  public static String functionFile(FunctionDefinitionContent content, BaseInfoEntity baseInfo) {
    final FunctionEntity function = content.function();
    return MarkdownTemplateSupport.fileHeader(function.functionName())
        + baseInfo(baseInfo)
        + FunctionTableUsageTemplates.section(content)
        + "## 定義"
        + LINE_SEPARATOR_DOUBLE
        + MarkdownTemplateSupport.sqlCodeBlock(function.definition())
        + footer(ListDocumentType.FUNCTION, baseInfo);
  }

  /** シーケンスの個別定義ファイル内容 */
  public static String sequenceFile(SequenceEntity sequence, BaseInfoEntity baseInfo) {
    final String properties =
        """
                ## シーケンス情報

                | 増分 | 最小値 | 最大値 | キャッシュ | 開始値 | 循環 | 所有カラム |
                |:---|:---|:---|:---|:---|:---|:---|
                """
            + MarkdownTemplateSupport.row(
                sequence.incrementBy(),
                sequence.minValue(),
                sequence.maxValue(),
                sequence.cacheSize(),
                sequence.startValue(),
                MarkdownTemplateSupport.marker(sequence.cycle()),
                sequence.ownedBy())
            + LINE_SEPARATOR_DOUBLE;
    return MarkdownTemplateSupport.fileHeader(sequence.sequenceName())
        + baseInfo(baseInfo)
        + properties
        + footer(ListDocumentType.SEQUENCE, baseInfo);
  }

  /** ユーザー定義型の個別定義ファイル内容 */
  public static String typeFile(TypeEntity type, BaseInfoEntity baseInfo) {
    final String definition =
        """
                ## 定義

                | 種別 | 定義 |
                |:---|:---|
                """
            + MarkdownTemplateSupport.row(
                type.typeCategory(), MarkdownTemplateSupport.escapePipe(type.definition()))
            + LINE_SEPARATOR_DOUBLE;
    return MarkdownTemplateSupport.fileHeader(type.typeName())
        + baseInfo(baseInfo)
        + definition
        + footer(ListDocumentType.TYPE, baseInfo);
  }
}
