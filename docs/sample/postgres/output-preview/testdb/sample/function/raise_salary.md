# raise_salary

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|sample.employee|table|||○||

## 定義

```sql
CREATE OR REPLACE PROCEDURE sample.raise_salary(IN p_employee_id integer, IN p_amount numeric)
 LANGUAGE plpgsql
AS $procedure$
begin
    update sample.employee set salary = salary + p_amount where employee_id = p_employee_id;
end;
$procedure$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
