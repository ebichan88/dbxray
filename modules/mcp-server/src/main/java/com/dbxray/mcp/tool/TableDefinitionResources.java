package com.dbxray.mcp.tool;

import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * テーブルの定義を、利用者がMCPクライアントの{@code @}等で会話に添付するリソースとして返す<br>
 * 全テーブル（ビューを含む）を具体的なリソースとして列挙し、読むと{@code get_table}と同じJSONを返す
 */
public final class TableDefinitionResources {

  private static final String MIME_TYPE = "application/json";

  private static final int MAX_DESCRIPTION_LENGTH = 120;

  /** 表示用の文字列から取り除く文字（制御文字・行／段落区切り・双方向の書字方向を変える制御文字）の並び */
  private static final Pattern UNSAFE_DISPLAY_CHARS =
      Pattern.compile("[\\p{Cc}\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]+");

  private static final Pattern WHITESPACES = Pattern.compile("\\s+");

  private final TableOutputBuilder outputBuilder;
  private final SchemaCatalog catalog;

  public TableDefinitionResources(SchemaCatalog catalog) {
    this.catalog = catalog;
    this.outputBuilder = new TableOutputBuilder(catalog, Set.of(), List.of());
  }

  /**
   * MCPサーバーへ登録するリソースの一覧を返すメソッド
   *
   * @return 全テーブル（ビューを含む）。定義は読まれたときに組み立てる
   */
  public List<SyncResourceSpecification> specifications() {
    return catalog.tables().all().stream().map(this::specificationOf).toList();
  }

  /**
   * リソースの表示名<br>
   * MCPクライアントの絞り込み検索は、Claude Codeが{@code name}・{@code description}・URI、
   * VS Codeが{@code name}・URIを対象にする（{@code title}は対象外）。論理名・物理名のどちらでも探せるよう、両方を含める
   */
  static String displayName(TableEntry table) {
    final String qualified = singleLine(table.key().qualifiedName());
    final String logicalName = singleLine(table.logicalName());
    return logicalName.isEmpty() ? qualified : logicalName + " (" + qualified + ")";
  }

  private SyncResourceSpecification specificationOf(TableEntry table) {
    final String uri = TableResourceUri.of(table.key());
    final String name = displayName(table);
    final Resource resource =
        Resource.builder()
            .uri(uri)
            .name(name)
            .title(name)
            .description(description(table, name))
            .mimeType(MIME_TYPE)
            .build();
    return new SyncResourceSpecification(
        resource,
        (exchange, request) ->
            new ReadResourceResult(
                List.of(
                    new TextResourceContents(
                        uri, MIME_TYPE, outputBuilder.build(table).toString()))));
  }

  /**
   * リソースの説明<br>
   * Claude Codeの{@code @}の候補は、URIが途中で切れる（{@code dbxray:dbxray://testdb…}）うえ、表示に{@code name}より
   * {@code description}を優先するため、どのテーブルか分かるよう先頭に表示名を入れる
   */
  private static String description(TableEntry table, String name) {
    final String base = name + " / " + singleLine(table.key().database()) + " / " + table.type();
    final String description = singleLine(table.description());
    if (description.isEmpty()) {
      return base;
    }
    final String text = base + " / " + description;
    return text.length() <= MAX_DESCRIPTION_LENGTH
        ? text
        : text.substring(0, MAX_DESCRIPTION_LENGTH) + "…";
  }

  /** DB由来の文字列を、MCPクライアントの一覧に1行で表示できるようにする（制御文字・改行を空白へ置き換え、連続する空白を1つにする） */
  private static String singleLine(String value) {
    final String printable = UNSAFE_DISPLAY_CHARS.matcher(value).replaceAll(" ");
    return WHITESPACES.matcher(printable).replaceAll(" ").strip();
  }
}
