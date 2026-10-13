package com.dbxray.mcp.catalog;

import static com.dbxray.mcp.catalog.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbxray.mcp.catalog.FunctionTableUsageEntry.UsedTable;
import com.dbxray.mcp.catalog.TableImpact.IndirectReference;
import com.dbxray.mcp.catalog.TableImpact.SequenceUse;
import com.dbxray.mcp.catalog.TableImpact.TypeUse;
import com.dbxray.mcp.catalog.TableImpact.ViewReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SchemaCatalog#impactOf}（{@link ImpactAnalyzer}）のテスト */
class ImpactAnalyzerTest {

  private static final String HASH = "h";

  private static ObjectKey key(String name) {
    return new ObjectKey("testdb", "sample", name);
  }

  private static SchemaCatalog catalogOf(
      List<TableEntry> tables,
      List<FunctionEntry> functions,
      List<FunctionTableUsageEntry> usages,
      List<TypeEntry> types) {
    final List<String> databases = new ArrayList<>();
    tables.forEach(table -> databases.add(table.key().database()));
    functions.forEach(function -> databases.add(function.key().database()));
    return SchemaCatalog.of(
            databases.stream()
                .distinct()
                .map(name -> new DatabaseEntry(name, "PostgreSQL", 16))
                .toList(),
            tables,
            functions,
            List.of(),
            types)
        .withFunctionTableUsages(usages);
  }

  private static SchemaCatalog catalogOf(List<TableEntry> tables) {
    return catalogOf(tables, List.of(), List.of(), List.of());
  }

  private static TableEntry find(SchemaCatalog catalog, String name) {
    return catalog.tables().all().stream()
        .filter(entry -> entry.key().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static TableImpact impactOf(SchemaCatalog catalog, String table) {
    return catalog.impactOf(find(catalog, table));
  }

  private static List<String> names(List<ViewReference> views) {
    return views.stream()
        .map(view -> view.view().key().name() + (view.via() == null ? "" : "@" + view.via().name()))
        .toList();
  }

  @Test
  @DisplayName("impactOf: 外部キー・論理リレーションの参照元を、段1の関連と間接のテーブルに分け、impactの数と一致する")
  void splitsDirectAndIndirectReferences() {
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee").foreignKey("manager_id", "employee", "employee_id").build(),
                table("attendance").foreignKey("employee_id", "employee", "employee_id").build(),
                table("audit_log").logicalRelation("record_id", "employee", "employee_id").build(),
                table("overtime")
                    .foreignKey("employee_id", "employee", "employee_id")
                    .foreignKey("attendance_id", "attendance", "attendance_id")
                    .build(),
                table("attendance_note")
                    .foreignKey("attendance_id", "attendance", "attendance_id")
                    .build(),
                table("note_detail").foreignKey("note_id", "attendance_note", "note_id").build(),
                table("note_comment").foreignKey("detail_id", "note_detail", "detail_id").build(),
                table("unrelated").build()));

    final TableImpact impact = impactOf(catalog, "employee");

    assertEquals(
        List.of("attendance", "audit_log", "employee", "overtime"),
        impact.directReferences().stream().map(relation -> relation.from().name()).toList(),
        "参照元のテーブルの順。自己参照も段1の関連に含む");
    assertEquals(
        List.of(RelationKind.FOREIGN_KEY, RelationKind.LOGICAL_RELATION),
        impact.directReferences().subList(0, 2).stream().map(Relation::kind).toList());
    assertEquals(
        List.of(
            new IndirectReference(key("attendance_note"), 2, key("attendance")),
            new IndirectReference(key("note_detail"), 3, key("attendance_note"))),
        impact.indirectReferences(),
        "段1に現れるovertimeは間接に含めない。3段を超えるnote_commentは含めない");
    assertEquals(
        catalog.relations().counts(find(catalog, "employee")).impact(),
        impact.directReferences().stream()
                .map(relation -> relation.from())
                .filter(from -> !from.equals(key("employee")))
                .distinct()
                .count()
            + impact.indirectReferences().size(),
        "list_tablesのimpact（自テーブルを除く、重複しないテーブルの数）と一致する");
  }

  @Test
  @DisplayName("impactOf: 関連もビューも関数も無いテーブルは、すべて空で返す")
  void returnsEmptyForIsolatedTable() {
    final TableImpact impact = impactOf(catalogOf(List.of(table("memo").build())), "memo");

    assertTrue(impact.directReferences().isEmpty());
    assertTrue(impact.indirectReferences().isEmpty());
    assertTrue(impact.views().isEmpty());
    assertTrue(impact.triggers().isEmpty());
    assertTrue(impact.functions().usages().isEmpty());
    assertEquals(new FunctionCoverage(0, 0, 0, 0, 0, 0, 0, 0), impact.functions().coverage());
    assertTrue(impact.sequences().isEmpty());
    assertTrue(impact.types().isEmpty());
  }

  @Test
  @DisplayName("impactOf: ビューを参照するビュー・マテリアライズドビューも段ごとに求め、経由したビューを示す。ビューが循環していても止まる")
  void collectsViewsTransitively() {
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee").build(),
                table("v1").type("view").referencedTable("employee").referencedTable("v2").build(),
                table("v2").type("view").referencedTable("v1").build(),
                table("mv").type("materialized_view").referencedTable("employee").build(),
                table("v3").type("view").referencedTable("mv").build(),
                table("other").type("view").referencedTable("memo").build()));

    final TableImpact impact = impactOf(catalog, "employee");

    assertEquals(
        List.of("mv", "v1", "v2@v1", "v3@mv"),
        names(impact.views()),
        "段ごとにテーブル名の順。自テーブルを直接参照するビューにはviaを付けない");
  }

  @Test
  @DisplayName("impactOf: ビューを調べた場合は、そのビューを参照するビューと、INSTEAD OFトリガーを含む自身のトリガーを返す")
  void analyzesView() {
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee").build(),
                table("v1")
                    .type("view")
                    .referencedTable("employee")
                    .trigger("trg_v1_insert", "sample.view_insert")
                    .build(),
                table("v2").type("view").referencedTable("v1").build()));

    final TableImpact impact = impactOf(catalog, "v1");

    assertEquals(List.of("v2"), names(impact.views()));
    assertEquals(
        List.of("trg_v1_insert"), impact.triggers().stream().map(TriggerEntry::name).toList());
  }

  @Test
  @DisplayName("impactOf: テーブルを使う関数を、関数名ごとに操作・オーバーロード・動的SQL・実行するトリガーとともに求める")
  void findsFunctionsUsingTable() {
    final List<FunctionEntry> functions = new ArrayList<>();
    final List<FunctionTableUsageEntry> usages = new ArrayList<>();
    add(
        functions,
        usages,
        function("PROCEDURE", "close_project", "IN p integer"),
        used("sample", "employee", "U"));
    add(
        functions,
        usages,
        function("FUNCTION", "count_rows", "t text"),
        List.of("EXECUTE"),
        candidate("employee", "search_path", "R", "other", "sample"));
    add(
        functions,
        usages,
        function("FUNCTION", "other_schema_only", ""),
        candidate("employee", "search_path", "R", "other"));
    add(
        functions,
        usages,
        function("FUNCTION", "case_sensitive", ""),
        used("sample", "Employee", "R"));
    add(
        functions,
        usages,
        function("FUNCTION", "mixed_match", ""),
        used("sample", "employee", "R"),
        candidate("employee", "search_path", "D", "sample"));
    add(
        functions,
        usages,
        function("FUNCTION", "mixed_overloads", "a integer"),
        used("sample", "employee", "R"));
    add(
        functions,
        usages,
        function("FUNCTION", "mixed_overloads", "a text"),
        candidate("employee", "synonym", "D", "sample"));
    add(
        functions,
        usages,
        function("FUNCTION", "raise_bonus", "a integer"),
        used("sample", "employee", "R"));
    add(
        functions,
        usages,
        function("FUNCTION", "raise_bonus", "a text"),
        used("sample", "employee", "UR"));
    add(
        functions,
        usages,
        function("FUNCTION", "kinds_differ", "a integer"),
        used("sample", "employee", "R"));
    add(
        functions,
        usages,
        function("PROCEDURE", "kinds_differ", "a text"),
        used("sample", "employee", "R"));
    add(functions, usages, function("FUNCTION", "view_reader", ""), used("sample", "v1", "R"));
    add(
        functions,
        usages,
        function("FUNCTION", "uses_both", ""),
        used("sample", "employee", "C"),
        used("sample", "v1", "U"));
    add(
        functions,
        usages,
        function("db2", "FUNCTION", "other_database", ""),
        used("sample", "employee", "R"));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee").build(),
                table("v1")
                    .type("view")
                    .referencedTable("employee")
                    .trigger("trg_v1_insert", "sample.view_reader")
                    .build()),
            functions,
            usages,
            List.of());

    final List<FunctionUse> uses = impactOf(catalog, "employee").functions().usages();

    assertEquals(
        List.of(
            "close_project:U",
            "count_rows:R?",
            "kinds_differ:R",
            "mixed_match:R",
            "mixed_overloads:RD",
            "raise_bonus:RU",
            "uses_both:C",
            "uses_both:U@v1",
            "view_reader:R@v1"),
        uses.stream()
            .map(
                use ->
                    use.function().name()
                        + ":"
                        + String.join("", use.operations())
                        + (use.possible() ? "?" : "")
                        + (use.via() == null ? "" : "@" + use.via().name()))
            .toList(),
        "関数のキー順。同じ関数は自テーブル、ビューの順。完全一致のみ。確定を優先。別DBの関数・候補に自スキーマが無い関数は含めない");
    final FunctionUse closeProject = use(uses, "close_project");
    assertEquals("PROCEDURE", closeProject.kind());
    assertEquals(List.of("IN p integer"), closeProject.overloads());
    assertEquals("", closeProject.schemaResolution());
    final FunctionUse countRows = use(uses, "count_rows");
    assertTrue(countRows.possible());
    assertEquals("search_path", countRows.schemaResolution());
    assertEquals(List.of("EXECUTE"), countRows.dynamicSql());
    assertEquals(
        List.of("a integer", "a text"),
        use(uses, "raise_bonus").overloads(),
        "同名のオーバーロードは1つにまとめ、一致した引数を並べる");
    assertEquals("", use(uses, "kinds_differ").kind(), "種別が異なるオーバーロードをまとめた場合は空");
    final FunctionUse mixedOverloads = use(uses, "mixed_overloads");
    assertFalse(mixedOverloads.possible(), "確定で一致したオーバーロードがあれば、候補だけとは言わない");
    assertEquals("", mixedOverloads.schemaResolution());
    final FunctionUse viaView = use(uses, "view_reader");
    assertEquals(
        List.of("v1.trg_v1_insert"),
        viaView.calledByTriggers().stream()
            .map(found -> found.table().key().name() + "." + found.trigger().name())
            .toList());
  }

  private static FunctionUse use(List<FunctionUse> uses, String name) {
    return uses.stream()
        .filter(use -> use.function().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("impactOf: 関数の参考情報の状態（解析済み・解析不可・無い・古い）と、動的SQL・読み切れなかった関数、関数名を持たないトリガーを数える")
  void countsFunctionCoverage() {
    final List<FunctionEntry> functions = new ArrayList<>();
    final List<FunctionTableUsageEntry> usages = new ArrayList<>();
    add(functions, usages, function("FUNCTION", "f_ok", ""));
    add(functions, usages, function("FUNCTION", "f_dynamic", ""), List.of("EXECUTE"));
    final FunctionEntry incomplete = function("FUNCTION", "f_incomplete", "");
    functions.add(incomplete);
    usages.add(
        usageOf(incomplete, FunctionTableUsageEntry.ANALYZED, HASH, List.of(), List.of(), true));
    final FunctionEntry unsupported = function("FUNCTION", "f_unsupported", "");
    functions.add(unsupported);
    usages.add(usageOf(unsupported, "unsupported_language", HASH, List.of(), List.of(), false));
    functions.add(function("FUNCTION", "f_none", ""));
    final FunctionEntry stale = function("FUNCTION", "f_stale", "");
    functions.add(stale);
    usages.add(
        usageOf(stale, FunctionTableUsageEntry.ANALYZED, "old", List.of(), List.of(), false));
    add(functions, usages, function("db2", "FUNCTION", "other_database", ""));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee")
                    .trigger("trg_with_function", "sample.log_change")
                    .trigger("trg_body_only", "")
                    .build(),
                table("testdb", "sample", "department").trigger("trg_other_body_only", "").build(),
                table("db2", "sample", "employee").trigger("trg_db2_body_only", "").build(),
                table("TESTDB", "sample", "employee")
                    .trigger("trg_upper_db_body_only", "")
                    .build()),
            functions,
            usages,
            List.of());

    assertEquals(
        new FunctionCoverage(6, 3, 1, 1, 1, 1, 1, 2),
        impactOf(catalog, "employee").functions().coverage(),
        "別DBの関数・トリガーは数えない。関数名を持たないトリガーはDB全体で数える");
  }

  @Test
  @DisplayName(
      "impactOf: 自テーブルを使うか確かめきれない関数を、理由の優先順・関数のキー・引数の順に返す。自テーブルかそのビューを使うと分かったオーバーロード・別DBの関数は含めない")
  void listsUnverifiedFunctions() {
    final List<FunctionEntry> functions = new ArrayList<>();
    final List<FunctionTableUsageEntry> usages = new ArrayList<>();
    // 動的SQLを含むが、自テーブルを使うと分かったオーバーロードは含めない。同じ関数の別のオーバーロードは含める
    add(
        functions,
        usages,
        function("FUNCTION", "mixed", "a integer"),
        List.of("EXECUTE"),
        used("sample", "employee", "R"));
    add(functions, usages, function("FUNCTION", "mixed", "a text"), List.of("EXECUTE"));
    add(
        functions,
        usages,
        function("FUNCTION", "via_view_dynamic", ""),
        List.of("EXECUTE"),
        used("sample", "v1", "R"));
    add(functions, usages, function("FUNCTION", "b_dynamic", ""), List.of("EXECUTE"));
    add(functions, usages, function("FUNCTION", "a_dynamic", "z integer"), List.of("EXECUTE"));
    add(functions, usages, function("FUNCTION", "a_dynamic", "a integer"), List.of("EXECUTE"));
    // 動的SQLも読み残しもある場合は、先に並ぶ動的SQLを採る
    final FunctionEntry both = function("FUNCTION", "both", "");
    functions.add(both);
    usages.add(
        usageOf(both, FunctionTableUsageEntry.ANALYZED, HASH, List.of(), List.of("EXECUTE"), true));
    final FunctionEntry incomplete = function("FUNCTION", "incomplete_only", "");
    functions.add(incomplete);
    usages.add(
        usageOf(incomplete, FunctionTableUsageEntry.ANALYZED, HASH, List.of(), List.of(), true));
    final FunctionEntry unsupported = function("FUNCTION", "unsupported", "");
    functions.add(unsupported);
    usages.add(usageOf(unsupported, "unsupported_language", HASH, List.of(), List.of(), false));
    final FunctionEntry stale = function("FUNCTION", "stale", "");
    functions.add(stale);
    usages.add(
        usageOf(stale, FunctionTableUsageEntry.ANALYZED, "old", List.of(), List.of(), false));
    functions.add(function("FUNCTION", "no_insight", ""));
    add(functions, usages, function("FUNCTION", "clean", ""));
    add(functions, usages, function("db2", "FUNCTION", "other_database", ""), List.of("EXECUTE"));
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee").build(),
                table("v1").type("view").referencedTable("employee").build()),
            functions,
            usages,
            List.of());

    final List<TableImpact.UnverifiedFunction> unverified =
        impactOf(catalog, "employee").functions().unverified();

    assertEquals(
        List.of(
            "DYNAMIC_SQL:a_dynamic(a integer)",
            "DYNAMIC_SQL:a_dynamic(z integer)",
            "DYNAMIC_SQL:b_dynamic()",
            "DYNAMIC_SQL:both()",
            "DYNAMIC_SQL:mixed(a text)",
            "INCOMPLETE:incomplete_only()",
            "UNSUPPORTED:unsupported()",
            "STALE:stale()",
            "NO_INSIGHT:no_insight()"),
        unverified.stream()
            .map(
                found ->
                    found.reason().name()
                        + ":"
                        + found.function().name()
                        + "("
                        + found.arguments()
                        + ")")
            .toList());
    assertEquals(
        List.of("dynamicSql", "incomplete", "unsupported", "stale", "noInsight"),
        List.of(TableImpact.UnverifiedFunction.Reason.values()).stream()
            .map(TableImpact.UnverifiedFunction.Reason::value)
            .toList(),
        "ツールの結果で使う値は、理由の優先順に並ぶ");
  }

  @Test
  @DisplayName("impactOf: カラムのデフォルト値のnextvalが使うシーケンスと、カラムの型に使われているスナップショットにあるユーザー定義型を、使うカラムとともに返す")
  void collectsSequencesAndTypes() {
    final TypeEntry status = new TypeEntry(key("status_enum"), "ENUM", "{}");
    final TypeEntry address = new TypeEntry(key("address_type"), "COMPOSITE", "{}");
    final SchemaCatalog catalog =
        catalogOf(
            List.of(
                table("employee")
                    .column(
                        column(
                            "employee_id",
                            "integer",
                            "nextval('sample.employee_id_seq'::regclass)"))
                    .column(column("code", "integer", "nextval('code_seq')"))
                    .column(column("sub_code", "integer", "nextval('code_seq'::regclass)"))
                    .column(column("status", "sample.status_enum", ""))
                    .column(column("past_statuses", "sample.status_enum[]", ""))
                    .column(column("home", "sample.address_type", ""))
                    .column(column("outside", "sample.not_exported", ""))
                    .column(column("name", "text", ""))
                    .column(column("untyped", "", ""))
                    .build()),
            List.of(),
            List.of(),
            List.of(status, address));

    final TableImpact impact = impactOf(catalog, "employee");

    assertEquals(
        List.of(
            new SequenceUse(key("code_seq"), List.of("code", "sub_code")),
            new SequenceUse(key("employee_id_seq"), List.of("employee_id"))),
        impact.sequences(),
        "シーケンスの順。スキーマ修飾の無い名前は自テーブルと同じスキーマとみなす");
    assertEquals(
        List.of(
            new TypeUse(address, List.of("home")),
            new TypeUse(status, List.of("status", "past_statuses"))),
        impact.types(),
        "型の順。配列の型も含む。スナップショットに無い型は含めない");
  }

  private static ColumnEntry column(String name, String type, String defaultValue) {
    return new ColumnEntry(name, null, type, false, false, defaultValue, null);
  }

  private static FunctionEntry function(String kind, String name, String arguments) {
    return function("testdb", kind, name, arguments);
  }

  private static FunctionEntry function(
      String database, String kind, String name, String arguments) {
    return new FunctionEntry(
        new ObjectKey(database, "sample", name), kind, arguments, "void", "plpgsql", "{}", HASH);
  }

  private static void add(
      List<FunctionEntry> functions,
      List<FunctionTableUsageEntry> usages,
      FunctionEntry function,
      UsedTable... tables) {
    add(functions, usages, function, List.of(), tables);
  }

  private static void add(
      List<FunctionEntry> functions,
      List<FunctionTableUsageEntry> usages,
      FunctionEntry function,
      List<String> dynamicSql,
      UsedTable... tables) {
    functions.add(function);
    usages.add(
        usageOf(
            function, FunctionTableUsageEntry.ANALYZED, HASH, List.of(tables), dynamicSql, false));
  }

  private static FunctionTableUsageEntry usageOf(
      FunctionEntry function,
      String status,
      String hash,
      List<UsedTable> tables,
      List<String> dynamicSql,
      boolean incomplete) {
    return new FunctionTableUsageEntry(
        function.key(),
        function.arguments(),
        hash,
        status,
        "",
        tables,
        dynamicSql,
        incomplete,
        false);
  }

  private static UsedTable used(String schema, String name, String operations) {
    return new UsedTable(schema, name, "", List.of(), operationsOf(operations));
  }

  private static UsedTable candidate(
      String name, String resolution, String operations, String... schemas) {
    return new UsedTable("", name, resolution, List.of(schemas), operationsOf(operations));
  }

  private static List<String> operationsOf(String operations) {
    return operations.chars().mapToObj(operation -> String.valueOf((char) operation)).toList();
  }
}
