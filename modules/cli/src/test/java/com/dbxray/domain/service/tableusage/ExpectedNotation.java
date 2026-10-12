package com.dbxray.domain.service.tableusage;

import java.util.ArrayList;
import java.util.List;

/**
 * テストの期待値の短い記法（{@code "sample.employee:RU, (search_path).audit_log:C [dyn EXECUTE]"}）を読むクラス<br>
 * 名前は拾った順、{@code :}の後ろは操作（C・R・U・D）、{@code (決まり方).}はスキーマの決まり方、{@code [}以降は動的SQL。何も無ければ{@code "-"}
 */
final class ExpectedNotation {

  private ExpectedNotation() {}

  /**
   * 期待値に現れるテーブルの名前を取り出すメソッド
   *
   * @return 名前（スキーマ修飾を含む。決まり方の{@code (…).}は除く）。何も無ければ空
   */
  static List<String> tableNames(String expected) {
    final List<String> names = new ArrayList<>();
    for (final String item : expected.split(" ?\\[")[0].split(", ")) {
      if (item.contains(":")) {
        final String name = item.substring(0, item.lastIndexOf(':'));
        names.add(name.startsWith("(") ? name.substring(name.indexOf(").") + 2) : name);
      }
    }
    return names;
  }
}
