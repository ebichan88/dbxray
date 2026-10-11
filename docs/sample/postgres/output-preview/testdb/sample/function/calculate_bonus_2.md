# calculate_bonus

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

出力対象のテーブルへの参照は見つかりませんでした。

## 定義

```sql
CREATE OR REPLACE FUNCTION sample.calculate_bonus(p_salary numeric)
 RETURNS numeric
 LANGUAGE sql
 IMMUTABLE
AS $function$
    select sample.calculate_bonus(p_salary, 0.1);
$function$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
