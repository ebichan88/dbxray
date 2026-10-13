package com.dbxray.mcp.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TableCatalog#findSimilar}（{@link SimilarTableFinder}）のテスト */
class SimilarTableFinderTest {

  private static final String[] ORDER_DETAIL = {
    "id", "created_at", "order_id", "item_no", "qty", "unit_price"
  };

  /** 全テーブルが持つ汎用のカラム（id・created_at・updated_at）の重みを下げるための、他のテーブル */
  private static List<TableEntry> fillers() {
    return IntStream.rangeClosed(1, 6)
        .mapToObj(
            number ->
                tableOf("filler" + number, "id", "created_at", "updated_at", "filler_" + number))
        .toList();
  }

  private static TableEntry tableOf(String name, String... columns) {
    return tableOf(TestTables.table(name), columns);
  }

  private static TableEntry tableOf(TestTables table, String... columns) {
    for (final String column : columns) {
      table.column(column);
    }
    return table.build();
  }

  private static SchemaCatalog catalogOf(TableEntry... tables) {
    final List<TableEntry> all = new ArrayList<>(fillers());
    all.addAll(List.of(tables));
    return TestCatalogs.of(all);
  }

  private static TableEntry find(SchemaCatalog catalog, String name) {
    return catalog.tables().all().stream()
        .filter(table -> table.key().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static SimilarTables similarTo(
      SchemaCatalog catalog, String name, int minShared, int minSimilarity) {
    return similarTo(catalog, name, minShared, minSimilarity, 10);
  }

  private static SimilarTables similarTo(
      SchemaCatalog catalog, String name, int minShared, int minSimilarity, int limit) {
    final TableEntry base = find(catalog, name);
    return catalog
        .tables()
        .findSimilar(
            SimilarTableQuery.ofTable(base, minShared, minSimilarity),
            new SearchScope(base.key().database(), ""),
            limit);
  }

  private static List<String> names(SimilarTables result) {
    return result.tables().stream().map(similar -> similar.table().key().name()).toList();
  }

  @Test
  @DisplayName("findSimilar: カラムが同じテーブルを、類似度100のidenticalとして返す")
  void findsIdenticalTable() {
    final SchemaCatalog catalog =
        catalogOf(tableOf("order_detail", ORDER_DETAIL), tableOf("order_detail_bk", ORDER_DETAIL));

    final SimilarTables result = similarTo(catalog, "order_detail", 3, 50);

    assertEquals(1, result.total());
    final SimilarTable found = result.tables().get(0);
    assertEquals("order_detail_bk", found.table().key().name());
    assertEquals(100, found.similarity());
    assertEquals(SimilarityKind.IDENTICAL, found.kind());
    assertEquals(6, found.sharedColumns());
    assertTrue(found.onlyInBase().isEmpty());
    assertTrue(found.onlyInOther().isEmpty());
  }

  @Test
  @DisplayName("findSimilar: 相手が基準の全カラムに追加のカラムを持てばsuperset、相手が基準の一部だけならsubsetとして返す")
  void classifiesSupersetAndSubset() {
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("order_detail", ORDER_DETAIL),
            tableOf(
                "order_detail_hist",
                "id",
                "created_at",
                "order_id",
                "item_no",
                "qty",
                "unit_price",
                "valid_from",
                "valid_to"));

    final SimilarTable superset = similarTo(catalog, "order_detail", 3, 50).tables().get(0);
    assertEquals("order_detail_hist", superset.table().key().name());
    assertEquals(SimilarityKind.SUPERSET, superset.kind());
    assertEquals(List.of("valid_from", "valid_to"), superset.onlyInOther());
    assertTrue(superset.onlyInBase().isEmpty());
    assertTrue(superset.similarity() > 50 && superset.similarity() < 100);

    final SimilarTable subset = similarTo(catalog, "order_detail_hist", 3, 50).tables().get(0);
    assertEquals("order_detail", subset.table().key().name());
    assertEquals(SimilarityKind.SUBSET, subset.kind());
    assertEquals(List.of("valid_from", "valid_to"), subset.onlyInBase());
    assertTrue(subset.onlyInOther().isEmpty());
  }

  @Test
  @DisplayName("findSimilar: 基準にだけ、相手にだけあるカラムがともにあればoverlapとして返し、条件の類似度に満たなければ返さない")
  void classifiesOverlapAndAppliesMinSimilarity() {
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("order_detail", ORDER_DETAIL),
            tableOf("partial", "order_id", "item_no", "qty", "memo", "remarks", "flag"));

    assertEquals(List.of(), names(similarTo(catalog, "order_detail", 3, 90)));

    final SimilarTable overlap = similarTo(catalog, "order_detail", 3, 10).tables().get(0);
    assertEquals(SimilarityKind.OVERLAP, overlap.kind());
    assertEquals(3, overlap.sharedColumns());
    assertEquals(List.of("id", "created_at", "unit_price"), overlap.onlyInBase());
    assertEquals(List.of("memo", "remarks", "flag"), overlap.onlyInOther());
  }

  @Test
  @DisplayName("findSimilar: 全テーブルが持つ汎用のカラムだけを共有するテーブルは、似ているとみなさない")
  void ignoresGenericColumns() {
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("customer", "id", "created_at", "updated_at", "customer_name"),
            tableOf("product", "id", "created_at", "updated_at", "product_name"));

    assertEquals(List.of(), names(similarTo(catalog, "customer", 3, 50)));
  }

  @Test
  @DisplayName("findSimilar: 共通のカラムが下限に満たないテーブルは、類似度が高くても返さない")
  void appliesMinShared() {
    final SchemaCatalog catalog =
        catalogOf(tableOf("pair", "pair_left", "pair_right"), tableOf("pair_bk", "pair_left", "pair_right"));

    assertEquals(List.of(), names(similarTo(catalog, "pair", 3, 50)));
    assertEquals(List.of("pair_bk"), names(similarTo(catalog, "pair", 2, 50)));
  }

  @Test
  @DisplayName("findSimilar: 基準と外部キー・論理リレーションで直接つながるテーブルは、どちらの向きでも返さない")
  void excludesDirectlyRelatedTables() {
    final String[] columns = {"customer_id", "customer_name", "kana", "tel"};
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("customer", columns),
            tableOf("customer_bk", columns),
            tableOf(
                TestTables.table("customer_fk").foreignKey("customer_id", "customer", "customer_id"),
                columns),
            tableOf(
                TestTables.table("customer_lr")
                    .logicalRelation("customer_id", "customer", "customer_id"),
                columns));

    assertEquals(List.of("customer_bk"), names(similarTo(catalog, "customer", 3, 50)));
    assertEquals(
        List.of("customer_bk", "customer_lr"), names(similarTo(catalog, "customer_fk", 3, 50)));
  }

  @Test
  @DisplayName("findSimilar: 区分がtableでないもの（ビュー・マテリアライズドビュー）と基準自身は返さない")
  void excludesViewsAndBase() {
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("order_detail", ORDER_DETAIL),
            tableOf(TestTables.table("order_detail_view").type("view"), ORDER_DETAIL),
            tableOf(TestTables.table("order_detail_mv").type("materialized_view"), ORDER_DETAIL));

    assertEquals(List.of(), names(similarTo(catalog, "order_detail", 3, 50)));
  }

  @Test
  @DisplayName("findSimilar: 基準のDBの全スキーマから探し、別のDBのテーブルは返さない")
  void searchesWholeDatabaseOfBase() {
    final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                tableOf(TestTables.table("db1", "sales", "order_detail"), ORDER_DETAIL),
                tableOf(TestTables.table("db1", "archive", "order_detail"), ORDER_DETAIL),
                tableOf(TestTables.table("db2", "sales", "order_detail"), ORDER_DETAIL)));

    final SimilarTables result = similarTo(catalog, "order_detail", 3, 50);

    assertEquals(1, result.total());
    assertEquals(
        new ObjectKey("db1", "archive", "order_detail"), result.tables().get(0).table().key());
  }

  @Test
  @DisplayName("findSimilar: カラム名で指定した場合、大文字小文字・全角半角の違いと重複を吸収し、DB・スキーマの範囲で絞り込む")
  void searchesByColumnNames() {
    final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                tableOf(TestTables.table("db1", "sales", "order_detail"), ORDER_DETAIL),
                tableOf(TestTables.table("db2", "sales", "order_detail"), ORDER_DETAIL)));
    final SimilarTableQuery query =
        SimilarTableQuery.ofColumns(
            List.of("ＯＲＤＥＲ＿ＩＤ", "Item_No", "ITEM_NO", "QTY", "memo"), 3, 10);

    final SimilarTables all = catalog.tables().findSimilar(query, SearchScope.ALL, 10);
    assertEquals(
        List.of(
            new ObjectKey("db1", "sales", "order_detail"),
            new ObjectKey("db2", "sales", "order_detail")),
        all.tables().stream().map(similar -> similar.table().key()).toList());
    final SimilarTable found = all.tables().get(0);
    assertEquals(3, found.sharedColumns(), "重複したItem_No・ITEM_NOは1つとみなす");
    assertEquals(List.of("memo"), found.onlyInBase());
    assertEquals(List.of("id", "created_at", "unit_price"), found.onlyInOther());
    assertEquals(SimilarityKind.OVERLAP, found.kind());

    assertEquals(
        List.of(new ObjectKey("db2", "sales", "order_detail")),
        catalog.tables().findSimilar(query, new SearchScope("db2", "SALES"), 10).tables().stream()
            .map(similar -> similar.table().key())
            .toList());
    assertEquals(0, catalog.tables().findSimilar(query, new SearchScope("db2", "other"), 10).total());
  }

  @Test
  @DisplayName("findSimilar: 共通のカラムで型が異なるものを返す（型の表記の大文字小文字の違いと、型が未設定のカラムは含めない）")
  void reportsTypeMismatches() {
    final SchemaCatalog catalog =
        TestCatalogs.of(
            List.of(
                tableOf(
                    TestTables.table("base")
                        .column(new ColumnEntry("order_id", null, "INTEGER", true, true, null, null))
                        .column(new ColumnEntry("qty", null, "integer", false, false, null, null))
                        .column(new ColumnEntry("note", null, "text", false, false, null, null)),
                    "price"),
                tableOf(
                    TestTables.table("other")
                        .column(new ColumnEntry("order_id", null, "integer", true, true, null, null))
                        .column(
                            new ColumnEntry("qty", null, "numeric(10,2)", false, false, null, null))
                        .column(new ColumnEntry("note", null, null, false, false, null, null)),
                    "price")));

    final SimilarTable found = similarTo(catalog, "base", 3, 50).tables().get(0);

    assertEquals(
        List.of(new SimilarTable.TypeMismatch("qty", "integer", "numeric(10,2)")),
        found.typeMismatches());
    final SimilarTables byNames =
        catalog
            .tables()
            .findSimilar(
                SimilarTableQuery.ofColumns(List.of("order_id", "qty", "note"), 3, 50),
                SearchScope.ALL,
                10);
    assertEquals(2, byNames.total());
    assertTrue(
        byNames.tables().stream().allMatch(similar -> similar.typeMismatches().isEmpty()),
        "カラム名で指定した場合は、基準の型が分からないため型の違いを返さない");
  }

  @Test
  @DisplayName("findSimilar: 類似度・共通のカラム数が同じ場合は名前の順に並べ、件数の上限で切り捨てても総数は切り捨て前の数を返す")
  void ordersAndLimits() {
    final SchemaCatalog catalog =
        catalogOf(
            tableOf("order_detail", ORDER_DETAIL),
            tableOf("order_detail_bk3", ORDER_DETAIL),
            tableOf("order_detail_bk1", ORDER_DETAIL),
            tableOf("order_detail_bk2", ORDER_DETAIL),
            tableOf("order_detail_ext", "order_id", "item_no", "qty", "unit_price", "extra"));

    final SimilarTables limited = similarTo(catalog, "order_detail", 3, 10, 2);

    assertEquals(4, limited.total());
    assertEquals(List.of("order_detail_bk1", "order_detail_bk2"), names(limited));
    assertEquals(
        List.of(
            "order_detail_bk1", "order_detail_bk2", "order_detail_bk3", "order_detail_ext"),
        names(similarTo(catalog, "order_detail", 3, 10)),
        "類似度の高い順");
  }

  @Test
  @DisplayName("findSimilar: カラムの無いテーブルが基準でも、空の結果を返す")
  void returnsNothingForBaseWithoutColumns() {
    final SchemaCatalog catalog = catalogOf(tableOf("empty"));

    final SimilarTables result = similarTo(catalog, "empty", 1, 10);

    assertEquals(0, result.total());
    assertTrue(result.tables().isEmpty());
  }

  @Test
  @DisplayName("ofColumns: カラム名が1つも無い場合は例外を投げる")
  void rejectsEmptyColumnNames() {
    assertThrows(IllegalArgumentException.class, () -> SimilarTableQuery.ofColumns(List.of(), 3, 50));
  }
}
