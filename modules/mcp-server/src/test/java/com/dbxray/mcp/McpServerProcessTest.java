package com.dbxray.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配布するjarを子プロセスで起動し、MCPクライアントからstdioで呼び出すE2Eテスト<br>
 * マニフェスト・依存の同梱（ServiceLoaderの登録を含む）・標準出力にプロトコル以外を出さないことをまとめて確かめる
 */
class McpServerProcessTest {

  private static final String JAVA = ProcessHandle.current().info().command().orElse("java");
  private static final String JAR = System.getProperty("mcpServerJar");
  private static final String SAMPLE_SNAPSHOT = System.getProperty("sampleSnapshotDir");

  @Test
  @DisplayName("MCPクライアントから初期化・ツールの一覧の取得・ツールの呼び出しができる")
  void servesToolsOverStdio() throws Exception {
    final StdioClientTransport transport =
        new StdioClientTransport(
            ServerParameters.builder(JAVA)
                .args("-jar", JAR, "--snapshot=" + SAMPLE_SNAPSHOT)
                .build(),
            new JacksonMcpJsonMapper(new ObjectMapper()));
    try (McpSyncClient client =
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()) {
      client.initialize();

      assertEquals("dbxray", client.getServerInfo().name());
      assertEquals(
          List.of(
              "list_schemas",
              "list_viewpoints",
              "list_table_clusters",
              "search_tables",
              "list_tables",
              "get_table",
              "find_columns",
              "find_similar_tables",
              "get_related_tables",
              "find_join_path",
              "get_er_diagram",
              "list_functions",
              "get_function",
              "list_sequences",
              "get_sequence",
              "list_types",
              "get_type",
              "list_triggers"),
          client.listTools().tools().stream().map(Tool::name).toList());

      final CallToolResult search =
          client.callTool(
              CallToolRequest.builder("search_tables").arguments(Map.of("query", "従業員")).build());
      assertFalse(Boolean.TRUE.equals(search.isError()));
      assertTrue(text(search).contains("\"name\":\"employee\""), text(search));

      final CallToolResult related =
          client.callTool(
              CallToolRequest.builder("get_related_tables")
                  .arguments(Map.of("table", "audit_log"))
                  .build());
      assertTrue(text(related).contains("\"kind\":\"logicalRelation\""), text(related));

      final CallToolResult sections =
          client.callTool(
              CallToolRequest.builder("get_table")
                  .arguments(Map.of("table", "employee", "sections", List.of("foreignKeys")))
                  .build());
      assertFalse(Boolean.TRUE.equals(sections.isError()), () -> text(sections));
      assertTrue(text(sections).contains("\"foreignKeys\""), text(sections));
      assertFalse(text(sections).contains("\"indexes\""), text(sections));

      final CallToolResult manyToMany =
          client.callTool(
              CallToolRequest.builder("get_table").arguments(Map.of("table", "project")).build());
      assertFalse(Boolean.TRUE.equals(manyToMany.isError()), () -> text(manyToMany));
      assertTrue(
          text(manyToMany)
              .contains(
                  "\"manyToMany\":[{\"table\":\"sample.employee\",\"via\":\"sample.project_assignment\"}]"),
          text(manyToMany));

      final CallToolResult junctions =
          client.callTool(
              CallToolRequest.builder("list_tables")
                  .arguments(Map.of("erRole", "junction"))
                  .build());
      assertFalse(Boolean.TRUE.equals(junctions.isError()), () -> text(junctions));
      assertTrue(text(junctions).contains("\"name\":\"project_assignment\""), text(junctions));
      assertTrue(text(junctions).contains("\"erRole\":\"junction\""), text(junctions));

      final CallToolResult similar =
          client.callTool(
              CallToolRequest.builder("find_similar_tables")
                  .arguments(Map.of("columns", List.of("attendance_id", "work_date", "note")))
                  .build());
      assertFalse(Boolean.TRUE.equals(similar.isError()), () -> text(similar));
      assertTrue(text(similar).contains("\"name\":\"sample.attendance_note\""), text(similar));

      final CallToolResult function =
          client.callTool(
              CallToolRequest.builder("get_function")
                  .arguments(Map.of("function", "calculate_bonus"))
                  .build());
      assertFalse(Boolean.TRUE.equals(function.isError()), () -> text(function));
      assertTrue(text(function).contains("\"arguments\":\"p_salary numeric\""), text(function));
      assertFalse(text(function).contains("\"definition\""), "定義本体は返さない");

      final CallToolResult notFound =
          client.callTool(
              CallToolRequest.builder("get_table")
                  .arguments(Map.of("table", "no_such_table"))
                  .build());
      assertTrue(notFound.isError());
    }
  }

  @Test
  @DisplayName("型・範囲・未知の引数は、ハンドラを呼ぶ前にSDKが入力スキーマで検証してツールのエラーにする")
  void validatesArgumentsWithInputSchema() throws Exception {
    final StdioClientTransport transport =
        new StdioClientTransport(
            ServerParameters.builder(JAVA)
                .args("-jar", JAR, "--snapshot=" + SAMPLE_SNAPSHOT)
                .build(),
            new JacksonMcpJsonMapper(new ObjectMapper()));
    try (McpSyncClient client =
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()) {
      client.initialize();

      final CallToolResult arrayAsString =
          client.callTool(
              CallToolRequest.builder("get_table")
                  .arguments(Map.of("table", "employee", "sections", "foreignKeys,triggers"))
                  .build());
      assertTrue(arrayAsString.isError());
      assertTrue(
          text(arrayAsString).contains("/sections: string found, array expected"),
          text(arrayAsString));

      final CallToolResult numberAsString =
          client.callTool(
              CallToolRequest.builder("list_tables").arguments(Map.of("limit", "3")).build());
      assertTrue(numberAsString.isError());
      assertTrue(
          text(numberAsString).contains("/limit: string found, integer expected"),
          text(numberAsString));

      final CallToolResult outOfRange =
          client.callTool(
              CallToolRequest.builder("get_related_tables")
                  .arguments(Map.of("table", "employee", "depth", 4))
                  .build());
      assertTrue(outOfRange.isError());
      assertTrue(text(outOfRange).contains("/depth"), text(outOfRange));

      final CallToolResult unknownErRole =
          client.callTool(
              CallToolRequest.builder("list_tables")
                  .arguments(Map.of("erRole", "subtype"))
                  .build());
      assertTrue(unknownErRole.isError());
      assertTrue(text(unknownErRole).contains("/erRole"), text(unknownErRole));

      final CallToolResult unknown =
          client.callTool(
              CallToolRequest.builder("get_table")
                  .arguments(Map.of("table", "employee", "verbose", true))
                  .build());
      assertTrue(unknown.isError());
      assertTrue(text(unknown).contains("'verbose'"), text(unknown));

      final CallToolResult commaSeparated =
          client.callTool(
              CallToolRequest.builder("get_table")
                  .arguments(Map.of("table", "employee,department"))
                  .build());
      assertFalse(Boolean.TRUE.equals(commaSeparated.isError()), () -> text(commaSeparated));
      assertTrue(text(commaSeparated).startsWith("{\"tables\":["), text(commaSeparated));
    }
  }

  @Test
  @DisplayName("応答を待たずに続けてツールを呼び出しても、すべての応答が返る（並行呼び出しで応答が止まる不具合の回帰）")
  void respondsToPipelinedToolCalls() throws Exception {
    final int callCount = 8;
    final Process process =
        new ProcessBuilder(JAVA, "-jar", JAR, "--snapshot=" + SAMPLE_SNAPSHOT).start();
    try {
      final BufferedWriter stdin =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
      final Thread reader = new Thread(() -> readLines(process, responses));
      reader.setDaemon(true);
      reader.start();

      send(
          stdin,
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
              + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
              + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
      assertNotNull(responses.poll(30, TimeUnit.SECONDS), "initializeの応答がありません");
      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

      // 応答を待たずに続けて書き込む（Claude Code等が独立したツールを並行に呼ぶのと同じ状況を作る）
      for (int i = 0; i < callCount; i++) {
        send(
            stdin,
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + (100 + i)
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"list_schemas\",\"arguments\":{}}}");
      }

      final ObjectMapper mapper = new ObjectMapper();
      final Set<Integer> receivedIds = new HashSet<>();
      for (int i = 0; i < callCount; i++) {
        final String line = responses.poll(30, TimeUnit.SECONDS);
        assertNotNull(line, (i + 1) + "件目の応答がタイムアウトしました（並行呼び出しで応答が止まる不具合の再発）");
        receivedIds.add(mapper.readTree(line).get("id").asInt());
      }
      assertEquals(
          IntStream.range(100, 100 + callCount).boxed().collect(Collectors.toSet()), receivedIds);
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  @DisplayName("スナップショットのディレクトリを読めない場合は、標準エラーに理由を出して終了コード2で終了する")
  void exitsWithMessageOnInvalidSnapshot() throws IOException, InterruptedException {
    final Process process =
        new ProcessBuilder(JAVA, "-jar", JAR, "--snapshot=no/such/directory").start();
    process.getOutputStream().close();

    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "起動に失敗したら待たずに終了する");
    assertEquals(McpServerMain.EXIT_USER_CORRECTABLE, process.exitValue());
    assertEquals("", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    final String stderr =
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(stderr.contains("[error]:スナップショットのディレクトリが見つかりません。"), stderr);
  }

  @Test
  @DisplayName("リソース: 全テーブルを一覧でき、論理名と物理名の両方を含む名前で、読むとテーブル定義が返る")
  void servesResourcesOverStdio() throws Exception {
    final StdioClientTransport transport =
        new StdioClientTransport(
            ServerParameters.builder(JAVA)
                .args("-jar", JAR, "--snapshot=" + SAMPLE_SNAPSHOT)
                .build(),
            new JacksonMcpJsonMapper(new ObjectMapper()));
    try (McpSyncClient client =
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()) {
      client.initialize();

      assertNotNull(client.getServerCapabilities().resources());
      assertNull(client.getServerCapabilities().completions(), "URIテンプレートと補完は出さない");
      assertTrue(client.listResourceTemplates().resourceTemplates().isEmpty());

      final List<Resource> resources = client.listResources().resources();
      assertEquals(14, resources.size());
      final Resource employee =
          resources.stream()
              .filter(resource -> resource.uri().equals("dbxray://testdb/sample/employee"))
              .findFirst()
              .orElseThrow();
      assertTrue(employee.name().endsWith(" (sample.employee)"), employee.name());
      assertTrue(employee.name().length() > " (sample.employee)".length(), employee.name());
      assertTrue(employee.description().startsWith(employee.name()), employee.description());

      final TextResourceContents contents =
          (TextResourceContents) client.readResource(employee).contents().get(0);
      assertEquals("application/json", contents.mimeType());
      assertTrue(contents.text().contains("\"name\":\"employee\""), contents.text());
    }
  }

  @Test
  @DisplayName("リソース: 応答を待たずに続けて読んでも、すべての応答が返る（並行呼び出しで応答が止まる不具合の回帰）")
  void respondsToPipelinedResourceReads() throws Exception {
    final int callCount = 8;
    final Process process =
        new ProcessBuilder(JAVA, "-jar", JAR, "--snapshot=" + SAMPLE_SNAPSHOT).start();
    try {
      final BufferedWriter stdin =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
      final Thread reader = new Thread(() -> readLines(process, responses));
      reader.setDaemon(true);
      reader.start();

      send(
          stdin,
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
              + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
              + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
      assertNotNull(responses.poll(30, TimeUnit.SECONDS), "initializeの応答がありません");
      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

      for (int i = 0; i < callCount; i++) {
        send(
            stdin,
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + (100 + i)
                + ",\"method\":\"resources/read\",\"params\":{"
                + "\"uri\":\"dbxray://testdb/sample/employee\"}}");
      }

      final ObjectMapper mapper = new ObjectMapper();
      final Set<Integer> receivedIds = new HashSet<>();
      for (int i = 0; i < callCount; i++) {
        final String line = responses.poll(30, TimeUnit.SECONDS);
        assertNotNull(line, (i + 1) + "件目の応答がタイムアウトしました（並行呼び出しで応答が止まる不具合の再発）");
        receivedIds.add(mapper.readTree(line).get("id").asInt());
      }
      assertEquals(
          IntStream.range(100, 100 + callCount).boxed().collect(Collectors.toSet()), receivedIds);
    } finally {
      process.destroyForcibly();
    }
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static void send(BufferedWriter stdin, String json) throws IOException {
    stdin.write(json);
    stdin.write("\n");
    stdin.flush();
  }

  /** プロセスの標準出力を行ごとにキューへ流す。プロセス終了時の読み取りの中断は呼び出し側のタイムアウトで判定する */
  private static void readLines(Process process, BlockingQueue<String> lines) {
    try (BufferedReader stdout =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = stdout.readLine()) != null) {
        lines.put(line);
      }
    } catch (IOException | InterruptedException e) {
      // プロセスの終了・破棄で読み取りが止まる。テスト側はpollのタイムアウトで判定するため無視する
    }
  }
}
