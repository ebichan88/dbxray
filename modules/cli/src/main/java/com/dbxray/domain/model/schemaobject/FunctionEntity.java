package com.dbxray.domain.model.schemaobject;

/**
 * 関数・プロシージャ情報に関するrecordクラス
 *
 * @param overloadIndex 同じスキーマ内の同名の関数・プロシージャ（オーバーロード）のうち何番目か（1始まり。作成順）
 * @param overloadCount 同じスキーマ内の同名の関数・プロシージャの数（オーバーロードが無い場合は1）
 * @param functionKind 種別（FUNCTION/PROCEDURE）
 * @param functionResult 戻り値の型（プロシージャの場合は空文字）
 * @param definition 定義本体（一覧取得時は空文字）
 */
public record FunctionEntity(
    String dbName,
    String schemaName,
    String functionName,
    int overloadIndex,
    int overloadCount,
    String functionKind,
    String functionArguments,
    String functionResult,
    String languageName,
    String definition) {

  /** プロシージャの種別（{@link #functionKind}の値） */
  private static final String PROCEDURE = "PROCEDURE";

  /**
   * プロシージャであるか判定するメソッド
   *
   * @return プロシージャの場合はtrue。関数の場合はfalse
   */
  public boolean isProcedure() {
    return PROCEDURE.equals(functionKind);
  }

  /**
   * 同じスキーマに同名の関数・プロシージャが複数存在する（オーバーロードされている）か判定するメソッド
   *
   * @return 同名の関数・プロシージャが複数存在する場合はtrue
   */
  public boolean isOverloaded() {
    return overloadCount > 1;
  }
}
