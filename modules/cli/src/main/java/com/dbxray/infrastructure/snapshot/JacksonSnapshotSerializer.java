package com.dbxray.infrastructure.snapshot;

import com.dbxray.domain.service.snapshot.SnapshotSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jacksonを用いた{@link SnapshotSerializer}の実装クラス<br>
 * recordはコンポーネントの宣言順に出力されるため、同じ内容からは常に同じ文字列が得られる。
 * 日本語等の非ASCII文字はエスケープせずそのまま出力する（git diffでの可読性のため）
 */
public class JacksonSnapshotSerializer implements SnapshotSerializer {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper =
      new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);

  /** {@inheritDoc} */
  @Override
  public String serialize(Object snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, Object> deserialize(String json) {
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("Failed to parse a snapshot line. [line=" + json + "]", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<String> formatForDiff(String json) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      return List.of(json);
    }
    if (!root.isObject()) {
      return List.of(json);
    }

    final List<String> lines = new ArrayList<>();
    lines.add("{");
    for (final Map.Entry<String, JsonNode> field : root.properties()) {
      final String key = writeCompact(TextNode.valueOf(field.getKey()));
      final JsonNode value = field.getValue();
      if (value.isArray()) {
        lines.add("  " + key + ": [");
        value.forEach(element -> lines.add("    " + writeCompact(element)));
        lines.add("  ]");
      } else {
        lines.add("  " + key + ": " + writeCompact(value));
      }
    }
    lines.add("}");
    return lines;
  }

  /**
   * JSONノードを、改行を含まない1行のJSON文字列へ変換するメソッド
   *
   * @return 1行のJSON文字列
   */
  private String writeCompact(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
