package com.dbxray.domain.service.tableusage;

import java.util.Collections;
import java.util.List;

/**
 * 字句の解析結果
 *
 * @param tokens 空白・コメントを除いた字句（出現順）
 * @param incomplete 閉じていない文字列・コメント・ドル引用符・引用符付き識別子があり、範囲の終わりまでをその要素とみなしたか
 */
record SqlLexResult(List<SqlToken> tokens, boolean incomplete) {

  /** 字句のリストは変更不可にして保持する（字句は数百万個になりうるため、複製せず包む。字句を足したレキサー自身はこの後リストに触れない） */
  SqlLexResult {
    tokens = Collections.unmodifiableList(tokens);
  }
}
