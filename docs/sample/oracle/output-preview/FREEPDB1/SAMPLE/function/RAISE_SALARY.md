# RAISE_SALARY

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|Oracle 23|FREEPDB1|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|SAMPLE.EMPLOYEE|table|||○||

## 定義

```sql
CREATE OR REPLACE procedure raise_salary(p_employee_id number, p_amount number) as
begin
    update sample.employee set salary = salary + p_amount where employee_id = p_employee_id;
end;
```

___

[関数・プロシージャ一覧へ](../../functionList_FREEPDB1.md)
