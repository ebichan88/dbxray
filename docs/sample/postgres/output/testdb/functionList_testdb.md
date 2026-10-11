# 関数・プロシージャ一覧（DB名：testdb）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 関数・プロシージャ一覧

| No. | スキーマ名 | 種別 | 名前 | 引数 | 戻り値 | 言語 | Link |
|:---|:---|:---|:---|:---|:---|:---|:---|
|1|sample|FUNCTION|calculate_bonus|p_salary numeric, p_rate numeric|numeric|sql|[■](./sample/function/calculate_bonus_1.md)|
|2|sample|FUNCTION|calculate_bonus|p_salary numeric|numeric|sql|[■](./sample/function/calculate_bonus_2.md)|
|3|sample|FUNCTION|check_work_minutes||trigger|plpgsql|[■](./sample/function/check_work_minutes.md)|
|4|sample|PROCEDURE|close_project|IN p_project_id integer||plpgsql|[■](./sample/function/close_project.md)|
|5|sample|FUNCTION|count_rows|p_table_name text|bigint|plpgsql|[■](./sample/function/count_rows.md)|
|6|sample|FUNCTION|department_headcount|p_department_id integer|bigint|sql|[■](./sample/function/department_headcount.md)|
|7|sample|FUNCTION|employee_directory_insert||trigger|plpgsql|[■](./sample/function/employee_directory_insert.md)|
|8|sample|FUNCTION|log_employee_change||trigger|plpgsql|[■](./sample/function/log_employee_change.md)|
|9|sample|FUNCTION|notify_truncate||trigger|plpgsql|[■](./sample/function/notify_truncate.md)|
|10|sample|PROCEDURE|raise_salary|IN p_employee_id integer, IN p_amount numeric||plpgsql|[■](./sample/function/raise_salary.md)|
|11|sample|FUNCTION|set_updated_at||trigger|plpgsql|[■](./sample/function/set_updated_at.md)|

___

[テーブル一覧へ](./tableList_testdb.md)
