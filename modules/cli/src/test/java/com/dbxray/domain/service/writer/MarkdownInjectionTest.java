package com.dbxray.domain.service.writer;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.relation.Cardinality;
import com.dbxray.domain.model.relation.DiagramNeighborhood;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.RelationType;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.SequenceEntity;
import com.dbxray.domain.model.schemaobject.TypeEntity;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.ConstraintEntity;
import com.dbxray.domain.model.table.IndexEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.TriggerEntity;
import com.dbxray.domain.model.table.ViewReferenceEntity;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.SchemaResolution;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.OutputObjectType;
import com.dbxray.domain.service.writer.erdiagram.ErDiagramTemplates;
import com.dbxray.domain.service.writer.objectlist.ObjectDefinitionTemplates;
import com.dbxray.domain.service.writer.objectlist.ObjectListTemplates;
import com.dbxray.domain.service.writer.readme.ReadmeTemplates;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionListTemplates;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionTemplates;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import com.dbxray.testsupport.DiagramBoxesFixtures;
import com.dbxray.testsupport.ExportTargetsFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * DBの名前・コメント・定義に、HTMLやMarkdownの構文を混ぜても、出力したMarkdownに生のHTMLが現れないことのテスト<br>
 * テンプレートを追加・変更した場合は、DB由来の文字列を受け取る箇所を{@link #renderedDocuments}に足すこと
 */
public class MarkdownInjectionTest {

  /** 生のHTML・コードブロックを閉じる行・見出しを混ぜた、DB由来の文字列 */
  private static final String HOSTILE =
      "x<img src=x onerror=alert(1)>\n```\n<script>alert(2)</script>\n# h|z";

  private static final BaseInfoEntity BASE_INFO =
      new BaseInfoEntity(HOSTILE, Dbms.POSTGRESQL, 16, LocalDate.EPOCH);

  /** 表のセルの改行（{@code <br>}）以外の、生のHTMLの開始 */
  private static final Pattern RAW_HTML = Pattern.compile("<(?!br>)");

  /** コードブロックの囲みの行（CommonMarkでは行頭の空白3個まで許される） */
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,})(.*)$");

  @TestFactory
  @DisplayName("DB由来の文字列にHTML・コードブロックの囲みを混ぜても、コードブロックの外に生のHTMLが現れない")
  Stream<DynamicTest> testNoRawHtmlOutsideCodeBlocks() {
    return renderedDocuments().entrySet().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey(), () -> assertNoRawHtml(entry.getKey(), entry.getValue().get())));
  }

  private static Map<String, Supplier<String>> renderedDocuments() {
    final TableEntity table =
        new TableEntity(HOSTILE, HOSTILE, HOSTILE, HOSTILE, TableType.VIEW, HOSTILE);
    final ColumnEntity column =
        new ColumnEntity(HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, true, true, HOSTILE);
    final ForeignKeyEntity fk =
        new ForeignKeyEntity(
            HOSTILE,
            HOSTILE,
            HOSTILE,
            List.of(HOSTILE),
            "other",
            "other",
            List.of(HOSTILE),
            Cardinality.ONE_TO_MANY,
            RelationType.PHYSICAL);
    final TriggerEntity trigger =
        new TriggerEntity(
            HOSTILE,
            HOSTILE,
            HOSTILE,
            HOSTILE,
            List.of(HOSTILE),
            HOSTILE,
            HOSTILE,
            HOSTILE,
            HOSTILE);
    final FunctionEntity function =
        new FunctionEntity(
            HOSTILE, HOSTILE, HOSTILE, 1, 1, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE);
    final SequenceEntity sequence =
        new SequenceEntity(
            HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, true, HOSTILE);
    final TypeEntity type = new TypeEntity(HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE);
    final TableAnnotation annotation = TableAnnotation.EMPTY;

    final Map<String, Supplier<String>> documents = new LinkedHashMap<>();
    documents.put(
        "titledFileHeader", () -> MarkdownTemplateSupport.titledFileHeader("t", BASE_INFO));
    documents.put("baseInfoSection", () -> MarkdownTemplateSupport.baseInfoSection(BASE_INFO));
    documents.put("table.fileHeader", () -> TableDefinitionTemplates.fileHeader(table));
    documents.put("table.tableInfo", () -> TableDefinitionTemplates.tableInfo(table, annotation));
    documents.put(
        "table.columns", () -> TableDefinitionTemplates.columns(List.of(column), annotation));
    documents.put("table.view", () -> TableDefinitionTemplates.view(table));
    final ViewReferenceEntity viewReference =
        new ViewReferenceEntity(
            HOSTILE, HOSTILE, TableType.VIEW, HOSTILE, HOSTILE, TableType.MATERIALIZED_VIEW);
    documents.put(
        "table.referencedTables",
        () -> TableDefinitionTemplates.referencedTables(List.of(viewReference)));
    documents.put(
        "table.referencingViews",
        () -> TableDefinitionTemplates.referencingViews(List.of(viewReference)));
    documents.put(
        "table.indexes",
        () ->
            TableDefinitionTemplates.indexes(
                List.of(
                    new IndexEntity(
                        HOSTILE, HOSTILE, HOSTILE, HOSTILE, true, true, HOSTILE, HOSTILE))));
    documents.put(
        "table.constraints",
        () ->
            TableDefinitionTemplates.constraints(
                List.of(
                    new ConstraintEntity(HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE))));
    documents.put("table.foreignKeys", () -> TableDefinitionTemplates.foreignKeys(List.of(fk)));
    documents.put(
        "table.incomingRelations", () -> TableDefinitionTemplates.incomingRelations(List.of(fk)));
    documents.put("table.triggers", () -> TableDefinitionTemplates.triggers(List.of(trigger)));
    documents.put(
        "table.erDiagram",
        () ->
            TableDefinitionTemplates.erDiagram(
                table,
                List.of(column),
                neighborhood(List.of(fk), List.of()),
                DiagramBoxesFixtures.none()));
    documents.put(
        "tableList.tableListLine", () -> TableDefinitionListTemplates.tableListLine(1, table));
    documents.put(
        "objectList.lines",
        () ->
            ObjectListTemplates.triggerListLine(1, trigger)
                + ObjectListTemplates.functionListLine(1, function)
                + ObjectListTemplates.sequenceListLine(1, sequence)
                + ObjectListTemplates.typeListLine(1, type));
    documents.put(
        "objectDefinition.functionFile",
        () ->
            ObjectDefinitionTemplates.functionFile(
                FunctionDefinitionContent.withoutTableUsage(function), BASE_INFO));
    final TableEntity hostileTable =
        new TableEntity(HOSTILE, HOSTILE, HOSTILE, HOSTILE, TableType.TABLE, HOSTILE);
    final FunctionTableUsage tableUsage =
        FunctionTableUsage.analyzed(
            Arrays.stream(SchemaResolution.values())
                .map(
                    resolution ->
                        new TableUsage(
                            resolution == SchemaResolution.DETERMINED
                                ? List.of(hostileTable)
                                : List.of(hostileTable, hostileTable),
                            resolution,
                            Set.of(CrudOperation.READ)))
                .toList(),
            List.of(DynamicSqlKind.EXECUTE),
            true,
            true);
    documents.put(
        "objectDefinition.functionFile.tableUsage",
        () ->
            ObjectDefinitionTemplates.functionFile(
                new FunctionDefinitionContent(function, tableUsage), BASE_INFO));
    documents.put(
        "objectDefinition.functionFile.unsupportedLanguage",
        () ->
            ObjectDefinitionTemplates.functionFile(
                new FunctionDefinitionContent(
                    function, FunctionTableUsage.unsupportedLanguage(HOSTILE)),
                BASE_INFO));
    documents.put(
        "objectDefinition.sequenceFile",
        () -> ObjectDefinitionTemplates.sequenceFile(sequence, BASE_INFO));
    documents.put(
        "objectDefinition.typeFile", () -> ObjectDefinitionTemplates.typeFile(type, BASE_INFO));
    documents.put("readme.fileHeader", () -> ReadmeTemplates.fileHeader(BASE_INFO));
    documents.put(
        "readme.metrics",
        () ->
            ReadmeTemplates.metrics(
                DatabaseMetrics.builder(
                        ExportTargetsFixtures.of(
                            List.of(
                                table,
                                new TableEntity("db", "other", "", "t", TableType.TABLE, "")),
                            List.of(),
                            List.of(function),
                            EnumSet.allOf(OutputObjectType.class)))
                    .build()));
    documents.put(
        "erDiagram.headers",
        () ->
            ErDiagramTemplates.schemaFileHeader(HOSTILE, BASE_INFO)
                + ErDiagramTemplates.groupFileHeader(HOSTILE, 1, BASE_INFO));
    documents.put(
        "erDiagram.schemaIndex",
        () -> ErDiagramTemplates.schemaIndex(BASE_INFO, Map.of(HOSTILE, List.of(table))));
    documents.put(
        "erDiagram.lines",
        () ->
            ErDiagramTemplates.diagramTableLine(1, TableKey.of(table), table)
                + ErDiagramTemplates.diagramTableLine(1, new TableKey(HOSTILE, HOSTILE), null)
                + ErDiagramTemplates.foreignKeyTableLine(1, fk)
                + ErDiagramTemplates.groupIndexLine(1, 1, 1, TableKey.of(table), "./x.md"));
    return documents;
  }

  /** コードブロックの中身を除いたうえで、生のHTMLの開始が無いことを確かめる */
  private static void assertNoRawHtml(String name, String markdown) {
    final List<String> outsideCodeBlocks = new ArrayList<>();
    int openFenceLength = 0;
    for (final String line : markdown.split("\\R", -1)) {
      final Matcher fence = FENCE.matcher(line);
      if (openFenceLength == 0) {
        if (fence.matches()) {
          openFenceLength = fence.group(1).length();
        } else {
          outsideCodeBlocks.add(line);
        }
      } else if (fence.matches()
          && fence.group(1).length() >= openFenceLength
          && fence.group(2).isBlank()) {
        openFenceLength = 0;
      }
    }
    assertEquals(0, openFenceLength, name + ": a code block is left open");
    outsideCodeBlocks.forEach(
        line ->
            assertFalse(
                RAW_HTML.matcher(line).find(), name + ": raw HTML outside code blocks: " + line));
  }

  /** 自テーブルの関連だけを描く（描画距離1の）ER図の関連 */
  private static DiagramNeighborhood neighborhood(
      List<ForeignKeyEntity> outgoing, List<ForeignKeyEntity> incoming) {
    return new DiagramNeighborhood(
        java.util.stream.Stream.concat(outgoing.stream(), incoming.stream()).toList(), 1, 1);
  }
}
