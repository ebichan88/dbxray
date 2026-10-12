package com.dbxray.mcp;

import com.dbxray.mcp.catalog.FunctionTableUsageEntry;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.ViewpointEntry;
import com.dbxray.mcp.insight.InsightsDirectoryReader;
import com.dbxray.mcp.snapshot.SnapshotDirectoryReader;
import com.dbxray.mcp.tool.TableDefinitionResources;
import com.dbxray.mcp.tool.TableDefinitionTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * MCPサーバーのエントリーポイント<br>
 * 標準入出力（stdio）でMCPクライアントとやり取りする。標準出力はプロトコル専用のため、利用者向けの表示はすべて標準エラーへ出す
 */
public final class McpServerMain {

  /** 起動引数・スナップショットの誤りで起動できなかった場合の終了コード（cliの失敗と揃える） */
  static final int EXIT_USER_CORRECTABLE = 2;

  private static final String SERVER_NAME = "dbxray";

  /** ツールの使い分け。ツールが多いため、AIが最初の手を選べるよう探し方の順を示す */
  private static final String INSTRUCTIONS =
      "DBのテーブル定義（dbxrayが出力したスキーマのスナップショット）を調べるサーバー。"
          + "全体像はlist_schemas、テーブルはキーワードならsearch_tables・一覧ならlist_tablesで探し"
          + "（どこから読むか迷う場合は、list_tablesのorderByで関連の多い中心のテーブルから並べる）、"
          + "get_tableで定義を取得する（必要な項目だけをsections・columnsで指定すると結果が小さくなる）。"
          + "カラム名からテーブルを探すときはfind_columns、JOINの条件はget_related_tables"
          + "（外部キーと、DBに制約の無い論理リレーション）、直接つながらないテーブル同士はfind_join_pathを使う。"
          + "関数・シーケンス・ユーザー定義型・トリガーは、list_*で探してget_*で取得する。"
          + "業務ドメインの単位（観点）で絞り込みたい場合は、list_viewpointsで一覧を確認し、"
          + "list_tables・search_tablesのviewpoint引数を指定する。"
          + "テーブルがどの観点に所属するかは、get_tableの結果のviewpointsで分かる。"
          + "観点が宣言されていない範囲は、list_table_clustersで関連のつながりから推測したまとまりを手がかりにする。"
          + "ER図を見せたい場合は、get_er_diagramでテーブルの周辺か観点のER図をMermaid記法で取得する。";

  private McpServerMain() {}

  /**
   * MCPサーバーを起動するメソッド
   *
   * @param args {@code --snapshot=<ディレクトリ>}
   */
  public static void main(String[] args) {
    // 標準エラーの文字コードは実行環境のロケールに依存し、日本語が化けることがある。
    // MCPクライアントは標準エラーをログとして取り込むため、ロケールに関わらずUTF-8で出す
    System.setErr(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    final SchemaCatalog catalog;
    try {
      final ServerArguments arguments = ServerArguments.parse(args);
      final InsightsDirectoryReader insightsReader = new InsightsDirectoryReader();
      final List<ViewpointEntry> viewpoints =
          insightsReader.readViewpoints(arguments.snapshotDirectory());
      final List<FunctionTableUsageEntry> functionTableUsages =
          insightsReader.readFunctionTableUsages(arguments.snapshotDirectory());
      catalog =
          new SnapshotDirectoryReader()
              .read(arguments.snapshotDirectory())
              .withViewpoints(viewpoints)
              .withFunctionTableUsages(functionTableUsages);
      System.err.println(
          "[info]:"
              + catalog.tables().all().size()
              + "テーブルを読み込みました。 [snapshot="
              + arguments.snapshotDirectory().toAbsolutePath()
              + "]");
    } catch (UserCorrectableException e) {
      System.err.println("[error]:" + e.getMessage());
      System.exit(EXIT_USER_CORRECTABLE);
      return;
    }
    start(catalog);
  }

  /** stdioのトランスポートでサーバーを起動する。標準入力が閉じられるまでトランスポートのスレッドが応答し続ける */
  private static void start(SchemaCatalog catalog) {
    final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    McpServer.sync(new StdioServerTransportProvider(jsonMapper))
        .serverInfo(SERVER_NAME, version())
        .instructions(INSTRUCTIONS)
        // スナップショットは起動時に読み込んだままで変わらないため、リソースの購読・一覧の変更通知は使わない
        .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).build())
        .jsonMapper(jsonMapper)
        .tools(new TableDefinitionTools(catalog).specifications())
        .resources(new TableDefinitionResources(catalog).specifications())
        // mcp-core 2.0.1は既定でツール・リソースの読み出しをboundedElasticの複数スレッドに並行実行させ、応答の書き込み先
        // （Reactorのunicastシンク）への同時書き込みで止まる（java-sdk#686。修正はSDKのmainに入ったが未リリース）。
        // 標準入力を読むスレッドで1つずつ実行させて回避する
        .immediateExecution(true)
        .build();
  }

  /** jarのマニフェストのバージョン。jarから起動していない場合（テスト等）は{@code dev} */
  private static String version() {
    return Objects.requireNonNullElse(
        McpServerMain.class.getPackage().getImplementationVersion(), "dev");
  }
}
