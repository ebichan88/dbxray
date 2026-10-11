package com.dbxray.domain.service.export;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.metrics.DatabaseMetrics;
import com.dbxray.domain.model.target.ExportTargets;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import com.dbxray.domain.model.target.TableDefinitionContent;
import java.util.List;

/**
 * DBから取得したスキーマ情報を、1つの出力形式（Markdownのドキュメント／スナップショット）で書き出すインタフェース<br>
 * 取得処理（一括取得・スキーマ単位・チャンク単位）は出力形式に依らず共通のため、ユースケースは取得した順に各メソッドを呼び出し、
 * どの情報をどう書き出すかは実装に委ねる。出力先のディレクトリは実装の生成時に決まる
 *
 * @see MarkdownExportSinkFactory.MarkdownExportSink
 * @see SnapshotExportSinkFactory.SnapshotExportSink
 */
public interface ExportSink {

  /**
   * 一括取得した情報のみで出力できるもの（一覧・ER図・シーケンス/型の個別定義等）を書き出すメソッド<br>
   * テーブルの詳細情報・関数の定義本体を取得する前に1度だけ呼ばれる
   *
   * @param targets 一括取得した出力対象の情報
   */
  void writeOverview(ExportTargets targets);

  /**
   * スキーマ単位で取得した関数・プロシージャ（定義本体を含む）を書き出すメソッド
   *
   * @param contents 当該スキーマの関数・プロシージャ1つずつの出力内容（定義本体・利用しているテーブル）のリスト
   */
  void writeFunctionDefinitions(
      String schemaName, List<FunctionDefinitionContent> contents, BaseInfoEntity baseInfo);

  /**
   * スキーマ内のテーブル定義を書き出し始める前に呼ばれるメソッド<br>
   * 以降、当該スキーマのテーブルについて{@link #writeTableDefinition}がチャンク単位で繰り返し呼ばれる
   */
  default void beginSchemaTables(String schemaName, BaseInfoEntity baseInfo) {}

  /**
   * 1テーブル分の定義を書き出すメソッド
   *
   * @param content 1テーブル分の定義書出力に必要な情報
   */
  void writeTableDefinition(TableDefinitionContent content);

  /**
   * 一括取得した情報とテーブルの詳細情報の両方から求めた集計を書き出すメソッド<br>
   * すべてのテーブルの定義を書き出した後に1度だけ呼ばれる。集計を使わない出力形式は何もしない
   *
   * @param targets 一括取得した出力対象の情報
   * @param metrics 出力対象の集計（カラムを含めて数え終えたもの）
   */
  default void writeSummary(ExportTargets targets, DatabaseMetrics metrics) {}
}
