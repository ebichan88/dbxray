package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.ToolSpecifications.namedObjectProperties;
import static com.dbxray.mcp.tool.ToolSpecifications.objectSchema;
import static com.dbxray.mcp.tool.ToolSpecifications.readOnlyTool;

import com.dbxray.mcp.catalog.FunctionCoverage;
import com.dbxray.mcp.catalog.FunctionUse;
import com.dbxray.mcp.catalog.ObjectKey;
import com.dbxray.mcp.catalog.Relation;
import com.dbxray.mcp.catalog.RelationCounts;
import com.dbxray.mcp.catalog.SchemaCatalog;
import com.dbxray.mcp.catalog.TableEntry;
import com.dbxray.mcp.catalog.TableImpact;
import com.dbxray.mcp.catalog.TableImpact.IndirectReference;
import com.dbxray.mcp.catalog.TableImpact.SequenceUse;
import com.dbxray.mcp.catalog.TableImpact.TypeUse;
import com.dbxray.mcp.catalog.TableImpact.ViewReference;
import com.dbxray.mcp.catalog.TableTrigger;
import com.dbxray.mcp.catalog.TriggerEntry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** テーブルの影響範囲をまとめて返すツール（{@code get_impact}） */
final class ImpactTools {

  static final String GET_IMPACT = "get_impact";

  /** 各リストを返す件数の上限。共通のマスタ等では、参照元が上限を超えることがある（超えた分は総数だけを示す） */
  static final int MAX_LISTED = 50;

  private final SchemaCatalog catalog;

  ImpactTools(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  /** ツールの一覧 */
  List<SyncToolSpecification> specifications() {
    return List.of(
        readOnlyTool(
            GET_IMPACT,
            "影響範囲調査",
            "テーブル（ビュー・マテリアライズドビューを含む）を変更・削除したときの影響範囲を、1回で返す。"
                + "影響範囲を調べるときは最初に呼び、詳細はget_related_tables（どのカラム同士でつながるか）、"
                + "get_function（定義本体・利用しているテーブル）、get_table（定義）で調べる。"
                + "affectedは影響を受けるもの: referencedBy（外部キー・論理リレーションで参照しているテーブル。"
                + "directは段1の関連、indirectは"
                + RelationCounts.IMPACT_DEPTH
                + "段までの間接参照）、views（参照しているビュー。ビューを参照するビューを含み、viaは経由したビュー）、"
                + "triggers（自テーブルのトリガー）、functions（自テーブルかそのビューを使う関数・プロシージャと操作C・R・U・D）。"
                + "functionsはcliを--previewで実行して出力した参考情報から機械的に求めた参考値で、"
                + "載っていなくても使われていないとは限らない（動的SQL、呼び出している関数の中、出力対象外のテーブルは含まない）。"
                + "coverageで、調べがついた範囲（総数・解析済み・解析不可・参考情報なし・古い・動的SQLあり）が分かる。"
                + "スキーマが決まらない名前でしか一致しなかったものはmatchがpossible（実際はそのスキーマのテーブルではない可能性がある）。"
                + "dependsOnは影響を受けるものではなく、自テーブルのカラムが使うシーケンスとユーザー定義型。"
                + "ビュー・関数の影響はテーブル単位で、カラム単位ではない。"
                + "各リストは"
                + MAX_LISTED
                + "件までで、超えた場合は〇〇Totalで総数を示す",
            objectSchema(
                namedObjectProperties("table", TableTools.TABLE_DESCRIPTION, Map.of()),
                List.of("table")),
            this::getImpact));
  }

  private CallToolResult getImpact(ToolArguments arguments) {
    final TableEntry table =
        ObjectResolver.resolve(
            arguments, "table", "テーブル", TableTools.SEARCH_TABLES, catalog.tables()::lookup);
    return ToolResults.json(GetImpactOutput.of(table, catalog.impactOf(table)));
  }

  private static <T> List<T> head(List<T> items) {
    return items.subList(0, Math.min(items.size(), MAX_LISTED));
  }

  /** 上限を超えて切り捨てた場合の総数。切り捨てていない場合はnull（結果のJSONに出力しない） */
  private static Integer totalIfOver(List<?> items) {
    return items.size() > MAX_LISTED ? items.size() : null;
  }

  private static String name(ObjectKey key) {
    return key == null ? null : key.qualifiedName();
  }

  /**
   * {@code get_impact}の結果
   *
   * @param table 調べたテーブル（{@code スキーマ名.テーブル名}）
   * @param type 区分（table/view/materialized_view）
   * @param affected テーブルを変更・削除したときに影響を受けるもの
   * @param dependsOn テーブルが依存するもの（影響を受けるものではない）。無い場合はnull
   * @param notes 結果の読み方の注意
   */
  record GetImpactOutput(
      String table,
      String database,
      String type,
      Affected affected,
      DependsOn dependsOn,
      List<String> notes) {

    static GetImpactOutput of(TableEntry table, TableImpact impact) {
      final DependsOn dependsOn =
          new DependsOn(
              impact.sequences().stream().map(SequenceRef::of).toList(),
              impact.types().stream().map(TypeRef::of).toList());
      return new GetImpactOutput(
          table.key().qualifiedName(),
          table.key().database(),
          table.type(),
          Affected.of(impact),
          impact.sequences().isEmpty() && impact.types().isEmpty() ? null : dependsOn,
          notes(impact.functions().coverage()));
    }

    /** 結果の読み方の注意。参考情報の状態に応じて、利用者がすべきことも示す */
    private static List<String> notes(FunctionCoverage coverage) {
      final List<String> notes = new ArrayList<>();
      notes.add(
          "functionsは、cliを--previewで実行して出力した参考情報から機械的に求めた参考値です。"
              + "載っていなくても使われていないとは限りません（動的SQL、呼び出している関数の中、出力対象外のテーブルは含みません）。");
      if (coverage.noInsight() > 0 || coverage.stale() > 0) {
        notes.add(
            "関数の参考情報が無い（noInsight）または古い（stale）関数があります。"
                + "cliを--previewを付けて実行して出力・出し直すと、調べられる範囲が広がります。");
      }
      if (coverage.triggersWithoutFunction() > 0) {
        notes.add(
            "実行する関数名を持たず、本体を直接持つトリガーが同じDBに"
                + coverage.triggersWithoutFunction()
                + "件あります（Oracle）。トリガーの本体の中の参照は含まないため、"
                + "トリガーが触るテーブルは、このツールでは分かりません。");
      }
      notes.add("ビュー・関数の影響は、テーブル単位です（ビューや関数がどのカラムを使うかは、スナップショットに含まれません）。");
      notes.add(
          "dependsOnは、影響を受けるものではなく、このテーブルが使うものです。"
              + "シーケンスはカラムのデフォルト値のnextvalから拾うため、IDENTITY列やトリガーの本体で採番するシーケンスは含みません。");
      return notes;
    }
  }

  /**
   * 影響を受けるもの
   *
   * @param viewsTotal {@code views}を上限で切り捨てた場合の総数。切り捨てていない場合はnull
   * @param triggersTotal {@code triggers}を上限で切り捨てた場合の総数。切り捨てていない場合はnull
   */
  record Affected(
      ReferencedBy referencedBy,
      List<ViewRef> views,
      Integer viewsTotal,
      List<TriggerRef> triggers,
      Integer triggersTotal,
      Functions functions) {

    static Affected of(TableImpact impact) {
      final List<ViewRef> views = impact.views().stream().map(ViewRef::of).toList();
      final List<TriggerRef> triggers = impact.triggers().stream().map(TriggerRef::of).toList();
      return new Affected(
          ReferencedBy.of(impact),
          head(views),
          totalIfOver(views),
          head(triggers),
          totalIfOver(triggers),
          Functions.of(impact));
    }
  }

  /**
   * 外部キー・論理リレーションで参照しているテーブル
   *
   * @param direct 段1の関連。自己参照を含む
   * @param directTotal {@code direct}を上限で切り捨てた場合の総数。切り捨てていない場合はnull
   * @param indirect 段1に現れない、間接的な参照元
   * @param indirectTotal {@code indirect}の総数（上限で切り捨てても、切り捨て前の数）
   */
  record ReferencedBy(
      List<DirectRef> direct, Integer directTotal, List<IndirectRef> indirect, int indirectTotal) {

    static ReferencedBy of(TableImpact impact) {
      final List<DirectRef> direct = impact.directReferences().stream().map(DirectRef::of).toList();
      final List<IndirectRef> indirect =
          impact.indirectReferences().stream().map(IndirectRef::of).toList();
      return new ReferencedBy(head(direct), totalIfOver(direct), head(indirect), indirect.size());
    }
  }

  /**
   * 段1の関連
   *
   * @param table 自テーブルを参照しているテーブル（{@code スキーマ名.テーブル名}）
   * @param kind {@code foreignKey}または{@code logicalRelation}
   * @param columns 参照元のカラム
   * @param referencedColumns 参照先（自テーブル）のカラム
   */
  record DirectRef(
      String table,
      String kind,
      List<String> columns,
      List<String> referencedColumns,
      String cardinality) {

    static DirectRef of(Relation relation) {
      return new DirectRef(
          relation.from().qualifiedName(),
          RelationTools.kindName(relation.kind()),
          relation.fromColumns(),
          relation.toColumns(),
          relation.cardinality());
    }
  }

  /**
   * 間接的な参照元
   *
   * @param depth 自テーブルから何段目で見つけたか
   * @param via その段で見つけた関連の参照先（{@code スキーマ名.テーブル名}）
   */
  record IndirectRef(String table, int depth, String via) {

    static IndirectRef of(IndirectReference reference) {
      return new IndirectRef(name(reference.table()), reference.depth(), name(reference.via()));
    }
  }

  /**
   * 自テーブルを参照しているビュー
   *
   * @param view {@code スキーマ名.ビュー名}
   * @param type {@code view}または{@code materialized_view}
   * @param via ビューが参照しているビュー。自テーブルを直接参照している場合はnull
   */
  record ViewRef(String view, String type, String via) {

    static ViewRef of(ViewReference reference) {
      return new ViewRef(
          reference.view().key().qualifiedName(), reference.view().type(), name(reference.via()));
    }
  }

  /** 自テーブルのトリガー */
  record TriggerRef(String name, String timing, List<String> events, String function) {

    static TriggerRef of(TriggerEntry trigger) {
      return new TriggerRef(trigger.name(), trigger.timing(), trigger.events(), trigger.function());
    }
  }

  /**
   * 自テーブルを使う関数・プロシージャ
   *
   * @param preview 常にtrue。cliの{@code --preview}の参考情報から求めた参考値で、互換性の約束の対象外であることを示す
   * @param usagesTotal {@code usages}を上限で切り捨てた場合の総数。切り捨てていない場合はnull
   */
  record Functions(boolean preview, Coverage coverage, List<Usage> usages, Integer usagesTotal) {

    static Functions of(TableImpact impact) {
      final List<Usage> usages = impact.functions().usages().stream().map(Usage::of).toList();
      return new Functions(
          true, Coverage.of(impact.functions().coverage()), head(usages), totalIfOver(usages));
    }
  }

  /**
   * 関数の参考情報で調べがついた範囲（同じDBの関数・プロシージャ。オーバーロードはそれぞれ数える）
   *
   * @param analyzed 解析済み
   * @param unsupported 参考情報はあるが解析していない（対象外の言語・難読化等）
   * @param noInsight 参考情報が無い（cliを{@code --preview}付きで実行する）
   * @param stale 参考情報が古い（cliを{@code --preview}付きで出力し直す）
   * @param withDynamicSql 解析済みのうち、動的SQLを含む
   * @param incomplete 解析済みのうち、定義本体を最後まで読めなかった
   * @param triggersWithoutFunction 実行する関数名を持たず、本体を直接持つトリガーの数（Oracle）
   */
  record Coverage(
      int total,
      int analyzed,
      int unsupported,
      int noInsight,
      int stale,
      int withDynamicSql,
      int incomplete,
      int triggersWithoutFunction) {

    static Coverage of(FunctionCoverage coverage) {
      return new Coverage(
          coverage.total(),
          coverage.analyzed(),
          coverage.unsupported(),
          coverage.noInsight(),
          coverage.stale(),
          coverage.withDynamicSql(),
          coverage.incomplete(),
          coverage.triggersWithoutFunction());
    }
  }

  /**
   * 自テーブルを使う関数・プロシージャ1つ分
   *
   * @param function {@code スキーマ名.関数名}
   * @param overloads 一致したオーバーロードの引数。引数が無い場合は{@code ()}
   * @param operations 操作（{@code C}・{@code R}・{@code U}・{@code D}の順）
   * @param match スキーマが決まらない名前でしか一致しなかった場合は{@code possible}。それ以外はnull
   * @param via 関数が使っているビュー（自テーブルではなくビューを使っている場合）。自テーブルを使っている場合はnull
   * @param calledByTriggers この関数を実行するトリガー（{@code スキーマ名.テーブル名.トリガー名}）
   */
  record Usage(
      String function,
      String kind,
      List<String> overloads,
      String operations,
      String match,
      String schemaResolution,
      List<String> dynamicSql,
      String via,
      List<String> calledByTriggers) {

    static Usage of(FunctionUse use) {
      return new Usage(
          name(use.function()),
          use.kind(),
          use.overloads().stream()
              .map(arguments -> arguments.isEmpty() ? "()" : arguments)
              .toList(),
          String.join("", use.operations()),
          use.possible() ? "possible" : null,
          use.schemaResolution(),
          use.dynamicSql(),
          name(use.via()),
          use.calledByTriggers().stream().map(Usage::triggerName).toList());
    }

    private static String triggerName(TableTrigger found) {
      return found.table().key().qualifiedName() + "." + found.trigger().name();
    }
  }

  /** 自テーブルが依存するもの（影響を受けるものではない） */
  record DependsOn(List<SequenceRef> sequences, List<TypeRef> types) {}

  /**
   * 自テーブルのカラムが使うシーケンス
   *
   * @param sequence {@code スキーマ名.シーケンス名}
   * @param columns シーケンスを使うカラム
   */
  record SequenceRef(String sequence, List<String> columns) {

    static SequenceRef of(SequenceUse use) {
      return new SequenceRef(name(use.sequence()), use.columns());
    }
  }

  /**
   * 自テーブルのカラムの型に使われているユーザー定義型
   *
   * @param type {@code スキーマ名.型名}
   * @param columns 型を使うカラム
   */
  record TypeRef(String type, String category, List<String> columns) {

    static TypeRef of(TypeUse use) {
      return new TypeRef(name(use.type().key()), use.type().category(), use.columns());
    }
  }
}
