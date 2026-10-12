package com.dbxray.domain.service.tableusage;

import com.dbxray.domain.model.tableusage.TableUsageStatus;
import java.util.List;

/**
 * 関数・プロシージャの定義から切り出した結果<br>
 * 解析しない場合（{@link NotAnalyzable}・{@link UnsupportedLanguage}）と、DBMSごとの解析する本体（{@link Postgres}・{@link
 * Oracle}）を、 型で分けて持つ。持つ属性がそれぞれ違い、解析しない場合は字句を持たないため。解析できない理由は、例外にせず結果の型と状態で表す
 */
sealed interface FunctionBody {

  /**
   * 結果の状態を求めるメソッド
   *
   * @return 本体を切り出せた場合は{@link TableUsageStatus#ANALYZED}、それ以外は解析しない理由
   */
  TableUsageStatus status();

  /**
   * 言語以外の理由で解析しない場合の結果
   *
   * @param status 解析しない理由（言語が対象外の場合は{@link UnsupportedLanguage}を使う。{@link
   *     TableUsageStatus#ANALYZED}は解析しない理由ではない）
   */
  record NotAnalyzable(TableUsageStatus status) implements FunctionBody {

    /** 解析しない理由として使えない状態は受け付けない */
    public NotAnalyzable {
      if (status == TableUsageStatus.UNSUPPORTED_LANGUAGE) {
        throw new IllegalArgumentException("Use UnsupportedLanguage for UNSUPPORTED_LANGUAGE");
      }
      if (status == TableUsageStatus.ANALYZED) {
        throw new IllegalArgumentException("ANALYZED is not a reason for skipping the analysis");
      }
    }
  }

  /**
   * 言語が対象外のため解析しない場合の結果
   *
   * @param language 解析しない言語の名前
   */
  record UnsupportedLanguage(String language) implements FunctionBody {

    /** {@inheritDoc} */
    @Override
    public TableUsageStatus status() {
      return TableUsageStatus.UNSUPPORTED_LANGUAGE;
    }
  }

  /** 解析する本体（DBMSに共通の属性） */
  sealed interface Analyzed extends FunctionBody {

    /**
     * 本体の字句を求めるメソッド
     *
     * @return 本体の字句（空白・コメントを除いたもの）。本体ごとに分けて持つ（文の状態が本体をまたがないよう、本体ごとに走査するため）。 字句のリストは、{@link
     *     SqlLexResult}が変更不可で包んだ字句のリストの一部（ビュー）を、複製せずに持つ
     */
    List<List<SqlToken>> segments();

    /**
     * 閉じていない文字列・コメント等があり、定義の終わりまで字句を読み取れなかったかを求めるメソッド
     *
     * @return 読み取れなかった場合はtrue
     */
    boolean incomplete();

    /**
     * オーバーロードされた同名のサブプログラムの本体を区別できず、絞りきれなかった本体をすべて持つかを求めるメソッド
     *
     * @return すべて持つ場合はtrue。Oracleのパッケージのサブプログラムだけが対象で、それ以外は常にfalse
     */
    default boolean overloadsMerged() {
      return false;
    }

    /** {@inheritDoc} */
    @Override
    default TableUsageStatus status() {
      return TableUsageStatus.ANALYZED;
    }
  }

  /**
   * PostgreSQLの本体
   *
   * @param tokens 本体の字句（空白・コメントを除いたもの）。{@link Analyzed#segments}と同じく、複製せずに持つ
   * @param incomplete 定義の終わりまで字句を読み取れなかったか
   * @param searchPath 関数の属性の{@code SET search_path}に並ぶスキーマ（本体の中のSET文・{@code set_config}は含まない。{@code
   *     $user}・{@code pg_catalog}・{@code pg_temp}を除く。指定が無ければ空）
   */
  record Postgres(List<SqlToken> tokens, boolean incomplete, List<String> searchPath)
      implements Analyzed {

    /** 検索パスは変更不可な複製として保持する */
    public Postgres {
      searchPath = List.copyOf(searchPath);
    }

    /** {@inheritDoc} */
    @Override
    public List<List<SqlToken>> segments() {
      return List.of(tokens);
    }
  }

  /**
   * Oracleの本体
   *
   * @param segments 本体の字句。区別できなかった同名のサブプログラムは本体ごとに分けて持つ
   * @param incomplete 定義の終わりまで字句を読み取れなかったか
   * @param overloadsMerged 絞りきれなかった同名のサブプログラムの本体をすべて持つか
   * @param invokerRights 実行者権限（{@code AUTHID CURRENT_USER}）か
   */
  record Oracle(
      List<List<SqlToken>> segments,
      boolean incomplete,
      boolean overloadsMerged,
      boolean invokerRights)
      implements Analyzed {

    /** 字句のリストを束ねるリストは、変更不可な複製として保持する（字句のリストは数百万個になりうるため、複製しない） */
    public Oracle {
      segments = List.copyOf(segments);
    }
  }
}
