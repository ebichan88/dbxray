# log_employee_change

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|sample.audit_log|table|○||||

## 定義

```sql
CREATE OR REPLACE FUNCTION sample.log_employee_change()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
begin
    insert into sample.audit_log(table_name, record_id, action, changed_by)
    values ('employee', coalesce(new.employee_id, old.employee_id), tg_op, current_user);
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$function$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
