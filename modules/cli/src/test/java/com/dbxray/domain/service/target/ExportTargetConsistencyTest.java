package com.dbxray.domain.service.target;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.relation.ForeignKeyEntity;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.sidecar.Annotations;
import com.dbxray.domain.model.sidecar.TableAnnotation;
import com.dbxray.domain.model.table.TableDetail;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.target.ConsistencyNotice;
import com.dbxray.domain.model.target.ConsistencyNotice.Kind;
import com.dbxray.domain.model.target.ConsistencyNotice.Severity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.model.viewpoint.Viewpoints;
import com.dbxray.domain.service.target.ExportTargetConsistency.ResolvedForeignKeys;
import com.dbxray.testsupport.EntityFixtures;
import com.dbxray.testsupport.ForeignKeyFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ExportTargetConsistency の外部キー・論理リレーション・付帯情報の突き合わせに関するテスト */
public class ExportTargetConsistencyTest {

  private final ExportTargetConsistency service = new ExportTargetConsistency();

  private TableEntity table(String schema, String physical) {
    return new TableEntity("testdb", schema, "", physical, TableType.TABLE, "", "");
  }

  private List<String> names(List<ForeignKeyEntity> foreignKeys) {
    return foreignKeys.stream().map(ForeignKeyEntity::foreignKeyName).toList();
  }

  private List<Kind> kinds(List<ConsistencyNotice> consistencyNotices) {
    return consistencyNotices.stream().map(ConsistencyNotice::kind).toList();
  }

  @Test
  @DisplayName("resolveForeignKeys: 参照元・参照先の双方が出力対象の物理外部キーのみを残す")
  void testResolveForeignKeysKeepsOnlyResolvablePhysicalForeignKeys() {
    var tables = List.of(table("public", "orders"), table("public", "customers"));
    var resolvable =
        ForeignKeyFixtures.physical(
            "public", "orders", "fk_orders_customer", "public", "customers");
    var missingParent =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");

    ForeignKeys result =
        service
            .resolveForeignKeys(
                List.of(resolvable, missingParent), List.of(), Tables.of(tables), false)
            .foreignKeys();

    assertEquals(
        List.of("fk_orders_customer"), names(result.belongingTo(table("public", "orders"))));
  }

  @Test
  @DisplayName("resolveForeignKeys: 論理リレーションも同じ集合へ合流させ、片側が出力対象外のものは除外する")
  void testResolveForeignKeysMergesResolvableLogicalRelations() {
    var tables = List.of(table("public", "orders"), table("public", "staff"));
    var physical =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var unresolvedLogical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_coupon", "public", "coupons");

    ForeignKeys result =
        service
            .resolveForeignKeys(
                List.of(physical), List.of(logical, unresolvedLogical), Tables.of(tables), true)
            .foreignKeys();

    // 物理外部キー、論理リレーションの順に並ぶ
    assertEquals(
        List.of("fk_orders_staff", "rel_orders_staff"),
        names(result.belongingTo(table("public", "orders"))));
    assertEquals(
        List.of("fk_orders_staff"), names(result.physicalBelongingTo(table("public", "orders"))));
    assertEquals(
        List.of("rel_orders_staff"), names(result.logicalBelongingTo(table("public", "orders"))));
  }

  @Test
  @DisplayName("resolveForeignKeys: 対象が無い場合は空の集合を返し、通知も無い")
  void testResolveForeignKeysReturnsEmptyWhenNothingToResolve() {
    ResolvedForeignKeys result =
        service.resolveForeignKeys(
            List.of(), List.of(), Tables.of(List.of(table("public", "orders"))), false);

    assertEquals(List.of(), result.foreignKeys().belongingTo(table("public", "orders")));
    assertEquals(List.of(), result.foreignKeys().crossSchema());
    assertEquals(List.of(), result.consistencyNotices());
  }

  @Test
  @DisplayName("resolveForeignKeys: 絞り込みが無い場合、参照先が存在しない物理外部キーを通知する")
  void testResolveForeignKeysReportsUnresolvedPhysicalForeignKeyWhenNotFiltered() {
    var tables = Tables.of(List.of(table("public", "orders")));
    var missingParent =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");

    List<ConsistencyNotice> consistencyNotices =
        service
            .resolveForeignKeys(List.of(missingParent), List.of(), tables, false)
            .consistencyNotices();

    assertEquals(List.of(Kind.UNRESOLVED_FOREIGN_KEY), kinds(consistencyNotices));
    assertEquals(Severity.WARN, consistencyNotices.get(0).severity());
    assertEquals(
        "Skipping a foreign key because the referenced table was not found (renamed or dropped?)."
            + " [foreignKey=fk_orders_staff, table=public.orders, referenceTable=public.staff]",
        consistencyNotices.get(0).message());
  }

  @Test
  @DisplayName("resolveForeignKeys: 絞り込み時は、物理外部キーの除外は意図したものとして通知しない")
  void testResolveForeignKeysDoesNotReportPhysicalForeignKeyWhenFiltered() {
    var tables = Tables.of(List.of(table("public", "orders")));
    var missingParent =
        ForeignKeyFixtures.physical("public", "orders", "fk_orders_staff", "public", "staff");

    assertEquals(
        List.of(),
        service
            .resolveForeignKeys(List.of(missingParent), List.of(), tables, true)
            .consistencyNotices());
  }

  @Test
  @DisplayName("resolveForeignKeys: 論理リレーションの除外は絞り込みの有無によらず通知し、合流させた件数も報告する")
  void testResolveForeignKeysReportsLogicalRelations() {
    var tables = Tables.of(List.of(table("public", "orders"), table("public", "staff")));
    var logical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_staff", "public", "staff");
    var unresolvedLogical =
        ForeignKeyFixtures.logical("public", "orders", "rel_orders_coupon", "public", "coupons");

    List<ConsistencyNotice> consistencyNotices =
        service
            .resolveForeignKeys(List.of(), List.of(logical, unresolvedLogical), tables, true)
            .consistencyNotices();

    assertEquals(
        List.of(Kind.UNRESOLVED_LOGICAL_RELATION, Kind.LOGICAL_RELATIONS_MERGED),
        kinds(consistencyNotices));
    assertEquals(
        "Skipping logical relation because the table was not found in the output target"
            + " (filtered, renamed or dropped?). [relation=rel_orders_coupon, table=public.orders,"
            + " parentTable=public.coupons (not found)]",
        consistencyNotices.get(0).message());
    assertEquals(Severity.INFO, consistencyNotices.get(1).severity());
    assertEquals(
        "Merged logical relations declared in the sidecar. [relationCount=1]",
        consistencyNotices.get(1).message());
  }

  @Test
  @DisplayName("resolveForeignKeys: 論理リレーションの除外の通知は、参照元・参照先のうち見つからなかった側に(not found)を付ける")
  void testResolveForeignKeysReportsWhichSideOfLogicalRelationIsMissing() {
    var tables = Tables.of(List.of(table("public", "orders")));
    var missingChild =
        ForeignKeyFixtures.logical("public", "audit_log", "rel_audit_orders", "public", "orders");
    var missingBoth =
        ForeignKeyFixtures.logical("public", "old_items", "rel_items_coupon", "public", "coupons");

    List<String> messages =
        service
            .resolveForeignKeys(List.of(), List.of(missingChild, missingBoth), tables, false)
            .consistencyNotices()
            .stream()
            .map(ConsistencyNotice::message)
            .toList();

    assertEquals(
        List.of(
            "Skipping logical relation because the table was not found in the output target"
                + " (filtered, renamed or dropped?). [relation=rel_audit_orders,"
                + " table=public.audit_log (not found), parentTable=public.orders]",
            "Skipping logical relation because the table was not found in the output target"
                + " (filtered, renamed or dropped?). [relation=rel_items_coupon,"
                + " table=public.old_items (not found), parentTable=public.coupons (not found)]",
            "Merged logical relations declared in the sidecar. [relationCount=0]"),
        messages);
  }

  @Test
  @DisplayName("resolveForeignKeys: 参照元が出力対象に無い物理外部キーも除外し、絞り込みが無ければ通知する")
  void testResolveForeignKeysExcludesPhysicalForeignKeyWithMissingChild() {
    var tables = Tables.of(List.of(table("public", "orders")));
    var missingChild =
        ForeignKeyFixtures.physical("public", "items", "fk_items_orders", "public", "orders");

    ResolvedForeignKeys result =
        service.resolveForeignKeys(List.of(missingChild), List.of(), tables, false);

    assertEquals(List.of(), result.foreignKeys().referencingTo(table("public", "orders")));
    assertEquals(List.of(Kind.UNRESOLVED_FOREIGN_KEY), kinds(result.consistencyNotices()));
  }

  @Test
  @DisplayName("findOrphanTableAnnotations: 実在しないテーブルに対する付帯情報を通知する")
  void testFindOrphanTableAnnotations() {
    var annotations =
        Annotations.of(
            Map.of(
                TableKey.of("public", "orders"), new TableAnnotation("説明", "", Map.of()),
                TableKey.of("public", "removed"), new TableAnnotation("説明", "", Map.of())));

    List<ConsistencyNotice> consistencyNotices =
        service.findOrphanTableAnnotations(
            annotations, Tables.of(List.of(table("public", "orders"))), false);

    assertEquals(List.of(Kind.ORPHAN_TABLE_ANNOTATION), kinds(consistencyNotices));
    assertEquals(
        "Annotation exists for a table that was not found (renamed or dropped?)."
            + " [table=public.removed]",
        consistencyNotices.get(0).message());
  }

  @Test
  @DisplayName("findOrphanTableAnnotations: 絞り込み時は検出を行わず、行わなかったことを報告する")
  void testFindOrphanTableAnnotationsSkippedWhenFiltered() {
    var annotations =
        Annotations.of(
            Map.of(TableKey.of("public", "removed"), new TableAnnotation("説明", "", Map.of())));

    List<ConsistencyNotice> consistencyNotices =
        service.findOrphanTableAnnotations(annotations, Tables.of(List.of()), true);

    assertEquals(List.of(Kind.ORPHAN_TABLE_ANNOTATION_CHECK_SKIPPED), kinds(consistencyNotices));
    assertEquals(Severity.INFO, consistencyNotices.get(0).severity());
  }

  @Test
  @DisplayName("findOrphanTableAnnotations: 付帯情報が無い場合は通知も無い")
  void testFindOrphanTableAnnotationsWithoutAnnotations() {
    assertEquals(
        List.of(),
        service.findOrphanTableAnnotations(Annotations.empty(), Tables.of(List.of()), true));
  }

  @Test
  @DisplayName("findOrphanColumnAnnotations: 実在しないカラムに対するカラム備考を通知する")
  void testFindOrphanColumnAnnotations() {
    var orders = table("public", "orders");
    var annotations =
        Annotations.of(
            Map.of(
                TableKey.of(orders),
                new TableAnnotation("", "", Map.of("id", "ID", "removed_column", "削除済み"))));
    var detail =
        new TableDetail(
            orders,
            List.of(EntityFixtures.column("public", "orders", "id", "int", true)),
            List.of(),
            List.of());

    List<ConsistencyNotice> consistencyNotices =
        service.findOrphanColumnAnnotations(detail, annotations);

    assertEquals(List.of(Kind.ORPHAN_COLUMN_ANNOTATION), kinds(consistencyNotices));
    assertEquals(
        "Column annotation exists for a column that was not found (renamed or dropped?)."
            + " [table=public.orders, column=removed_column]",
        consistencyNotices.get(0).message());
  }

  @Test
  @DisplayName("findOrphanColumnAnnotations: 実在しないカラムが複数ある場合は、サイドカーYAMLの記述順に通知する")
  void testFindOrphanColumnAnnotationsInDeclarationOrder() {
    var orders = table("public", "orders");
    var columnRemarks = new LinkedHashMap<String, String>();
    columnRemarks.put("shipped_on", "削除済み");
    columnRemarks.put("id", "ID");
    columnRemarks.put("coupon_code", "削除済み");
    columnRemarks.put("amount_old", "削除済み");
    var annotations =
        Annotations.of(Map.of(TableKey.of(orders), new TableAnnotation("", "", columnRemarks)));
    var detail =
        new TableDetail(
            orders,
            List.of(EntityFixtures.column("public", "orders", "id", "int", true)),
            List.of(),
            List.of());

    List<String> messages =
        service.findOrphanColumnAnnotations(detail, annotations).stream()
            .map(ConsistencyNotice::message)
            .toList();

    assertEquals(3, messages.size());
    assertTrue(messages.get(0).endsWith("column=shipped_on]"));
    assertTrue(messages.get(1).endsWith("column=coupon_code]"));
    assertTrue(messages.get(2).endsWith("column=amount_old]"));
  }

  @Test
  @DisplayName("findUnmatchedViewpointPatterns: どのテーブルにも一致しない観点のパターンを、観点・パターンごとに警告として返す")
  void testFindUnmatchedViewpointPatterns() {
    var viewpoints =
        Viewpoints.of(
            List.of(
                Viewpoint.of("order", "", "", List.of("public.order*", "public.custmer")),
                Viewpoint.of("master", "", "", List.of("public.customers"))));
    var tables = Tables.of(List.of(table("public", "orders"), table("public", "customers")));

    List<ConsistencyNotice> consistencyNotices =
        service.findUnmatchedViewpointPatterns(viewpoints, tables, false);

    assertEquals(List.of(Kind.UNMATCHED_VIEWPOINT_PATTERN), kinds(consistencyNotices));
    assertEquals(Severity.WARN, consistencyNotices.getFirst().severity());
    assertTrue(consistencyNotices.getFirst().message().contains("viewpoint=order"));
    assertTrue(consistencyNotices.getFirst().message().contains("pattern=public.custmer"));
  }

  @Test
  @DisplayName("findUnmatchedViewpointPatterns: 出力対象が絞り込まれている場合は、対象外のテーブルを指すパターンを誤って通知しないよう検出しない")
  void testFindUnmatchedViewpointPatternsSkippedWhenFiltered() {
    var viewpoints = Viewpoints.of(List.of(Viewpoint.of("order", "", "", List.of("sales.orders"))));

    assertEquals(
        List.of(),
        service.findUnmatchedViewpointPatterns(
            viewpoints, Tables.of(List.of(table("public", "orders"))), true));
  }
}
