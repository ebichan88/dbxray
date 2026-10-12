package com.dbxray.domain.service.writer.template;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.relation.DiagramBoxes;
import com.dbxray.domain.model.relation.DiagramColumn;
import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.table.ColumnEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.service.writer.erdiagram.ErDiagramTemplates;
import com.dbxray.domain.service.writer.tabledefinition.TableDefinitionTemplates;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mermaid記法の出力に必要な文字列変換を扱う共通ユーティリティクラス<br>
 * テーブル単位のER図（{@link TableDefinitionTemplates}）とスキーマ単位のER図（{@link ErDiagramTemplates}）の両方から利用する
 */
public final class MermaidSupport {

  private MermaidSupport() {}

  /**
   * ER図のコードブロック（末尾の空行を含む）を組み立てるメソッド<br>
   * 各ノードのエンティティ別名を宣言し、関係線を参照先（親）→ 参照元（子）の向きで描いた後、ノードごとにテーブルの箱を描く
   *
   * @param nodes 図に描画するノード（重複無し。この順に別名を宣言し、箱を描く）
   * @param relations 図に描画する関連（この順に関係線を描く）
   * @param columnsOf ノードの箱に表示するカラムを求める関数（表示するカラムが無いノードは空のリストを返す）
   */
  public static String erDiagramCodeBlock(
      Collection<TableKey> nodes,
      Collection<ForeignKeyEntity> relations,
      DiagramBoxes boxes,
      Function<TableKey, List<DiagramColumn>> columnsOf) {
    final Map<TableKey, String> ids = assignNodeIds(nodes);
    final Map<TableKey, String> labels = assignLabels(nodes, boxes);
    final StringBuilder sb =
        new StringBuilder("```mermaid")
            .append(LINE_SEPARATOR)
            .append("erDiagram")
            .append(LINE_SEPARATOR);
    nodes.forEach(key -> sb.append(aliasLine(ids.get(key), labels.get(key))));
    relations.forEach(
        fk -> sb.append(relationLine(ids.get(fk.referenceTableKey()), fk, ids.get(fk.tableKey()))));
    nodes.forEach(key -> sb.append(attributeBlock(ids.get(key), columnsOf.apply(key))));
    return sb.append("```").append(LINE_SEPARATOR_DOUBLE).toString();
  }

  /**
   * 図に描画するノードごとに、一意なMermaidのエンティティ識別子を採番するメソッド<br>
   * 識別子のサニタイズでは英数字以外がすべてアンダースコアに潰れるため、別のテーブルが同じ識別子になり1つの箱に融合することがある（例: {@code a_b.c}と
   * {@code a.b_c}、同じ文字数の日本語のテーブル名）。衝突した場合は連番を付けて区別する
   *
   * @param nodes 図に描画するノードのテーブルキー（重複無し。この順に採番するため、衝突した場合は後のものに連番が付く）
   * @return テーブルキーごとの識別子
   */
  public static Map<TableKey, String> assignNodeIds(Collection<TableKey> nodes) {
    final Map<TableKey, String> ids = new LinkedHashMap<>();
    final Set<String> usedIds = new HashSet<>();
    nodes.forEach(
        key -> {
          final String baseId = mermaidId(key);
          String id = baseId;
          for (int suffix = 2; !usedIds.add(id); suffix++) {
            id = baseId + "_" + suffix;
          }
          ids.put(key, id);
        });
    return ids;
  }

  /** スキーマ名を含めることで、同名テーブルが複数スキーマに存在する場合の識別子衝突を避ける */
  private static String mermaidId(TableKey key) {
    return sanitizeIdentifier(key.schema() + "_" + key.table());
  }

  /**
   * 図に描画するノードの表示ラベルを決めるメソッド<br>
   * 識別子（スキーマ名込み）とは別に、Mermaidのエンティティ別名構文で表示名を差し替える。
   * 通常はテーブル名のみを表示するが、同じ図内に同名テーブルが複数スキーマにまたがって存在する
   * 場合は見分けが付かなくなるため、その場合だけ「スキーマ名.テーブル名」の完全修飾名にする。
   * 論理テーブル名がある場合は「テーブル名（論理テーブル名）」とする
   *
   * @param nodes 図に描画するノードのテーブルキー（重複無し）
   * @return テーブルキーごとの表示ラベル
   */
  public static Map<TableKey, String> assignLabels(Collection<TableKey> nodes, DiagramBoxes boxes) {
    final Map<String, Long> tableNameCounts =
        nodes.stream().collect(Collectors.groupingBy(TableKey::table, Collectors.counting()));
    final Map<TableKey, String> labels = new LinkedHashMap<>();
    nodes.forEach(
        key -> {
          final String name =
              tableNameCounts.get(key.table()) > 1 ? key.qualifiedName() : key.table();
          final String logicalName = boxes.logicalTableName(key);
          labels.put(key, logicalName.isEmpty() ? name : name + "（" + logicalName + "）");
        });
    return labels;
  }

  /**
   * エンティティ別名の宣言1行分を生成するメソッド<br>
   * 識別子ごとに図内で1回宣言すれば、以降その識別子が登場する箇所（属性ブロック・関係線の両方）に別名が適用される
   */
  public static String aliasLine(String id, String label) {
    return "    " + id + "[\"" + quotable(label) + "\"]" + LINE_SEPARATOR;
  }

  /**
   * テーブルの箱に表示するカラムの属性ブロックを生成するメソッド<br>
   * 1行は「型 物理カラム名 キー "論理カラム名"」。Mermaidの型・名前には記号や日本語を書けないため、論理カラム名は末尾のコメントに置く
   *
   * @return 属性ブロック（末尾の改行を含む）。表示するカラムが無い場合は空文字
   */
  public static String attributeBlock(String id, List<DiagramColumn> columns) {
    if (columns.isEmpty()) {
      return "";
    }
    final StringBuilder sb =
        new StringBuilder("    ").append(id).append(" {").append(LINE_SEPARATOR);
    columns.forEach(c -> sb.append(attributeLine(c)));
    return sb.append("    }").append(LINE_SEPARATOR).toString();
  }

  /** 属性1行分（末尾の改行を含む）を生成するメソッド */
  private static String attributeLine(DiagramColumn diagramColumn) {
    final ColumnEntity column = diagramColumn.column();
    final List<String> keys = new ArrayList<>();
    if (column.primaryKey()) {
      keys.add("PK");
    }
    if (diagramColumn.foreignKey()) {
      keys.add("FK");
    }
    final StringBuilder sb =
        new StringBuilder("        ")
            .append(sanitizeType(column.columnType()))
            .append(' ')
            .append(sanitizeIdentifier(column.physicalColumnName()));
    if (!keys.isEmpty()) {
      sb.append(' ').append(String.join(", ", keys));
    }
    if (!column.logicalColumnName().isBlank()) {
      sb.append(" \"").append(quotable(column.logicalColumnName())).append('"');
    }
    return sb.append(LINE_SEPARATOR).toString();
  }

  /**
   * 二重引用符で囲む表示名・コメントは、二重引用符を含められないため単一引用符に置き換える。
   * 改行は、図の次の行（Mermaidの構文や、コードブロックを閉じる{@code ```}）として解釈されないよう空白に置き換える
   */
  private static String quotable(String value) {
    return value.replace('"', '\'').replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
  }

  /**
   * 関係線1本分の行を生成するメソッド<br>
   * 参照先（親）→ 参照元（子）の向きで描画し、線種（実線／破線）と多重度は外部キーの由来・多重度から組み立てる
   *
   * @param parentId 参照先（親）のエンティティ識別子
   * @param fk 外部キーまたは論理リレーション
   * @param childId 参照元（子）のエンティティ識別子
   * @return 関係線1本分の行（末尾の改行を含む）
   */
  public static String relationLine(String parentId, ForeignKeyEntity fk, String childId) {
    return "    "
        + parentId
        + ' '
        + fk.cardinality().notation(fk.relationType())
        + ' '
        + childId
        + " : \""
        + quotable(fk.foreignKeyName())
        + '"'
        + LINE_SEPARATOR;
  }

  /** Mermaid記法で識別子として利用できない文字をアンダースコアに置換するメソッド */
  public static String sanitizeIdentifier(String value) {
    return value.replaceAll("[^A-Za-z0-9_]", "_");
  }

  /**
   * データ型からMermaid記法の属性型として利用できる文字列を生成するメソッド<br>
   * 桁数・精度を表す括弧部分を除去し、残った空白をアンダースコアに置換する
   *
   * @return サニタイズ済みのデータ型文字列
   */
  public static String sanitizeType(String columnType) {
    return columnType.replaceAll("\\(.*\\)", "").trim().replaceAll("[^A-Za-z0-9_]+", "_");
  }
}
