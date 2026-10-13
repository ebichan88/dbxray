package com.dbxray.domain.model.insight;

import com.dbxray.domain.model.tableusage.CrudOperation;
import com.dbxray.domain.model.tableusage.DynamicSqlKind;
import com.dbxray.domain.model.tableusage.FunctionTableUsage;
import com.dbxray.domain.model.tableusage.TableUsage;
import com.dbxray.domain.model.target.FunctionDefinitionContent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 関数・プロシージャが利用しているテーブルの参考情報1ファイル分（1スキーマ分）<br>
 * 定義本体から機械的に抽出した参考値で、スナップショットの事実とは別に置く。関数はスナップショットと同じく、
 * スキーマ名・名前・引数で識別する。値の無い項目（空文字・空のリスト・null）は出力しない（真偽値は真のときだけ値を持つ）
 *
 * @param formatVersion 参考情報の形式のバージョン（形式を互換性なく変更した場合に上げる）
 * @param database DB名。ファイルを置くディレクトリ名はパスに使えない文字を置き換えたもののため、読む側がDB名を求められるよう持つ
 * @param schema スキーマ名
 */
public record FunctionTableUsagesInsight(
    int formatVersion, String database, String schema, List<FunctionInsight> functions) {

  /** 現行の参考情報の形式のバージョン */
  public static final int FORMAT_VERSION = 1;

  /** リストは変更不可な複製として保持する */
  public FunctionTableUsagesInsight {
    functions = List.copyOf(functions);
  }

  /**
   * 1スキーマ分の関数・プロシージャの出力内容から参考情報を生成するメソッド
   *
   * @param contents 当該スキーマの関数・プロシージャの出力内容（抽出を行ったもの）
   */
  public static FunctionTableUsagesInsight of(
      String databaseName, String schemaName, List<FunctionDefinitionContent> contents) {
    final DefinitionHasher hasher = new DefinitionHasher();
    return new FunctionTableUsagesInsight(
        FORMAT_VERSION,
        databaseName,
        schemaName,
        contents.stream().map(content -> FunctionInsight.of(content, hasher)).toList());
  }

  /**
   * 関数・プロシージャ1つ分
   *
   * @param arguments 引数（スナップショットと同じ表記）
   * @param definitionHash 抽出元の定義本体（スナップショットの{@code definition}と同じ文字列）のSHA-256（16進数の小文字）。
   *     MCPサーバーがスナップショットの定義本体と照らし、定義が変わった後の古い結果を返さないために使う。定義本体が無い場合はnull
   * @param status 解析の状態（{@code analyzed}・{@code unsupported_language}・{@code no_definition}・
   *     {@code wrapped}・{@code subprogram_not_found}）
   * @param language 解析しなかった言語の名前（{@code unsupported_language}の場合だけ）
   * @param dynamicSql 定義本体に含まれる動的SQLの種類
   * @param incomplete 字句を最後まで読めなかった場合だけtrue（それ以外はnullで、出力しない）
   * @param overloadsMerged 同名のサブプログラムの本体をまとめて抽出した場合だけtrue（それ以外はnullで、出力しない）
   */
  public record FunctionInsight(
      String name,
      String arguments,
      String definitionHash,
      String status,
      String language,
      List<TableInsight> tables,
      List<String> dynamicSql,
      Boolean incomplete,
      Boolean overloadsMerged) {

    /** リストは変更不可な複製として保持する */
    public FunctionInsight {
      tables = List.copyOf(tables);
      dynamicSql = List.copyOf(dynamicSql);
    }

    /** 関数・プロシージャの出力内容から、参考情報の1件分を生成するメソッド */
    static FunctionInsight of(FunctionDefinitionContent content, DefinitionHasher hasher) {
      final FunctionTableUsage usage = content.tableUsage();
      return new FunctionInsight(
          content.function().functionName(),
          content.function().functionArguments(),
          hasher.hashOf(content.function().definition()),
          usage.status().id(),
          usage.language(),
          usage.tables().stream().map(TableInsight::of).toList(),
          usage.dynamicSql().stream().map(DynamicSqlKind::id).toList(),
          usage.incomplete() ? Boolean.TRUE : null,
          usage.overloadsMerged() ? Boolean.TRUE : null);
    }
  }

  /**
   * 定義本体（UTF-8）のSHA-256を16進数の小文字で求めるクラス<br>
   * Oracleのパッケージのサブプログラムは、どれも定義にパッケージ全体（仕様部と本体）を持つ。サブプログラムの数だけ同じ本体をハッシュしないよう、
   * 直前に求めた定義本体と同じなら結果を使い回す
   */
  private static final class DefinitionHasher {

    private String lastDefinition;
    private String lastHash;

    /** スナップショットが空白だけの定義本体を持たないのに合わせ、その場合はnullを返す */
    String hashOf(String definition) {
      if (definition == null || definition.isBlank()) {
        return null;
      }
      if (!definition.equals(lastDefinition)) {
        lastHash = sha256(definition);
        lastDefinition = definition;
      }
      return lastHash;
    }

    private static String sha256(String definition) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(definition.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException e) {
        // SHA-256はJava SEの実装が必ず備えるアルゴリズムのため、起こらない
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * 利用しているテーブル1件
   *
   * @param schema スキーマ名。スキーマが決まらない名前は空文字（出力しない）
   * @param schemaResolution スキーマが決まらない名前が何で決まるか（{@code search_path}・{@code current_user}・
   *     {@code synonym}）。スキーマが決まった場合は空文字（出力しない）
   * @param schemaCandidates スキーマが決まらない名前の、同じ名前の出力対象のテーブルがあるスキーマ
   * @param operations 操作（{@code C}・{@code R}・{@code U}・{@code D}の順）
   */
  public record TableInsight(
      String schema,
      String name,
      String schemaResolution,
      List<String> schemaCandidates,
      List<String> operations) {

    /** リストは変更不可な複製として保持する */
    public TableInsight {
      schemaCandidates = List.copyOf(schemaCandidates);
      operations = List.copyOf(operations);
    }

    /** 利用しているテーブルから、参考情報の1件分を生成するメソッド */
    static TableInsight of(TableUsage usage) {
      return new TableInsight(
          usage.schemaName(),
          usage.tableName(),
          usage.resolution().id(),
          usage.schemaCandidates(),
          usage.orderedOperations().stream().map(CrudOperation::letter).toList());
    }
  }
}
