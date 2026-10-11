package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.Tables;
import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.SchemaResolution;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 関数・プロシージャの定義本体から、利用している出力対象のテーブルと操作（C・R・U・D）を抽出するクラス<br>
 * 本体を切り出し（{@link FunctionBodyLocator}）、テーブルを指す位置の名前を拾い（{@link TableReferenceScanner}）、
 * 出力対象のテーブルと照らして一致したものだけを残す。スキーマ修飾の無い名前は、定義から決まる場合（PostgreSQLの{@code SET
 * search_path}、Oracleの定義者権限の所有者のスキーマ）だけスキーマを決め、決まらなければ同じ名前の出力対象のテーブルを候補として持つ。
 * インスタンスはスキーマごとに作る（Oracleのパッケージの字句をスキーマの処理の間だけ使い回すため）
 */
public final class FunctionTableUsageAnalyzer {

  private final Tables tables;
  private final Dbms dbms;
  private final FunctionBodyLocator locator;

  /** テーブル名をキー、同じ名前の出力対象のテーブルを値とするマップ（スキーマの処理の間だけ持つ） */
  private final Map<String, List<TableEntity>> tablesByName;

  private FunctionTableUsageAnalyzer(Tables tables, Dbms dbms) {
    this.tables = tables;
    this.dbms = dbms;
    this.locator = FunctionBodyLocator.of(dbms);
    this.tablesByName = tables.byTableName();
  }

  /**
   * スキーマ1つ分の関数・プロシージャを解析するインスタンスを生成する静的ファクトリメソッド
   *
   * @param tables 出力対象のテーブル（全スキーマ分。他のスキーマのテーブルへの参照も照らすため）
   */
  public static FunctionTableUsageAnalyzer of(Tables tables, Dbms dbms) {
    return new FunctionTableUsageAnalyzer(tables, dbms);
  }

  /**
   * 関数・プロシージャ1つを解析するメソッド
   *
   * @param function 定義本体を含む関数・プロシージャ
   * @return 利用しているテーブル。解析しなかった場合はその理由
   */
  public FunctionTableUsage analyze(FunctionEntity function) {
    final FunctionBody body = locator.locate(function);
    if (body.status() == TableUsageStatus.UNSUPPORTED_LANGUAGE) {
      return FunctionTableUsage.unsupportedLanguage(body.language());
    }
    if (body.status() != TableUsageStatus.ANALYZED) {
      return FunctionTableUsage.notAnalyzable(body.status());
    }
    final List<TableReference> references = new ArrayList<>();
    final Set<DynamicSqlKind> dynamicSql = new LinkedHashSet<>();
    for (final List<SqlToken> segment : body.segments()) {
      final ScanResult scanned = TableReferenceScanner.scan(segment, dbms);
      references.addAll(scanned.references());
      dynamicSql.addAll(scanned.dynamicSql());
    }
    return FunctionTableUsage.analyzed(
        resolve(references, function, body),
        List.copyOf(dynamicSql),
        body.incomplete(),
        body.overloadsMerged());
  }

  /**
   * 拾った名前を出力対象のテーブルと照らし、テーブルごとに操作をまとめるメソッド
   *
   * @return スキーマが決まったものをスキーマ名・テーブル名の順に、その後に決まらないものをテーブル名の順に並べたもの
   */
  private List<TableUsage> resolve(
      List<TableReference> references, FunctionEntity function, FunctionBody body) {
    final Map<TableKey, Set<CrudOperation>> determined =
        new TreeMap<>(Comparator.comparing(TableKey::schema).thenComparing(TableKey::table));
    final Map<String, Set<CrudOperation>> undetermined = new TreeMap<>();
    final Optional<SchemaResolution> unqualified = unqualifiedResolution(body);
    for (final TableReference reference : references) {
      final Optional<TableKey> key = determineSchema(reference, function, body);
      if (key.isPresent()) {
        determined
            .computeIfAbsent(key.get(), k -> EnumSet.noneOf(CrudOperation.class))
            .add(reference.operation());
      } else if (reference.schema().isEmpty()
          && unqualified.isPresent()
          && tablesByName.containsKey(reference.table())) {
        undetermined
            .computeIfAbsent(reference.table(), k -> EnumSet.noneOf(CrudOperation.class))
            .add(reference.operation());
      }
    }
    final List<TableUsage> usages = new ArrayList<>();
    determined.forEach(
        (key, operations) ->
            usages.add(
                new TableUsage(
                    List.of(tables.find(key).orElseThrow()),
                    SchemaResolution.DETERMINED,
                    operations)));
    undetermined.forEach(
        (name, operations) ->
            usages.add(
                new TableUsage(tablesByName.get(name), unqualified.orElseThrow(), operations)));
    return usages;
  }

  /**
   * 名前の指すテーブルを、スキーマまで決めて求めるメソッド
   *
   * @return 出力対象のテーブルのキー。スキーマが決まらない・出力対象に無い場合は空
   */
  private Optional<TableKey> determineSchema(
      TableReference reference, FunctionEntity function, FunctionBody body) {
    if (!reference.schema().isEmpty()) {
      return existing(TableKey.of(reference.schema(), reference.table()));
    }
    return switch (dbms) {
      // 定義者権限では、修飾の無い名前はまず所有者のスキーマで解決する
      case ORACLE ->
          body.invokerRights()
              ? Optional.empty()
              : existing(TableKey.of(function.schemaName(), reference.table()));
      case POSTGRESQL ->
          body.searchPath().stream()
              .map(schema -> TableKey.of(schema, reference.table()))
              .filter(tables::contains)
              .findFirst();
    };
  }

  /**
   * スキーマ修飾の無い名前が{@link #determineSchema}で決まらなかった場合の、候補の示し方を求めるメソッド
   *
   * @return 候補を示す場合はその決まり方。空は候補を示さない（関数の{@code SET search_path}のどのスキーマにも無い名前は、出力対象外のスキーマ・{@code
   *     pg_catalog}のもの）
   */
  private Optional<SchemaResolution> unqualifiedResolution(FunctionBody body) {
    return switch (dbms) {
      // 定義者権限で所有者のスキーマに無い名前は、シノニム経由の参照の可能性がある（シノニムは解決しない）
      case ORACLE ->
          Optional.of(
              body.invokerRights() ? SchemaResolution.CURRENT_USER : SchemaResolution.SYNONYM);
      case POSTGRESQL ->
          body.searchPath().isEmpty()
              ? Optional.of(SchemaResolution.SEARCH_PATH)
              : Optional.empty();
    };
  }

  private Optional<TableKey> existing(TableKey key) {
    return tables.contains(key) ? Optional.of(key) : Optional.empty();
  }
}
