# 観点：人事管理（DB名：testdb）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/13|

## 説明

従業員と、その所属・付帯情報を扱うテーブル群。
プロジェクトへのアサインは「プロジェクト管理」の観点を参照。

## ER図

```mermaid
erDiagram
    sample_department["department"]
    sample_employee["employee（従業員）"]
    sample_employee_history["employee_history（従業員履歴）"]
    sample_employee_profile["employee_profile"]
    sample_parking_spot["parking_spot"]
    sample_department ||--o{ sample_employee : "employee_department_id_fkey"
    sample_employee |o--o{ sample_employee : "employee_manager_id_fkey"
    sample_parking_spot |o--o| sample_employee : "employee_parking_spot_id_fkey"
    sample_employee ||--o{ sample_employee_history : "employee_history_employee_id_fkey"
    sample_employee ||--o| sample_employee_profile : "employee_profile_employee_id_fkey"
    sample_department {
        integer department_id PK
    }
    sample_employee {
        integer employee_id PK "従業員ID"
        integer department_id FK "所属部署ID"
        integer manager_id FK "上長の従業員ID"
        integer parking_spot_id FK "駐車場ID"
    }
    sample_employee_history {
        integer employee_id FK
    }
    sample_employee_profile {
        integer employee_id PK, FK
    }
    sample_parking_spot {
        integer parking_spot_id PK
    }
```

## 所属テーブル

| No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
|:---|:---|:---|:---|:---|:---|
|1|sample|department||table|[■](./sample/table/department.md)|
|2|sample|employee|従業員|table|[■](./sample/table/employee.md)|
|3|sample|employee_history|従業員履歴|table|[■](./sample/table/employee_history.md)|
|4|sample|employee_profile||table|[■](./sample/table/employee_profile.md)|
|5|sample|parking_spot||table|[■](./sample/table/parking_spot.md)|

## 観点外のテーブルとの関連

| No. | 参照元 | 外部キー名 | 参照先 |
|:---|:---|:---|:---|
|1|sample.attendance|attendance_employee_id_fkey|sample.employee|
|2|sample.project_assignment|project_assignment_employee_id_fkey|sample.employee|
|3|sample.audit_log|record_id|sample.employee|

___

[観点一覧へ](./viewpointList_testdb.md) / [テーブル一覧へ](./tableList_testdb.md)
