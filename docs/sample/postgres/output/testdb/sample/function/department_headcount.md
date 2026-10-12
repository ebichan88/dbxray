# department_headcount

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

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
