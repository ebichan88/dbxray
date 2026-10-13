package com.dbxray.mcp.tool;

import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.ErRole;
import com.dbxray.mcp.catalog.ManyToMany;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.ViewpointEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code get_table}の1テーブル分の結果の組み立て<br>
 * スナップショットの1行に、{@code sections}・{@code columns}による絞り込みと、所属する観点・ER上の区分を反映する
 */
final class TableOutputBuilder {

  private final SchemaCatalog catalog;

  /** 残す項目。空の場合は絞り込まない。{@code columns}を指定した場合はカラムの項目も残す */
  private final Set<TableSection> sections;

  /** 残すカラムの名前（大文字小文字を区別しない）。空の場合は絞り込まない */
  private final List<String> columns;

  TableOutputBuilder(SchemaCatalog catalog, Set<TableSection> sections, List<String> columns) {
    this.catalog = catalog;
    this.sections = effectiveSections(sections, columns);
    this.columns = List.copyOf(columns);
  }

  /**
   * 1テーブルの結果を組み立てるメソッド
   *
   * @throws InvalidToolArgumentException {@code columns}にテーブルに無いカラム名が含まれる場合
   */
  ObjectNode build(TableEntry table) {
    final ObjectNode output = ToolResults.readObject(table.json());
    if (!sections.isEmpty()) {
      for (final TableSection section : TableSection.values()) {
        if (!sections.contains(section)) {
          output.remove(section.fieldName());
        }
      }
    }
    if (!columns.isEmpty()) {
      output.set("columns", selectColumns(table, output.path("columns")));
    }
    if (sections.isEmpty() || sections.contains(TableSection.REFERENCED_BY_VIEWS)) {
      addReferencingViews(output, table);
    }
    addViewpoints(output, table);
    addErRole(output, table);
    return output;
  }

  /** テーブルを参照しているビューを、ビューの{@code referencedTables}と同じ形（スキーマ名・名前・区分）で加える */
  private void addReferencingViews(ObjectNode output, TableEntry table) {
    final List<TableEntry> views = catalog.tables().viewsReferencing(table);
    if (views.isEmpty()) {
      return;
    }
    final ArrayNode entries = output.putArray(TableSection.REFERENCED_BY_VIEWS.fieldName());
    for (final TableEntry view : views) {
      entries
          .addObject()
          .put("schema", view.key().schema())
          .put("name", view.key().name())
          .put("type", view.type());
    }
  }

  private static Set<TableSection> effectiveSections(
      Set<TableSection> sections, List<String> columns) {
    if (sections.isEmpty()) {
      return Set.of();
    }
    final Set<TableSection> effective = EnumSet.copyOf(sections);
    if (!columns.isEmpty()) {
      effective.add(TableSection.COLUMNS);
    }
    return effective;
  }

  /**
   * カラムの項目を、指定された名前のカラムだけ（テーブル定義の並び順）に絞る
   *
   * @throws InvalidToolArgumentException テーブルに無いカラム名が含まれる場合
   */
  private ArrayNode selectColumns(TableEntry table, JsonNode allColumns) {
    final Set<String> wanted =
        columns.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    final ArrayNode selected = JsonNodeFactory.instance.arrayNode();
    final Set<String> found = new HashSet<>();
    for (final Iterator<JsonNode> it = allColumns.elements(); it.hasNext(); ) {
      final JsonNode column = it.next();
      final String name = column.path("name").asText().toLowerCase(Locale.ROOT);
      if (wanted.contains(name)) {
        selected.add(column);
        found.add(name);
      }
    }
    final List<String> missing =
        columns.stream().filter(name -> !found.contains(name.toLowerCase(Locale.ROOT))).toList();
    if (!missing.isEmpty()) {
      throw new InvalidToolArgumentException(
          "テーブル"
              + table.key().qualifiedName()
              + "にカラム"
              + String.join(", ", missing)
              + "がありません。カラム: "
              + table.columns().stream().map(ColumnEntry::name).collect(Collectors.joining(", ")));
    }
    return selected;
  }

  /**
   * ER上の区分と多対多の関係を加える<br>
   * 中間テーブルの場合は{@code erRole}と、つなぐテーブル（{@code junctionOf}）を、多対多の関係にあるテーブルがある場合は、
   * 相手のテーブルと中間テーブル（{@code manyToMany}）を加える。
   * スナップショットの項目ではない（導出した値）ため{@code sections}では絞り込まず、該当すれば常に加える
   */
  private void addErRole(ObjectNode output, TableEntry table) {
    catalog
        .junctions()
        .of(table)
        .ifPresent(
            junction -> {
              output.put("erRole", ErRole.JUNCTION.value());
              final ArrayNode parents = output.putArray("junctionOf");
              junction.parents().forEach(parent -> parents.add(parent.qualifiedName()));
            });
    final List<ManyToMany> partners = catalog.junctions().manyToMany(table);
    if (partners.isEmpty()) {
      return;
    }
    final ArrayNode entries = output.putArray("manyToMany");
    for (final ManyToMany partner : partners) {
      entries
          .addObject()
          .put("table", partner.table().qualifiedName())
          .put("via", partner.via().qualifiedName());
    }
  }

  /**
   * 所属する観点（{@code id}・{@code name}）を宣言順に加える<br>
   * 観点はスナップショットの項目ではない（参考情報）ため{@code sections}では絞り込まず、所属する観点があれば常に加える
   */
  private void addViewpoints(ObjectNode output, TableEntry table) {
    final List<ViewpointEntry> viewpoints = catalog.viewpoints().containing(table);
    if (viewpoints.isEmpty()) {
      return;
    }
    final ArrayNode entries = output.putArray("viewpoints");
    for (final ViewpointEntry viewpoint : viewpoints) {
      final ObjectNode entry = entries.addObject().put("id", viewpoint.id());
      if (!viewpoint.name().isEmpty()) {
        entry.put("name", viewpoint.name());
      }
    }
  }
}
