# employee_directory_insert

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|sample.department|table||○|||
|2|sample.employee|table|○||||

## 定義

```sql
CREATE OR REPLACE FUNCTION sample.employee_directory_insert()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
declare
    v_department_id integer;
begin
    select department_id into v_department_id
    from sample.department
    where department_name = new.department_name;

    insert into sample.employee(employee_code, employee_name, department_id)
    values (new.employee_code, new.employee_name, v_department_id);
    return new;
end;
$function$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
