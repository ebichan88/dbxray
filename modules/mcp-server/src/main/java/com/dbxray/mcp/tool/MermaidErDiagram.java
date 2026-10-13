package com.dbxray.mcp.tool;

import com.dbxray.mcp.catalog.ColumnEntry;
import com.dbxray.mcp.catalog.DiagramScope;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.Relation;
import com.dbxray.mcp.catalog.RelationKind;
import com.dbxray.mcp.catalog.TableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ER図のMermaid記法（{@code erDiagram}）の組み立て<br>
 * 表記はcliのER図（{@code MermaidSupport}）に揃える。箱は「テーブル名（論理名）」と、図に描いた関連をつなぐカラム。
 * テーブル名・論理名・関連名はDB由来の信頼できない入力のため、識別子は記号を置き換え、表示名は改行・二重引用符を置き換える
 */
final class MermaidErDiagram {

  private static final String LINE_SEPARATOR = "\n";

  /** 多重度が未設定・未知の場合（古いcliのスナップショット等）は、cliが論理リレーションの既定にする1対多として描く */
  private static final String DEFAULT_CARDINALITY = "ONE_TO_MANY";

  /** 多重度ごとの、参照先（親）側と参照元（子）側の端点の表記 */
  private static final Map<String, List<String>> CARDINALITY_NOTATIONS =
      Map.of(
          "ONE_TO_MANY", List.of("||", "o{"),
          "OPTIONAL_ONE_TO_MANY", List.of("|o", "o{"),
          "ONE_TO_ONE", List.of("||", "o|"),
          "OPTIONAL_ONE_TO_ONE", List.of("|o", "o|"));

  private MermaidErDiagram() {}

  /**
   * 図の範囲から、Mermaidの{@code erDiagram}の記述を組み立てるメソッド
   *
   * @return コードブロックの囲み（{@code ```mermaid}）を含まない記述（末尾の改行を含む）。関連の参照先がスナップショットに無いテーブルは、カラムの無い箱として描く
   */
  static String render(DiagramScope scope) {
    final Map<ObjectKey, TableEntry> tables = new LinkedHashMap<>();
    scope.tables().forEach(table -> tables.put(table.key(), table));
    final List<ObjectKey> nodes = scope.nodes();
    final Map<ObjectKey, String> ids = assignIds(nodes);
    final StringBuilder sb = new StringBuilder("erDiagram").append(LINE_SEPARATOR);
    nodes.forEach(key -> sb.append(aliasLine(ids.get(key), label(key, tables.get(key), nodes))));
    scope
        .relations()
        .forEach(
            relation ->
                sb.append(
                    relationLine(ids.get(relation.to()), relation, ids.get(relation.from()))));
    final Map<ObjectKey, Set<String>> usedColumns = new HashMap<>();
    final Map<ObjectKey, Set<String>> foreignKeyColumns = new HashMap<>();
    scope
        .relations()
        .forEach(
            relation -> {
              usedColumns
                  .computeIfAbsent(relation.from(), key -> new HashSet<>())
                  .addAll(relation.fromColumns());
              foreignKeyColumns
                  .computeIfAbsent(relation.from(), key -> new HashSet<>())
                  .addAll(relation.fromColumns());
              usedColumns
                  .computeIfAbsent(relation.to(), key -> new HashSet<>())
                  .addAll(relation.toColumns());
            });
    tables.forEach(
        (key, table) ->
            sb.append(
                attributeBlock(
                    ids.get(key),
                    table,
                    usedColumns.getOrDefault(key, Set.of()),
                    foreignKeyColumns.getOrDefault(key, Set.of()))));
    return sb.toString();
  }

  /** 識別子を記号の置き換えで作り、置き換えの結果が重なった場合は連番を付けて区別する */
  private static Map<ObjectKey, String> assignIds(List<ObjectKey> nodes) {
    final Map<ObjectKey, String> ids = new LinkedHashMap<>();
    final Set<String> used = new HashSet<>();
    nodes.forEach(
        key -> {
          final String base = sanitizeIdentifier(key.schema() + "_" + key.name());
          String id = base;
          for (int suffix = 2; !used.add(id); suffix++) {
            id = base + "_" + suffix;
          }
          ids.put(key, id);
        });
    return ids;
  }

  /**
   * 箱の表示名。図の中に同じ名前のテーブルが複数のスキーマにある場合だけ、{@code スキーマ名.テーブル名}にする
   *
   * @param table スナップショットに無いテーブルの場合はnull
   */
  private static String label(ObjectKey key, TableEntry table, List<ObjectKey> nodes) {
    final long sameName = nodes.stream().filter(node -> node.name().equals(key.name())).count();
    final String name = sameName > 1 ? key.qualifiedName() : key.name();
    final String logicalName = table == null ? "" : table.logicalName();
    return logicalName.isEmpty() ? name : name + "（" + logicalName + "）";
  }

  private static String aliasLine(String id, String label) {
    return "    " + id + "[\"" + quotable(label) + "\"]" + LINE_SEPARATOR;
  }

  /** 参照先（親）→ 参照元（子）の向きで描く。外部キーは実線、論理リレーションは破線 */
  private static String relationLine(String parentId, Relation relation, String childId) {
    final List<String> notation =
        CARDINALITY_NOTATIONS.getOrDefault(
            relation.cardinality(), CARDINALITY_NOTATIONS.get(DEFAULT_CARDINALITY));
    final String line = relation.kind() == RelationKind.FOREIGN_KEY ? "--" : "..";
    return "    "
        + parentId
        + ' '
        + notation.get(0)
        + line
        + notation.get(1)
        + ' '
        + childId
        + " : \""
        + quotable(relation.name())
        + '"'
        + LINE_SEPARATOR;
  }

  /**
   * 箱に表示するカラムの属性ブロック。1行は「型 物理カラム名 キー "論理カラム名"」
   *
   * @return 表示するカラムが無い場合は空文字
   */
  private static String attributeBlock(
      String id, TableEntry table, Set<String> usedColumns, Set<String> foreignKeyColumns) {
    final List<ColumnEntry> columns =
        table.columns().stream().filter(column -> usedColumns.contains(column.name())).toList();
    if (columns.isEmpty()) {
      return "";
    }
    final StringBuilder sb =
        new StringBuilder("    ").append(id).append(" {").append(LINE_SEPARATOR);
    columns.forEach(
        column -> sb.append(attributeLine(column, foreignKeyColumns.contains(column.name()))));
    return sb.append("    }").append(LINE_SEPARATOR).toString();
  }

  private static String attributeLine(ColumnEntry column, boolean foreignKey) {
    final List<String> keys = new ArrayList<>();
    if (column.primaryKey()) {
      keys.add("PK");
    }
    if (foreignKey) {
      keys.add("FK");
    }
    final StringBuilder sb =
        new StringBuilder("        ")
            .append(sanitizeType(column.type()))
            .append(' ')
            .append(sanitizeIdentifier(column.name()));
    if (!keys.isEmpty()) {
      sb.append(' ').append(String.join(", ", keys));
    }
    if (!column.logicalName().isBlank()) {
      sb.append(" \"").append(quotable(column.logicalName())).append('"');
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * 二重引用符で囲む表示名は、二重引用符を含められないため単一引用符に置き換える。
   * 改行は、図の次の行（Mermaidの構文や、コードブロックを閉じる{@code ```}）として解釈されないよう空白に置き換える
   */
  static String quotable(String value) {
    return value.replace('"', '\'').replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
  }

  /** Mermaidの識別子に使えない文字をアンダースコアに置き換える */
  static String sanitizeIdentifier(String value) {
    return value.replaceAll("[^A-Za-z0-9_]", "_");
  }

  /**
   * 型から、Mermaidの属性の型に使える文字列を作る。桁数・精度の括弧を除き、残った記号・空白をアンダースコアに置き換える
   *
   * @return 型が未設定の場合は{@code unknown}（Mermaidの属性は型を省略できないため）
   */
  static String sanitizeType(String type) {
    final String sanitized =
        type.replaceAll("\\(.*\\)", "").strip().replaceAll("[^A-Za-z0-9_]+", "_");
    return sanitized.isEmpty() ? "unknown" : sanitized;
  }
}
