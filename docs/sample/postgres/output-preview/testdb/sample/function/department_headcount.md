# department_headcount

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|sample.employee|table||○|||

## 定義

```sql
CREATE OR REPLACE FUNCTION sample.department_headcount(p_department_id integer)
 RETURNS bigint
 LANGUAGE sql
 STABLE
 SET search_path TO 'sample'
AS $function$
    select count(*) from employee where department_id = p_department_id;
$function$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
