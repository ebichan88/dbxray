package com.dbxray.domain.model.viewpoint;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Viewpoints のテーブルから観点への逆引きに関するテスト */
public class ViewpointsTest {

  private static TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "", "");
  }

  @Test
  @DisplayName("of: テーブルが所属する観点を宣言順に返し、所属する観点が無い場合は空")
  void testOfTable() {
    Viewpoint order = Viewpoint.of("order", "受注管理", "", List.of("sales.order*", "customer"));
    Viewpoint master = Viewpoint.of("master", "マスタ", "", List.of("customer", "product"));
    Viewpoints viewpoints = Viewpoints.of(List.of(order, master));

    assertEquals(List.of(order, master), viewpoints.containing(table("sales", "customer")));
    assertEquals(List.of(order), viewpoints.containing(table("sales", "orders")));
    assertEquals(List.of(), viewpoints.containing(table("sales", "stock")));
  }

  @Test
  @DisplayName("empty: 観点を持たない")
  void testEmpty() {
    assertTrue(Viewpoints.empty().isEmpty());
    assertEquals(List.of(), Viewpoints.empty().asList());
    assertEquals(List.of(), Viewpoints.empty().containing(table("sales", "orders")));
  }

  @Test
  @DisplayName("stream・size: 宣言順のストリームと件数を返す")
  void testStreamAndSize() {
    Viewpoint order = Viewpoint.of("order", "受注管理", "", List.of("sales.order*"));
    Viewpoint master = Viewpoint.of("master", "マスタ", "", List.of("customer"));
    Viewpoints viewpoints = Viewpoints.of(List.of(order, master));

    assertEquals(2, viewpoints.size());
    assertEquals(List.of(order, master), viewpoints.stream().toList());
    assertEquals(0, Viewpoints.empty().size());
  }
}
