package com.dbxray.domain.model.viewpoint;

import com.dbxray.domain.model.relation.ForeignKeyGroup;
import com.dbxray.domain.model.relation.ForeignKeys;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableKey;
import com.dbxray.domain.model.table.TableNamePatterns;
import com.dbxray.domain.model.table.Tables;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 観点（業務ドメイン別にテーブルをまとめる切り口）を表すクラス<br>
 * スキーマ単位・連結成分単位の機械的なまとまりとは別に、「受注管理」「在庫管理」のような人が読む単位でテーブルを束ね、
 * 観点ごとのER図と所属テーブルの一覧を出力するために用いる。サイドカーYAMLの{@code viewpoints}で宣言する。<br>
 * 所属テーブルは、出力対象の範囲（{@code target.tables}）と同じテーブル名パターンの記法（ワイルドカード・除外・スキーマ修飾）で指定する
 */
public final class Viewpoint {

  /** 識別子として使える文字（ファイル名に用いるため、英数字・ハイフン・アンダースコアに限る） */
  private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

  private final String id;
  private final String name;
  private final String description;
  private final TableNamePatterns tableNamePatterns;

  private Viewpoint(
      String id, String name, String description, TableNamePatterns tableNamePatterns) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.tableNamePatterns = tableNamePatterns;
  }

  /**
   * サイドカーYAMLに記述された値から観点を生成する静的ファクトリメソッド<br>
   * 表示名を省略した場合は識別子を表示名とする
   *
   * @param id 識別子（観点ページのファイル名に用いる。英数字・ハイフン・アンダースコアのみ）
   * @param name 表示名（未指定可）
   * @param description 説明（未指定可）
   * @param tablePatterns 所属テーブルのテーブル名パターンのリスト（ワイルドカード・除外・スキーマ修飾を指定可）
   * @throws IllegalArgumentException 識別子が空・使えない文字を含む場合、テーブル名パターンの書き誤り（テーブル名・スキーマ名の部分が空）がある場合、
   *     包含パターン（先頭に{@code !}の無いパターン）が1件も無い場合
   */
  public static Viewpoint of(
      String id, String name, String description, List<String> tablePatterns) {
    final String strippedId = id == null ? "" : id.strip();
    if (!ID_PATTERN.matcher(strippedId).matches()) {
      throw new IllegalArgumentException(
          "Invalid viewpoint id: '"
              + strippedId
              + "' (use only letters, digits, '-' and '_', because it is used as a file name)");
    }
    final TableNamePatterns tableNamePatterns = TableNamePatterns.of(tablePatterns);
    if (!tableNamePatterns.hasInclusion()) {
      throw new IllegalArgumentException(
          "Viewpoint has no table pattern to include (patterns starting with '!' only exclude). [id="
              + strippedId
              + "]");
    }
    final String strippedName = name == null ? "" : name.strip();
    return new Viewpoint(
        strippedId,
        strippedName.isEmpty() ? strippedId : strippedName,
        description == null ? "" : description.strip(),
        tableNamePatterns);
  }

  /**
   * 識別子を取得するメソッド
   *
   * @return 識別子（観点ページのファイル名に用いる）
   */
  public String id() {
    return id;
  }

  /**
   * 表示名を取得するメソッド
   *
   * @return 表示名（未指定の場合は識別子）
   */
  public String name() {
    return name;
  }

  /**
   * 説明を取得するメソッド
   *
   * @return 説明（未指定の場合は空文字）
   */
  public String description() {
    return description;
  }

  /**
   * 指定されたテーブルがこの観点に所属するか判定するメソッド
   *
   * @return 所属する場合はtrue
   */
  public boolean contains(TableEntity table) {
    return tableNamePatterns.matches(TableKey.of(table));
  }

  /**
   * どのテーブルにも一致しない包含パターンを求めるメソッド<br>
   * テーブルのリネーム・削除によって、観点の宣言がDBと乖離していないかの気付きに用いる
   *
   * @return どのテーブルにも一致しない包含パターン（指定された文字列のまま、指定順）
   */
  public List<String> unmatchedPatterns(Tables tables) {
    return tableNamePatterns.unmatchedInclusions(tables);
  }

  /**
   * 出力対象のテーブル・関連から、この観点の出力内容（所属テーブルと、それに関わる関連）を求めるメソッド<br>
   * 関連は、両端がこの観点に所属するもの（観点のER図に描く）と、片端だけが所属するもの（観点外のテーブルとの関連）に分ける
   *
   * @param foreignKeys 出力対象のテーブル同士の関連（外部キー・論理リレーション）
   */
  public ViewpointContent resolve(Tables tables, ForeignKeys foreignKeys) {
    final List<TableEntity> members = tables.stream().filter(this::contains).toList();
    final Set<TableKey> memberKeys = members.stream().map(TableKey::of).collect(Collectors.toSet());
    return new ViewpointContent(
        this,
        members,
        ForeignKeyGroup.of(foreignKeys.withinTables(memberKeys)),
        foreignKeys.crossingTableSetBoundary(memberKeys));
  }
}
