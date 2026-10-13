package com.dbxray.mcp.tool;

import static com.dbxray.mcp.tool.ToolSpecifications.enumProperty;
import static com.dbxray.mcp.tool.ToolSpecifications.integerProperty;
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
import com.dbxray.mcp.catalog.TableImpact.UnverifiedFunction;
import com.dbxray.mcp.catalog.TableImpact.ViewReference;
import com.dbxray.mcp.catalog.TableTrigger;
import com.dbxray.mcp.catalog.TriggerEntry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** テーブルの影響範囲をまとめて返すツール（{@code get_impact}） */
final class ImpactTools {

  static final String GET_IMPACT = "get_impact";

  /** 各リストを返す件数の上限。共通のマスタ等では、参照元が上限を超えることがある（超えた分は{@code section}で続きを取る） */
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
                + "影響範囲を調べるときは最初にsectionなしで呼び、詳細はget_related_tables（どのカラム同士でつながるか）、"
                + "get_function（定義本体・利用しているテーブル）、get_table（定義）で調べる。"
                + "affectedは影響を受けるもの: referencedBy（外部キー・論理リレーションで参照しているテーブル。"
                + "directは段1の関連、indirectは"
                + RelationCounts.IMPACT_DEPTH
                + "段までの間接参照）、views（参照しているビュー。ビューを参照するビューを含み、viaは経由したビュー）、"
                + "triggers（自テーブルのトリガー）、functions（自テーブルかそのビューを使う関数・プロシージャ）。"
                + "functions.usagesは関数と経路（自テーブルか、使っているビューか）ごとの行で、操作C・R・U・Dを付ける"
                + "（自テーブルとビューの両方を使う関数は2行になる）。"
                + "functionsはcliを--previewで実行して出力した参考情報から機械的に求めた参考値で、"
                + "載っていなくても使われていないとは限らない（動的SQL、呼び出している関数の中、出力対象外のテーブルは含まない）。"
                + "coverageは調べがついた範囲の件数（参考情報が無いnoInsight・古いstaleの関数は、ここでだけ数える）。"
                + "unverifiedは自テーブルを使うかどうか参考情報からは確かめきれず、get_functionで定義本体を読む必要がある関数"
                + "（reason: dynamicSql・incomplete・unsupported）。"
                + "スキーマが決まらない名前でしか一致しなかった行はmatchがpossible。"
                + "確定で一致したオーバーロードと候補でだけ一致したオーバーロードが混ざる行はpossibleにならず、"
                + "候補のオーバーロードの操作もoperationsに含まれる。"
                + "dependsOnは影響を受けるものではなく、自テーブルのカラムが使うシーケンスとユーザー定義型。"
                + "ビュー・関数の影響はテーブル単位で、カラム単位ではない。"
                + "sectionなしでは各リストを"
                + MAX_LISTED
                + "件まで返し、超えたリストは総数と続きの取り方をmoreで示す（間接参照の総数indirectTotalは常に返す）。"
                + "続きを取るには、同じtableにsection（概要の同じ名前のリスト。"
                + String.join("・", Section.allValues())
                + "のいずれか）とoffsetを指定する（limitは1ページの件数）。offset・limitはsectionを指定した場合だけ使える",
            objectSchema(
                namedObjectProperties(
                    "table",
                    TableTools.TABLE_DESCRIPTION,
                    Map.of(
                        "section",
                        enumProperty(
                            "続きを取りたいリストを1つ指定する。省略すると全体の概要を返す。"
                                + "direct・indirect: affected.referencedBy、views・triggers: affected、"
                                + "usages・unverified: affected.functionsの同じ名前のリスト",
                            Section.allValues()),
                        "offset",
                        integerProperty(
                            "sectionを指定した場合の、先頭から読み飛ばす件数（既定0）。続きは前回の結果のnextOffsetを指定する",
                            0,
                            Integer.MAX_VALUE),
                        "limit",
                        integerProperty(
                            "sectionを指定した場合の、1ページの件数（既定" + Page.DEFAULT_LIMIT + "）",
                            1,
                            Page.MAX_LIMIT))),
                List.of("table")),
            this::getImpact));
  }

  private CallToolResult getImpact(ToolArguments arguments) {
    final Optional<Section> section =
        Section.of(arguments.optionalChoice("section", Section.allValues()));
    if (section.isEmpty() && (arguments.isPresent("offset") || arguments.isPresent("limit"))) {
      throw new InvalidToolArgumentException(
          "引数offset・limitは、sectionを指定した場合だけ使えます。続きを取るには、sectionに"
              + String.join("・", Section.allValues())
              + "のいずれかを指定してください。");
    }
    final TableEntry table =
        ObjectResolver.resolve(
            arguments, "table", "テーブル", TableTools.SEARCH_TABLES, catalog.tables()::lookup);
    final TableImpact impact = catalog.impactOf(table);
    return section.isEmpty()
        ? ToolResults.json(GetImpactOutput.of(table, impact))
        : ToolResults.json(
            SectionOutput.of(
                table, section.get(), impact, Page.read(arguments, Page.DEFAULT_LIMIT)));
  }

  /** 続きを取れるリスト。値は概要のJSONで、そのリストを持つキーの名前 */
  enum Section {
    DIRECT("direct"),
    INDIRECT("indirect"),
    VIEWS("views"),
    TRIGGERS("triggers"),
    USAGES("usages"),
    UNVERIFIED("unverified");

    private final String value;

    Section(String value) {
      this.value = value;
    }

    String value() {
      return value;
    }

    /** 全セクションの値を返すメソッド（ツールの入力スキーマの{@code enum}に使う） */
    static List<String> allValues() {
      return Arrays.stream(values()).map(Section::value).toList();
    }

    /**
     * 値からセクションを求めるメソッド
     *
     * @return 空文字（未指定）は空。未知の値は、入力スキーマの検証と{@code optionalChoice}が先に拒否する
     */
    static Optional<Section> of(String value) {
      return Arrays.stream(values()).filter(section -> section.value.equals(value)).findFirst();
    }

    /** セクションのリストの全要素（概要の各リストと同じ形） */
    List<?> items(TableImpact impact) {
      return switch (this) {
        case DIRECT -> impact.directReferences().stream().map(DirectRef::of).toList();
        case INDIRECT -> impact.indirectReferences().stream().map(IndirectRef::of).toList();
        case VIEWS -> impact.views().stream().map(ViewRef::of).toList();
        case TRIGGERS -> impact.triggers().stream().map(TriggerRef::of).toList();
        case USAGES -> impact.functions().usages().stream().map(Usage::of).toList();
        case UNVERIFIED -> impact.functions().unverified().stream().map(UnverifiedRef::of).toList();
      };
    }
  }

  private static <T> List<T> head(List<T> items) {
    return items.subList(0, Math.min(items.size(), MAX_LISTED));
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
   * @param more 上限（{@value ImpactTools#MAX_LISTED}件）で切り捨てたリストと、続きの取り方
   * @param notes 結果の読み方の注意
   */
  record GetImpactOutput(
      String table,
      String database,
      String type,
      Affected affected,
      DependsOn dependsOn,
      List<More> more,
      List<String> notes) {

    static GetImpactOutput of(TableEntry table, TableImpact impact) {
      final DependsOn dependsOn =
          new DependsOn(
              impact.sequences().stream().map(SequenceRef::of).toList(),
              impact.types().stream().map(TypeRef::of).toList());
      final List<More> more = more(impact);
      return new GetImpactOutput(
          table.key().qualifiedName(),
          table.key().database(),
          table.type(),
          Affected.of(impact),
          impact.sequences().isEmpty() && impact.types().isEmpty() ? null : dependsOn,
          more,
          notes(impact.functions().coverage(), !more.isEmpty()));
    }

    /** 上限で切り捨てたリストを、概要に並ぶ順に求める */
    private static List<More> more(TableImpact impact) {
      final List<More> more = new ArrayList<>();
      for (final Section section : Section.values()) {
        final int total = section.items(impact).size();
        if (total > MAX_LISTED) {
          more.add(new More(section.value(), total, MAX_LISTED));
        }
      }
      return more;
    }

    /** 結果の読み方の注意。参考情報の状態に応じて、利用者がすべきことも示す */
    private static List<String> notes(FunctionCoverage coverage, boolean hasMore) {
      final List<String> notes = new ArrayList<>();
      if (coverage.total() > 0) {
        notes.add(
            "functionsは、cliを--previewで実行して出力した参考情報から機械的に求めた参考値です。"
                + "載っていなくても使われていないとは限りません（動的SQL、呼び出している関数の中、出力対象外のテーブルは含みません）。");
      }
      if (coverage.noInsight() > 0 || coverage.stale() > 0) {
        notes.add(
            "関数の参考情報が無い（noInsight）または古い（stale）関数があります（件数はcoverage）。"
                + "これらはunverifiedに挙げていません。関数を1つずつ読むより、"
                + "cliを--previewを付けて実行して出力・出し直すと、調べられる範囲が広がります。");
      }
      if (coverage.triggersWithoutFunction() > 0) {
        notes.add(
            "実行する関数名を持たず、本体を直接持つトリガーが同じDBに"
                + coverage.triggersWithoutFunction()
                + "件あります（Oracle）。トリガーの本体の中の参照は含まないため、"
                + "トリガーが触るテーブルは、このツールでは分かりません。");
      }
      if (hasMore) {
        notes.add(
            MAX_LISTED
                + "件を超えたリストは、先頭の"
                + MAX_LISTED
                + "件だけを返しています（moreの各項目）。"
                + "続きは、同じtableにsectionとoffsetを指定して取得してください。");
      }
      notes.add("ビュー・関数の影響は、テーブル単位です（ビューや関数がどのカラムを使うかは、スナップショットに含まれません）。");
      notes.add(
          "dependsOnは、影響を受けるものではなく、このテーブルが使うものです。"
              + "シーケンスはカラムのデフォルト値のnextvalから拾うため、IDENTITY列やトリガーの本体で採番するシーケンスは含みません。");
      return notes;
    }
  }

  /**
   * 上限で切り捨てたリスト
   *
   * @param section 続きを取るときに指定する{@code section}
   * @param total リストの総数
   * @param nextOffset 続きを取るときに指定する{@code offset}
   */
  record More(String section, int total, int nextOffset) {}

  /**
   * {@code section}を指定した場合の結果（1つのリストの1ページ）
   *
   * @param section 指定した{@code section}
   * @param total リストの総数
   * @param nextOffset 続きを取るときに指定する{@code offset}。続きが無い場合はnull
   * @param items このページの要素。形は概要の各リストと同じ
   */
  record SectionOutput(
      String table, String database, String section, int total, Integer nextOffset, List<?> items) {

    static SectionOutput of(TableEntry table, Section section, TableImpact impact, Page page) {
      final List<?> all = section.items(impact);
      return new SectionOutput(
          table.key().qualifiedName(),
          table.key().database(),
          section.value(),
          all.size(),
          page.nextOffset(all.size()),
          page.apply(all));
    }
  }

  /** 影響を受けるもの */
  record Affected(
      ReferencedBy referencedBy,
      List<ViewRef> views,
      List<TriggerRef> triggers,
      Functions functions) {

    static Affected of(TableImpact impact) {
      return new Affected(
          ReferencedBy.of(impact),
          head(impact.views().stream().map(ViewRef::of).toList()),
          head(impact.triggers().stream().map(TriggerRef::of).toList()),
          Functions.of(impact));
    }
  }

  /**
   * 外部キー・論理リレーションで参照しているテーブル
   *
   * @param direct 段1の関連。自己参照を含む
   * @param indirect 段1に現れない、間接的な参照元
   * @param indirectTotal {@code indirect}の総数（上限で切り捨てても、切り捨て前の数）。
   *     {@code list_tables}の{@code impact}と同じく、間接参照の広さの目安として常に示す
   */
  record ReferencedBy(List<DirectRef> direct, List<IndirectRef> indirect, int indirectTotal) {

    static ReferencedBy of(TableImpact impact) {
      final List<IndirectRef> indirect =
          impact.indirectReferences().stream().map(IndirectRef::of).toList();
      return new ReferencedBy(
          head(impact.directReferences().stream().map(DirectRef::of).toList()),
          head(indirect),
          indirect.size());
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
   * @param unverified 自テーブルを使うかどうか、定義本体を読む必要がある関数。{@code usages}に載ったオーバーロードを除く
   */
  record Functions(
      boolean preview, Coverage coverage, List<Usage> usages, List<UnverifiedRef> unverified) {

    static Functions of(TableImpact impact) {
      return new Functions(
          true,
          Coverage.of(impact.functions().coverage()),
          head(impact.functions().usages().stream().map(Usage::of).toList()),
          head(impact.functions().unverified().stream().map(UnverifiedRef::of).toList()));
    }
  }

  /**
   * 自テーブルを使うかどうか、定義本体を読む必要がある関数のオーバーロード1つ
   *
   * @param function {@code スキーマ名.関数名}
   * @param arguments 引数。引数が無い場合は{@code ()}
   * @param reason {@code dynamicSql}・{@code incomplete}・{@code unsupported}
   */
  record UnverifiedRef(String function, String arguments, String reason) {

    static UnverifiedRef of(UnverifiedFunction unverified) {
      return new UnverifiedRef(
          name(unverified.function()),
          unverified.arguments().isEmpty() ? "()" : unverified.arguments(),
          unverified.reason().value());
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
