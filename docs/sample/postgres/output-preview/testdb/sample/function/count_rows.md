# count_rows

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

動的SQL（EXECUTE）を含むため、この一覧に無いテーブルを利用している可能性があります。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|(search_path).employee|table||○|||

(search_path) は、スキーマ修飾が無く、実行時のsearch_pathで決まる名前です。出力対象で同じ名前のテーブルがあるスキーマは次のとおりです。

- employee: sample

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
