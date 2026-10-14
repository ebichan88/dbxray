package com.dbxray.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.TestCatalogs;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 利用者向けのツール仕様（{@code docs/usage/mcp-server-spec.yaml}）の引数が、
 * 実際のツール定義（入力スキーマ）と一致することを確かめる契約テスト<br>
 * ツールの引数を変えたのに仕様の更新が漏れると、このテストで検知する
 */
class ToolSpecContractTest {

  /** 入力スキーマの説明に埋め込まれた、数値・真偽値の既定値（例: 既定20） */
  private static final Pattern DEFAULT_IN_DESCRIPTION = Pattern.compile("既定(\\d+|true|false)");

  private static Map<String, Object> spec;
  private static List<Tool> tools;

  @BeforeAll
  static void readSpecAndTools() throws IOException {
    try (Reader reader =
        Files.newBufferedReader(
            Path.of(System.getProperty("mcpToolSpecFile")), StandardCharsets.UTF_8)) {
      spec = asMap(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
    }
    tools =
        new TableDefinitionTools(TestCatalogs.of(List.of()))
            .specifications().stream().map(SyncToolSpecification::tool).toList();
  }

  @Test
  @DisplayName("ツール: 仕様のツール名・titleは、実際のツールと同じ並びで一致する")
  void toolsMatch() {
    assertEquals(
        tools.stream().map(tool -> tool.name() + "/" + tool.title()).toList(),
        specTools().stream().map(tool -> tool.get("name") + "/" + tool.get("title")).toList());
  }

  @Test
  @DisplayName("引数: 仕様の引数は、実際の入力スキーマと名前・型・必須・範囲・列挙値が一致する")
  void parametersMatchInputSchemas() {
    for (final Tool tool : tools) {
      final Map<String, Object> properties = asMap(tool.inputSchema().get("properties"));
      final List<Map<String, Object>> parameters = parametersOf(tool.name());

      assertEquals(
          properties.keySet(),
          new HashSet<>(parameters.stream().map(p -> (String) p.get("name")).toList()),
          tool.name() + "の引数の名前");
      assertEquals(parameters.size(), properties.size(), tool.name() + "の引数に、同じ名前が複数ある");
      assertEquals(
          Set.copyOf(asList(tool.inputSchema().get("required"))),
          Set.copyOf(
              parameters.stream()
                  .filter(p -> Boolean.TRUE.equals(p.get("required")))
                  .map(p -> (String) p.get("name"))
                  .toList()),
          tool.name() + "の必須の引数");
      for (final Map<String, Object> parameter : parameters) {
        final String name = tool.name() + "." + parameter.get("name");
        final Map<String, Object> property = asMap(properties.get(parameter.get("name")));
        assertEquals(typeOf(property), parameter.get("type"), name + "の型");
        assertEquals(enumOf(property), enumOf(parameter), name + "の列挙値");
        assertEquals(boundOf(property, "minimum"), boundOf(parameter, "min"), name + "の最小値");
        assertEquals(boundOf(property, "maximum"), boundOf(parameter, "max"), name + "の最大値");
      }
    }
  }

  @Test
  @DisplayName("引数: 仕様の数値・真偽値の既定値は、入力スキーマの説明にある既定と一致する")
  void defaultsMatchInputSchemaDescriptions() {
    for (final Tool tool : tools) {
      final Map<String, Object> properties = asMap(tool.inputSchema().get("properties"));
      for (final Map<String, Object> parameter : parametersOf(tool.name())) {
        final Matcher matcher =
            DEFAULT_IN_DESCRIPTION.matcher(
                (String) asMap(properties.get(parameter.get("name"))).get("description"));
        final String codeDefault = matcher.find() ? matcher.group(1) : null;
        final Object specDefault = parameter.get("default");
        final boolean specDefaultIsChecked =
            specDefault instanceof Number || specDefault instanceof Boolean;
        if (codeDefault != null || specDefaultIsChecked) {
          assertEquals(
              codeDefault,
              specDefaultIsChecked ? String.valueOf(specDefault) : null,
              tool.name() + "." + parameter.get("name") + "の既定値");
        }
      }
    }
  }

  @Test
  @DisplayName("説明: 各ツールに概要・結果の説明があり、引数に説明がある")
  void everyToolAndParameterIsDescribed() {
    for (final Map<String, Object> tool : specTools()) {
      final String name = (String) tool.get("name");
      assertFalse(((String) tool.get("summary")).isBlank(), name + "のsummary");
      assertFalse(
          ((String) asMap(tool.get("result")).get("summary")).isBlank(), name + "のresult.summary");
      for (final Map<String, Object> parameter : parametersOf(name)) {
        assertFalse(
            ((String) parameter.get("description")).isBlank(),
            name + "." + parameter.get("name") + "のdescription");
      }
    }
  }

  @Test
  @DisplayName("概要: 利用者向けの文書の概要に、全ツールが載っている")
  void usageDocumentMentionsEveryTool() throws IOException {
    final String document =
        Files.readString(Path.of(System.getProperty("mcpUsageDocFile")), StandardCharsets.UTF_8);

    for (final Tool tool : tools) {
      assertTrue(document.contains("`" + tool.name() + "`"), tool.name() + "が概要に無い");
    }
  }

  private static List<Map<String, Object>> specTools() {
    return asList(spec.get("tools")).stream().map(ToolSpecContractTest::asMap).toList();
  }

  /** ツールの引数。{@code ref}で共通の引数を使っているものは、その定義に置き換える */
  private static List<Map<String, Object>> parametersOf(String toolName) {
    final Map<String, Object> common = asMap(spec.get("commonParameters"));
    final Map<String, Object> tool =
        specTools().stream()
            .filter(candidate -> toolName.equals(candidate.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("仕様に無いツール: " + toolName));
    final List<Map<String, Object>> parameters = new ArrayList<>();
    for (final Object entry : asList(tool.get("parameters"))) {
      final Map<String, Object> parameter = asMap(entry);
      final Object ref = parameter.get("ref");
      if (ref == null) {
        parameters.add(parameter);
        continue;
      }
      assertNotNull(common.get(ref), toolName + "が参照する共通の引数が無い: " + ref);
      parameters.add(asMap(common.get(ref)));
    }
    return parameters;
  }

  /** 入力スキーマの型を、仕様の{@code type}の書き方（{@code string[]}・{@code string|string[]}）で表す */
  private static String typeOf(Map<String, Object> property) {
    final Object type = property.get("type");
    if (type instanceof List<?> types) {
      assertEquals(List.of("string", "array"), types);
      assertEquals("string", asMap(property.get("items")).get("type"));
      return "string|string[]";
    }
    if ("array".equals(type)) {
      return asMap(property.get("items")).get("type") + "[]";
    }
    return (String) type;
  }

  /** 指定できる値。配列の場合は要素の値 */
  private static Set<String> enumOf(Map<String, Object> property) {
    final Object values =
        property.containsKey("items") && property.get("items") instanceof Map<?, ?> items
            ? items.get("enum")
            : property.get("enum");
    return values == null
        ? Set.of()
        : asList(values).stream().map(String::valueOf).collect(Collectors.toSet());
  }

  /** 範囲の端。{@code int}の上限（上限の指定が無いことを表す）は、指定なしとして扱う */
  private static Integer boundOf(Map<String, Object> source, String key) {
    final Object value = source.get(key);
    if (value == null || ((Number) value).intValue() == Integer.MAX_VALUE) {
      return null;
    }
    return ((Number) value).intValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    return (List<Object>) value;
  }
}
