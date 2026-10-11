package com.dbxray.domain.service.path;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import java.nio.file.Path;
import java.util.Optional;

/**
 * テーブル定義および一覧出力用のパス生成戦略インタフェース <br>
 * 物理レイアウト（ディレクトリ構造・ファイル命名規則）を抽象化する。Markdownドキュメントのファイル名・配置の規則自体は、 ドキュメント間の相対リンクと共有するため{@link
 * DocumentLocations}に定める
 */
public interface OutputPathResolver {

  /**
   * 基本出力ディレクトリを解決する。<br>
   * 設定された出力先パスが未指定・空白の場合は、デフォルトの出力先にフォールバックする
   */
  Path resolveBaseOutputDir(String outputPath);

  /**
   * 出力先ベースディレクトリを、書き込み前にディレクトリごと削除してよいか判定する。<br>
   * カレントディレクトリ・ホームディレクトリ自体と、それらを含む上位のディレクトリ（ルートを含む）は、設定誤りで削除すると 被害が甚大なため削除を認めない
   */
  boolean isRemovableOutputDir(Path baseOutputDir);

  /**
   * データベース単位ディレクトリを返す。<br>
   * 複数のデータベースを同じ出力先へ出力してもドキュメントが混ざらないよう、1つのデータベースに関する
   * Markdownドキュメント（テーブル定義書・一覧・ER図・観点ページ・README）はすべてこの配下にまとめる。<br>
   * 例: {base}/{DB名}/
   */
  Path resolveDatabaseDirectory(OutputRoot root);

  /**
   * テーブル定義書の出力ディレクトリを返す。<br>
   * 例: {base}/{DB名}/{スキーマ名}/{テーブル種別}/
   */
  Path resolveTableDefinitionDirectory(OutputRoot root, TableEntity table);

  /**
   * テーブル定義書の出力ファイルパスを返す。<br>
   * 例: {base}/{DB名}/{スキーマ名}/{テーブル種別}/{物理テーブル名}.md
   */
  Path resolveTableDefinitionFile(OutputRoot root, TableEntity table);

  /**
   * 一覧（テーブル／ER図／関数・プロシージャ／シーケンス／ユーザー定義型／トリガー／観点）のパス。<br>
   * 例: {base}/{DB名}/{接頭辞}List_{DB名}.md
   */
  Path resolveListFile(OutputRoot root, ListDocumentType type);

  /**
   * スキーマ別ER図のパス。<br>
   * 例: {base}/{DB名}/erDiagram_{DB名}_{スキーマ名}.md
   */
  Path resolveErDiagramFile(OutputRoot root, String schemaName);

  /**
   * スキーマ別ER図をテーブルのまとまりごとに分割したページのパス。<br>
   * 例: {base}/{DB名}/erDiagram_{DB名}_{スキーマ名}_group{groupNo}.md
   *
   * @param groupNo グループ番号（1始まり）
   */
  Path resolveErDiagramGroupFile(OutputRoot root, String schemaName, int groupNo);

  /**
   * 観点ページのパス。<br>
   * 例: {base}/{DB名}/viewpoint_{DB名}_{観点の識別子}.md
   */
  Path resolveViewpointFile(OutputRoot root, Viewpoint viewpoint);

  /**
   * データベース単位ディレクトリにまとめたドキュメントへのリンクを集約するREADMEのパスを返す。<br>
   * 例: {base}/{DB名}/README.md
   */
  Path resolveReadmeFile(OutputRoot root);

  /**
   * 行数の多い表を分割した場合の、分割ページのパス。<br>
   * 本体ページと同じディレクトリに置く。例: {base}/{DB名}/tableList_{DB名}.md の2ページ目は {base}/{DB名}/tableList_{DB名}_2.md
   *
   * @param pageIndex ページインデックス（1始まり）
   */
  Path resolvePageFile(Path file, int pageIndex);

  /**
   * スキーマ配下オブジェクト（関数/シーケンス/型）の出力ディレクトリを返す。<br>
   * 例: {base}/{DB名}/{スキーマ名}/{kind}/
   *
   * @param kind オブジェクトの区分（{@link ListDocumentType#FUNCTION}／{@link
   *     ListDocumentType#SEQUENCE}／{@link ListDocumentType#TYPE}）
   */
  Path resolveSchemaObjectDirectory(OutputRoot root, String schemaName, ListDocumentType kind);

  /**
   * スキーマ配下オブジェクト（関数/シーケンス/型）の出力ファイルパスを返す。<br>
   * 例: {base}/{DB名}/{スキーマ名}/{kind}/{name}.md
   *
   * @param kind オブジェクトの区分（{@link ListDocumentType#FUNCTION}／{@link
   *     ListDocumentType#SEQUENCE}／{@link ListDocumentType#TYPE}）
   * @param name ファイル名（拡張子を除く）
   */
  Path resolveSchemaObjectFile(
      OutputRoot root, String schemaName, ListDocumentType kind, String name);

  /**
   * スキーマのスナップショットの出力ディレクトリ（スナップショット全体のルート）を返す。<br>
   * Markdownのドキュメントとは混在させず、専用のディレクトリ配下にまとめる。例: {base}/snapshot/
   */
  Path resolveSnapshotDirectory(Path baseOutputDir);

  /**
   * スナップショットのうち、DB全体の情報の出力ファイルパスを返す。<br>
   * 例: {base}/snapshot/{DB名}/database.json
   */
  Path resolveSnapshotDatabaseFile(OutputRoot root);

  /**
   * スナップショットのうち、スキーマ配下のオブジェクト（テーブル/関数/シーケンス/型）の出力ファイルパスを返す。<br>
   * 種別ごとに1ファイル（1行1オブジェクトのJSON Lines）とする。例: {base}/snapshot/{DB名}/{スキーマ名}/tables.jsonl
   */
  Path resolveSnapshotFile(OutputRoot root, String schemaName, SnapshotKind kind);

  /**
   * スナップショットのファイルパスから、出力しているオブジェクトの種別を判定する。<br>
   * {@link #resolveSnapshotFile}の逆変換。スナップショット同士の比較で、ファイルごとの比較方法を決めるために用いる
   *
   * @return スキーマ配下のオブジェクトのファイルでない場合（{@code database.json}等）は空
   */
  Optional<SnapshotKind> resolveSnapshotKind(Path snapshotFile);

  /**
   * 参考情報（{@code insights}）全体を置くディレクトリを返す。<br>
   * スナップショットとは混在させず、専用のディレクトリ配下にまとめる。例: {base}/insights/
   */
  Path resolveInsightsDirectory(Path baseOutputDir);

  /**
   * 観点の参考情報の出力ファイルパスを返す。<br>
   * 例: {base}/insights/{DB名}/viewpoints.json
   */
  Path resolveViewpointsInsightFile(OutputRoot root);

  /**
   * 関数・プロシージャの利用しているテーブルの参考情報の出力ファイルパスを返す。<br>
   * 例: {base}/insights/{DB名}/{スキーマ名}/functionTableUsages.json
   */
  Path resolveFunctionTableUsagesInsightFile(OutputRoot root, String schemaName);
}
