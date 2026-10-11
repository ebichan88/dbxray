package com.dbxray.application;

import java.util.Set;

/**
 * DBドキュメント出力（通常実行）のユースケースへの入力をまとめたrecord<br>
 * エントリーポイント→コントローラー→ユースケースの3層を、分解・再構築を繰り返さずそのまま通過する
 *
 * @param targetSelection 出力対象の絞り込み条件（設定ファイルの{@code target}のスキーマ・テーブル・オブジェクト種別）
 * @param sidecarPath サイドカーYAML（手動付帯情報・論理リレーション・観点）のパス（設定ファイルの{@code annotations}の値）。
 *     空・未指定の場合はマージを行わない
 * @param outputPath 出力先のパス
 * @param chunkSize 詳細情報（カラム・インデックス・制約・外部キー）をまとめて取得するテーブル数の上限。 0以下の場合はスキーマ単位で分割せず取得する
 * @param erDiagramMaxNodes スキーマ別ER図1枚に描画するノード数の上限。超過した場合はER図の代わりに 外部キーの一覧表を出力する。0以下の場合は上限なし
 * @param erDiagramDistance テーブル定義書のER図に描く関連の距離（段数。1〜3）。1は自テーブルの関連だけ、2は関連先のテーブルの関連まで描く。 テーブル数が{@code
 *     erDiagramMaxNodes}を超える場合は、超えない距離まで縮める
 * @param rmDist trueの場合、書き込みを開始する前に{@code outputPath}のベースディレクトリを 再帰的に削除する（{@code
 *     --rm-dist}）。削除されたテーブル等の残骸ファイルを残さずに再生成したい場合に指定する
 * @param previewFeatures 有効にするプレビューの機能（{@code --preview}。指定しない場合は空）
 */
public record ExportSchemaRequest(
    TargetSelection targetSelection,
    String sidecarPath,
    String outputPath,
    int chunkSize,
    int erDiagramMaxNodes,
    int erDiagramDistance,
    boolean rmDist,
    Set<PreviewFeature> previewFeatures) {

  /** プレビューの機能は変更不可な複製として保持する */
  public ExportSchemaRequest {
    previewFeatures = Set.copyOf(previewFeatures);
  }
}
