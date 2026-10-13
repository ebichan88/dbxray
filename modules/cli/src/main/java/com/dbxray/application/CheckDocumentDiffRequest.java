package com.dbxray.application;

/**
 * DB vs ドキュメントの差分検知（{@code --check}モード）のユースケースへの入力をまとめたrecord<br>
 * {@link ExportSchemaRequest}と異なり、Markdownの描画・ER図の生成を行わないため{@code erDiagramMaxNodes}・{@code erDiagramDistance}を、
 * 書き込み前の出力先削除を行わないため{@code rmDist}を、プレビューの機能はスナップショットを変えないため{@code previewFeatures}を持たない
 *
 * @param targetSelection 出力対象の絞り込み条件（設定ファイルの{@code target}のスキーマ・テーブル・オブジェクト種別）
 * @param sidecarPath サイドカーYAML（手動付帯情報・論理リレーション・観点）のパス（設定ファイルの{@code annotations}の値）。
 *     空・未指定の場合はマージを行わない
 * @param outputPath 比較対象となる、既にコミット済みのドキュメントが配置されたパス
 * @param chunkSize 詳細情報をまとめて取得するテーブル数の上限。0以下の場合はスキーマ単位で分割せず取得する
 */
public record CheckDocumentDiffRequest(
    TargetSelection targetSelection, String sidecarPath, String outputPath, int chunkSize) {}
