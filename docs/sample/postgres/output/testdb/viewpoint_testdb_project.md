# 観点：プロジェクト管理（DB名：testdb）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/13|

## 説明

プロジェクトと要員のアサインを扱うテーブル群。

## ER図

```mermaid
erDiagram
    sample_employee["employee（従業員）"]
    sample_project["project（プロジェクト）"]
    sample_project_assignment["project_assignment"]
    sample_employee |o--o{ sample_employee : "employee_manager_id_fkey"
    sample_employee ||--o{ sample_project_assignment : "project_assignment_employee_id_fkey"
    sample_project ||--o{ sample_project_assignment : "project_assignment_project_id_fkey"
    sample_employee {
        integer employee_id PK "従業員ID"
        integer manager_id FK "上長の従業員ID"
    }
    sample_project {
        integer project_id PK "プロジェクトID"
    }
    sample_project_assignment {
        integer project_id PK, FK
        integer employee_id PK, FK
    }
```

## 所属テーブル

| No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
|:---|:---|:---|:---|:---|:---|
|1|sample|employee|従業員|table|[■](./sample/table/employee.md)|
|2|sample|project|プロジェクト|table|[■](./sample/table/project.md)|
|3|sample|project_assignment||table|[■](./sample/table/project_assignment.md)|
|4|sample|project_summary_mv|プロジェクト別要員数集計|materialized_view|[■](./sample/materialized_view/project_summary_mv.md)|

## 観点外のテーブルとの関連

| No. | 参照元 | 外部キー名 | 参照先 |
|:---|:---|:---|:---|
|1|sample.attendance|attendance_employee_id_fkey|sample.employee|
|2|sample.employee|employee_department_id_fkey|sample.department|
|3|sample.employee|employee_parking_spot_id_fkey|sample.parking_spot|
|4|sample.employee_history|employee_history_employee_id_fkey|sample.employee|
|5|sample.employee_profile|employee_profile_employee_id_fkey|sample.employee|
|6|sample.audit_log|record_id|sample.employee|

___

[観点一覧へ](./viewpointList_testdb.md) / [テーブル一覧へ](./tableList_testdb.md)
