package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link ObjectCatalog}のテスト（関数・シーケンス・型の一覧と名前の解決。同じ組み立てを使うスキーマごとの数・トリガーの一覧も確かめる） */
class ObjectCatalogTest {

  @Nested
  @DisplayName("関数・シーケンス・ユーザー定義型・トリガー")
  class OtherObjects {

    private final SchemaCatalog catalog =
        SchemaCatalog.of(
            List.of(new DatabaseEntry("db1", "PostgreSQL", 16)),
            List.of(
                table("db1", "sales", "orders")
                    .trigger("trg_orders_audit", "sales.audit")
                    .trigger("trg_orders_touch", "sales.touch")
                    .build(),
                table("db1", "hr", "employee").trigger("trg_employee_audit", "sales.audit").build(),
                table("db1", "hr", "department").type("view").build()),
            List.of(
                function("db1", "sales", "calc_tax", "p_amount numeric"),
                procedure("db1", "sales", "audit"),
                function("db1", "sales", "calc_tax", "p_amount numeric, p_rate numeric"),
                function("db1", "hr", "calc_tax", "")),
            List.of(
                new SequenceEntry(
                    new ObjectKey("db1", "sales", "orders_id_seq"), "orders.id", "{}"),
                new SequenceEntry(new ObjectKey("db1", "sales", "invoice_no_seq"), null, "{}")),
            List.of(
                new TypeEntry(new ObjectKey("db1", "sales", "order_status"), "ENUM", "{}"),
                new TypeEntry(new ObjectKey("db1", "sales", "address"), "COMPOSITE", "{}")));

    @Test
    @DisplayName("スキーマごとの数に、関数（オーバーロードはそれぞれ）とそのうちのプロシージャ・シーケンス・型・トリガーも数える")
    void summarizesAllKinds() {
      assertEquals(
          List.of(
              new SchemaSummary("db1", "PostgreSQL", 16, "hr", 1, 1, 0, 1, 0, 0, 0, 1),
              new SchemaSummary("db1", "PostgreSQL", 16, "sales", 1, 0, 0, 3, 1, 2, 2, 2)),
          catalog.schemas());
    }

    @Test
    @DisplayName("関数は名前の順に、オーバーロードは並び順のまま一覧にし、名前の一部で絞り込める")
    void listsFunctions() {
      assertEquals(
          List.of(
              "hr.calc_tax()",
              "sales.calc_tax(p_amount numeric)",
              "sales.calc_tax(p_amount numeric, p_rate numeric)"),
          catalog.functions().list(SearchScope.ALL, NameFilter.of("CALC")).stream()
              .flatMap(function -> function.overloads().stream())
              .map(f -> f.key().qualifiedName() + "(" + f.arguments() + ")")
              .toList());
    }

    @Test
    @DisplayName("関数のオーバーロードは、名前の解決では1つとみなす")
    void resolvesOverloadsAsOne() {
      final FunctionOverloads calcTax =
          Lookups.found(
              catalog.functions().lookup(ObjectReference.of(null, null, "sales.calc_tax")));

      assertEquals(2, calcTax.overloads().size());
      assertEquals(
          List.of("hr.calc_tax", "sales.calc_tax"),
          Lookups.candidates(catalog.functions().lookup(ObjectReference.of(null, null, "calc_tax")))
              .stream()
              .map(f -> f.key().qualifiedName())
              .toList());
    }

    @Test
    @DisplayName("関数・シーケンス・型が見つからない場合は、名前の一部に指定を含むものを候補にする")
    void suggestsByPartialName() {
      assertEquals(
          List.of("sales.orders_id_seq"),
          Lookups.suggestions(catalog.sequences().lookup(ObjectReference.of(null, null, "orders")))
              .stream()
              .map(sequence -> sequence.key().qualifiedName())
              .toList());
      assertTrue(
          Lookups.suggestions(catalog.types().lookup(ObjectReference.of(null, null, "invoice")))
              .isEmpty());
    }

    @Test
    @DisplayName("シーケンス・型を名前の順に一覧にする（型の種別での絞り込みはlist_typesのツールが行う）")
    void listsSequencesAndTypes() {
      assertEquals(
          List.of("invoice_no_seq", "orders_id_seq"),
          catalog.sequences().list(SearchScope.ALL, NameFilter.ALL).stream()
              .map(sequence -> sequence.key().name())
              .toList());
      assertEquals(
          List.of("address", "order_status"),
          catalog.types().list(SearchScope.ALL, NameFilter.ALL).stream()
              .map(type -> type.key().name())
              .toList());
    }

    @Test
    @DisplayName("トリガーを、テーブル名の順にテーブルをまたいで一覧にする")
    void listsTriggers() {
      assertEquals(
          List.of(
              "employee.trg_employee_audit", "orders.trg_orders_audit", "orders.trg_orders_touch"),
          catalog.tables().triggers(SearchScope.ALL).stream()
              .map(found -> found.table().key().name() + "." + found.trigger().name())
              .toList());
    }

    private static FunctionEntry function(
        String database, String schema, String name, String arguments) {
      return new FunctionEntry(
          new ObjectKey(database, schema, name), "FUNCTION", arguments, "void", "sql", "{}", "");
    }

    private static FunctionEntry procedure(String database, String schema, String name) {
      return new FunctionEntry(
          new ObjectKey(database, schema, name), "PROCEDURE", "", "", "plpgsql", "{}", "");
    }
  }
}
