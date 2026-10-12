package com.dbxray.mcp.catalog;

import java.util.List;

/**
 * テーブル検索で一致した1テーブル
 *
 * @param score 一致の強さ。大きいほど上位に並べる
 * @param matchedIn 一致した項目（{@code name}・{@code logicalName}・{@code description}・
 *     {@code remarks}・{@code column:カラム名}）。一致した順に重複なく並ぶ
 */
public record TableHit(TableEntry table, int score, List<String> matchedIn) {}
