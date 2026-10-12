package com.dbxray.domain.model.sidecar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TableAnnotation の正規化・アクセサ・孤児カラム検出に関するテスト */
public class TableAnnotationTest {

  @Test
  @DisplayName("コンパクトコンストラクタ: nullは空文字・空マップへ正規化される")
  void testNullNormalized() {
    var annotation = new TableAnnotation(null, null, null);
    assertEquals("", annotation.description());
    assertEquals("", annotation.remarks());
    assertTrue(annotation.columnRemarks().isEmpty());
    assertTrue(annotation.isEmpty());
  }

  @Test
  @DisplayName("columnRemark: 未登録の物理カラム名は空文字を返す")
  void testColumnRemarkFallback() {
    var annotation = new TableAnnotation("", "", Map.of("id", "主キー"));
    assertEquals("主キー", annotation.columnRemark("id"));
    assertEquals("", annotation.columnRemark("unknown"));
  }

  @Test
  @DisplayName("orphanColumnNames: 実在カラムに含まれない付帯情報側カラム名のみを、付帯情報の記述順に抽出する")
  void testOrphanColumnNames() {
    // 記述順は名前の順とは異なる並びにする（順序を保たない複製では並びが変わることを検知するため）
    var columnRemarks = new LinkedHashMap<String, String>();
    columnRemarks.put("zip_code", "旧カラムの備考1");
    columnRemarks.put("id", "主キー");
    columnRemarks.put("removed_col", "旧カラムの備考2");
    columnRemarks.put("address", "旧カラムの備考3");
    columnRemarks.put("note", "旧カラムの備考4");
    var annotation = new TableAnnotation("", "", columnRemarks);

    List<String> orphans = annotation.orphanColumnNames(Set.of("id", "name"));

    assertEquals(List.of("zip_code", "removed_col", "address", "note"), orphans);
  }

  @Test
  @DisplayName("isEmpty: 説明・備考・カラム備考のいずれかがあればfalse")
  void testIsEmpty() {
    assertTrue(TableAnnotation.EMPTY.isEmpty());
    assertFalse(new TableAnnotation("説明", "", Map.of()).isEmpty());
    assertFalse(new TableAnnotation("", "備考", Map.of()).isEmpty());
    assertFalse(new TableAnnotation("", "", Map.of("id", "備考")).isEmpty());
  }
}
