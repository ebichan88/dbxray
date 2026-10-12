package com.dbxray.domain.service.writer.objectlist;

import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR;
import static com.dbxray.domain.service.writer.template.MarkdownTemplateSupport.LINE_SEPARATOR_DOUBLE;

import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.SchemaResolution;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.service.writer.template.MarkdownTemplateSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 関数・プロシージャの個別定義の「利用しているテーブル」セクションのMarkdownのテンプレートを扱うクラス */
public final class FunctionTableUsageTemplates {

  private static final String HEADING = "## 利用しているテーブル";

  private static final String DISCLAIMER =
      "定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。";

  private static final String TABLE_HEADER =
      """
      | No. | テーブル | 区分 | C | R | U | D |
      |:---|:---|:---|:---|:---|:---|:---|
      """;

  private FunctionTableUsageTemplates() {}

  /**
   * 利用しているテーブルのセクション<br>
   * 表の前に、抽出の限界（該当なし・動的SQL・字句を最後まで読めなかった・オーバーロードの本体をまとめた）を示す文を置く。
   * 解析しなかった場合は、表の代わりに理由を示す
   *
   * @return 抽出を行わない実行（プレビューの機能を有効にしていない実行）では空文字
   */
  public static String section(FunctionDefinitionContent content) {
    final FunctionTableUsage usage = content.tableUsage();
    if (!usage.isAttempted()) {
      return "";
    }
    final List<String> paragraphs = new ArrayList<>();
    final String notAnalyzedReason = notAnalyzedReason(usage);
    if (!notAnalyzedReason.isEmpty()) {
      paragraphs.add(notAnalyzedReason);
    } else {
      paragraphs.add(DISCLAIMER);
      paragraphs.addAll(notes(usage));
      if (!usage.tables().isEmpty()) {
        paragraphs.add(table(usage.tables()));
      }
      usage
          .schemaUndeterminedTables()
          .forEach((resolution, rows) -> paragraphs.add(schemaCandidates(resolution, rows)));
    }
    return HEADING
        + LINE_SEPARATOR_DOUBLE
        + String.join(LINE_SEPARATOR_DOUBLE, paragraphs)
        + LINE_SEPARATOR_DOUBLE;
  }

  /**
   * 解析しなかった理由の文
   *
   * @return 解析した場合は空文字
   */
  private static String notAnalyzedReason(FunctionTableUsage usage) {
    return switch (usage.status()) {
      case UNSUPPORTED_LANGUAGE ->
          "言語が"
              + MarkdownTemplateSupport.escapeInline(usage.language())
              + "のため、利用しているテーブルは抽出していません。";
      case NO_DEFINITION -> "定義本体を取得できなかったため、利用しているテーブルは抽出していません。";
      case WRAPPED -> "定義本体がwrap（難読化）されているため、利用しているテーブルは抽出していません。";
      case SUBPROGRAM_NOT_FOUND -> "パッケージ本体からこのサブプログラムの本体を見つけられなかったため、利用しているテーブルは抽出していません。";
      case ANALYZED, NOT_ANALYZED -> "";
    };
  }

  /** 抽出の結果の限界を示す文（該当なし・動的SQL・字句を最後まで読めなかった・オーバーロードの本体をまとめた） */
  private static List<String> notes(FunctionTableUsage usage) {
    final List<String> notes = new ArrayList<>();
    if (usage.tables().isEmpty()) {
      notes.add("出力対象のテーブルへの参照は見つかりませんでした。");
    }
    if (!usage.dynamicSql().isEmpty()) {
      notes.add(
          "動的SQL（"
              + usage.dynamicSql().stream()
                  .map(DynamicSqlKind::label)
                  .collect(Collectors.joining("・"))
              + "）を含むため、この一覧に無いテーブルを利用している可能性があります。");
    }
    if (usage.incomplete()) {
      notes.add("定義本体の途中で閉じていない文字列・コメント等があり、一覧が欠けている可能性があります。");
    }
    if (usage.overloadsMerged()) {
      notes.add("同名のサブプログラムの本体を区別できなかったため、すべての本体からまとめて抽出しています。");
    }
    return notes;
  }

  /** 利用しているテーブルの表（末尾の改行を含まない） */
  private static String table(List<TableUsage> tables) {
    final StringBuilder sb = new StringBuilder(TABLE_HEADER);
    for (int i = 0; i < tables.size(); i++) {
      if (i > 0) {
        sb.append(LINE_SEPARATOR);
      }
      final TableUsage usage = tables.get(i);
      sb.append(
          MarkdownTemplateSupport.row(
              i + 1,
              MarkdownTemplateSupport.escapeTableCell(displayName(usage)),
              usage.tableType().map(TableType::getName).orElse(""),
              MarkdownTemplateSupport.marker(usage.has(CrudOperation.CREATE)),
              MarkdownTemplateSupport.marker(usage.has(CrudOperation.READ)),
              MarkdownTemplateSupport.marker(usage.has(CrudOperation.UPDATE)),
              MarkdownTemplateSupport.marker(usage.has(CrudOperation.DELETE))));
    }
    return sb.toString();
  }

  /** 表に示す名前（スキーマが決まらない名前は、スキーマの代わりに決まり方のプレースホルダーを付ける） */
  private static String displayName(TableUsage usage) {
    return (usage.schemaDetermined() ? usage.schemaName() : placeholder(usage.resolution()))
        + "."
        + usage.tableName();
  }

  /**
   * スキーマが決まらない名前の候補のスキーマを示す段落（決まり方1つ分）
   *
   * @param rows その決まり方の行
   */
  private static String schemaCandidates(SchemaResolution resolution, List<TableUsage> rows) {
    final String explanation =
        switch (resolution) {
          case SEARCH_PATH -> "は、スキーマ修飾が無く、実行時のsearch_pathで決まる名前です。";
          case CURRENT_USER -> "は、スキーマ修飾が無く、実行者権限（AUTHID CURRENT_USER）のため実行するユーザーで決まる名前です。";
          case SYNONYM -> "は、スキーマ修飾が無く所有者のスキーマにも無いため、シノニム経由で参照している可能性がある名前です。";
          case DETERMINED -> "";
        };
    return placeholder(resolution)
        + " "
        + explanation
        + "出力対象で同じ名前のテーブルがあるスキーマは次のとおりです。"
        + LINE_SEPARATOR_DOUBLE
        + rows.stream()
            .map(
                usage ->
                    "- "
                        + MarkdownTemplateSupport.escapeInline(usage.tableName())
                        + ": "
                        + MarkdownTemplateSupport.escapeInline(
                            String.join(", ", usage.schemaCandidates())))
            .collect(Collectors.joining(LINE_SEPARATOR));
  }

  /** スキーマが決まらない名前に、スキーマの代わりに付けるプレースホルダー（{@code (search_path)}等） */
  private static String placeholder(SchemaResolution resolution) {
    return "(" + resolution.id() + ")";
  }
}
