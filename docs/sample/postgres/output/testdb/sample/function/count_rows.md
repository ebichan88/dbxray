# count_rows

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 定義

```sql
CREATE OR REPLACE FUNCTION sample.count_rows(p_table_name text)
 RETURNS bigint
 LANGUAGE plpgsql
 STABLE
AS $function$
declare
    v_count bigint;
begin
    if p_table_name = 'employee' then
        select count(*) into v_count from employee;
    else
        execute format('select count(*) from sample.%I', p_table_name) into v_count;
    end if;
    return v_count;
end;
$function$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
