package com.dbxray.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * スナップショットから読み込んだ全オブジェクトを保持し、関心ごとの窓口を返すクラス<br>
 * 検索・一覧・名前の解決・関連のたどりは各窓口（{@link TableCatalog}・{@link ObjectCatalog}・{@link ViewpointCatalog}・{@link
 * RelationGraph}）が担い、ここには複数の窓口にまたがる処理だけを置く
 */
public final class SchemaCatalog {

  private final List<DatabaseEntry> databases;
  private final RelationGraph relations;
  private final TableCatalog tables;
  private final ObjectCatalog<FunctionOverloads> functions;
  private final ObjectCatalog<SequenceEntry> sequences;
  private final ObjectCatalog<TypeEntry> types;
  private final ViewpointCatalog viewpoints;
  private final FunctionTableUsages functionTableUsages;

  private SchemaCatalog(
      List<DatabaseEntry> databases,
      RelationGraph relations,
      TableCatalog tables,
      ObjectCatalog<FunctionOverloads> functions,
      ObjectCatalog<SequenceEntry> sequences,
      ObjectCatalog<TypeEntry> types,
      ViewpointCatalog viewpoints,
      FunctionTableUsages functionTableUsages) {
    this.databases = List.copyOf(databases);
    this.relations = relations;
    this.tables = tables;
    this.functions = functions;
    this.sequences = sequences;
    this.types = types;
    this.viewpoints = viewpoints;
    this.functionTableUsages = functionTableUsages;
  }

  /**
   * スナップショットの内容から組み立てるメソッド（観点の参考情報を含む）
   *
   * @param databases 全DB。オブジェクトを持たないDBも含める
   * @param tables 全テーブル。キー（DB名・スキーマ名・テーブル名）が重複しないこと
   * @param functions 全関数・プロシージャ。オーバーロードはキーが重複する
   * @param sequences 全シーケンス
   * @param types 全ユーザー定義型
   * @param viewpoints 観点の参考情報（宣言順）
   */
  public static SchemaCatalog of(
      List<DatabaseEntry> databases,
      List<TableEntry> tables,
      List<FunctionEntry> functions,
      List<SequenceEntry> sequences,
      List<TypeEntry> types,
      List<ViewpointEntry> viewpoints) {
    final List<FunctionOverloads> overloads =
        functions.stream()
            .collect(
                Collectors.groupingBy(FunctionEntry::key, LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> new FunctionOverloads(entry.getKey(), entry.getValue()))
            .toList();
    final RelationGraph relations = new RelationGraph(tables);
    return new SchemaCatalog(
        databases,
        relations,
        new TableCatalog(tables, relations),
        new ObjectCatalog<>(overloads),
        new ObjectCatalog<>(sequences),
        new ObjectCatalog<>(types),
        new ViewpointCatalog(viewpoints),
        new FunctionTableUsages(List.of()));
  }

  /**
   * スナップショットの内容から組み立てるメソッド（観点の参考情報を持たない）
   *
   * @see #of(List, List, List, List, List, List)
   */
  public static SchemaCatalog of(
      List<DatabaseEntry> databases,
      List<TableEntry> tables,
      List<FunctionEntry> functions,
      List<SequenceEntry> sequences,
      List<TypeEntry> types) {
    return of(databases, tables, functions, sequences, types, List.of());
  }

  /**
   * 観点の参考情報を追加した新しいインスタンスを返すメソッド<br>
   * スナップショットとは別の読み込み元（参考情報）の内容を、組み立て後に合成するために用いる
   */
  public SchemaCatalog withViewpoints(List<ViewpointEntry> viewpoints) {
    return new SchemaCatalog(
        databases,
        relations,
        tables,
        functions,
        sequences,
        types,
        new ViewpointCatalog(viewpoints),
        functionTableUsages);
  }

  /**
   * 関数・プロシージャが利用しているテーブルの参考情報を追加した新しいインスタンスを返すメソッド<br>
   * 観点と同じく、スナップショットとは別の読み込み元（参考情報）の内容を、組み立て後に合成するために用いる
   */
  public SchemaCatalog withFunctionTableUsages(List<FunctionTableUsageEntry> entries) {
    return new SchemaCatalog(
        databases,
        relations,
        tables,
        functions,
        sequences,
        types,
        viewpoints,
        new FunctionTableUsages(entries));
  }

  /** テーブル（ビューを含む）の窓口を返すメソッド */
  public TableCatalog tables() {
    return tables;
  }

  /** 関数・プロシージャの窓口を返すメソッド（オーバーロードはまとめて1つとみなす） */
  public ObjectCatalog<FunctionOverloads> functions() {
    return functions;
  }

  /** シーケンスの窓口を返すメソッド */
  public ObjectCatalog<SequenceEntry> sequences() {
    return sequences;
  }

  /** ユーザー定義型の窓口を返すメソッド */
  public ObjectCatalog<TypeEntry> types() {
    return types;
  }

  /** 観点の参考情報の窓口を返すメソッド */
  public ViewpointCatalog viewpoints() {
    return viewpoints;
  }

  /** 関数・プロシージャが利用しているテーブルの参考情報の窓口を返すメソッド */
  public FunctionTableUsages functionTableUsages() {
    return functionTableUsages;
  }

  /** テーブル間の関連（外部キー・論理リレーション）のグラフを返すメソッド */
  public RelationGraph relations() {
    return relations;
  }

  /**
   * スキーマごとのオブジェクトの数を返すメソッド
   *
   * @return DB名・スキーマ名の順。オブジェクトを1つも持たないスキーマは含まない
   */
  public List<SchemaSummary> schemas() {
    return SchemaSummary.summarize(
        databases, tables.all(), functions.all(), sequences.all(), types.all());
  }

  /**
   * 関連のつながりから、テーブルのまとまりを推測するメソッド<br>
   * 範囲外のテーブルとの関連は無いものとみなす。求め方は{@link ClusterDetector}を参照
   *
   * @param maxClusterSize まとまりのテーブル数の上限（2以上）。上限を超えるまとまりは、被参照の多いテーブルを除いて分け直す
   * @param excludeViewpointTables trueの場合、いずれかの観点に所属するテーブルを範囲から除く（観点が未宣言の範囲だけを求める）
   */
  public TableClusters tableClusters(
      SearchScope scope, int maxClusterSize, boolean excludeViewpointTables) {
    final List<TableEntry> inScope =
        tables.all().stream()
            .filter(table -> scope.matches(table.key()))
            .filter(table -> !excludeViewpointTables || viewpoints.containing(table).isEmpty())
            .toList();
    return new ClusterDetector(inScope, relations).detect(maxClusterSize);
  }
}
