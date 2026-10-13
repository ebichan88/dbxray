package com.dbxray.mcp.catalog;

/**
 * 中間テーブルを介した多対多の関係にある、相手のテーブル
 *
 * @param table 起点のテーブルから見た相手のテーブル。起点と同じ場合は、同じテーブルを2つの外部キーで参照する自己多対多
 * @param via 2つのテーブルをつなぐ中間テーブル
 */
public record ManyToMany(ObjectKey table, ObjectKey via) {}
