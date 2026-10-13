# ER図（DB名：testdb / スキーマ名：sample）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/13|

## ER図

```mermaid
erDiagram
    sample_attendance["attendance（勤怠（月次パーティション））"]
    sample_attendance_note["attendance_note"]
    sample_audit_log["audit_log"]
    sample_department["department"]
    sample_employee["employee（従業員）"]
    sample_employee_history["employee_history（従業員履歴）"]
    sample_employee_profile["employee_profile"]
    sample_parking_spot["parking_spot"]
    sample_project["project（プロジェクト）"]
    sample_project_assignment["project_assignment"]
    sample_shipment["shipment"]
    sample_warehouse_zone["warehouse_zone"]
    sample_employee ||--o{ sample_attendance : "attendance_employee_id_fkey"
    sample_attendance ||--o{ sample_attendance_note : "attendance_note_attendance_id_work_date_fkey"
    sample_department ||--o{ sample_employee : "employee_department_id_fkey"
    sample_employee |o--o{ sample_employee : "employee_manager_id_fkey"
    sample_parking_spot |o--o| sample_employee : "employee_parking_spot_id_fkey"
    sample_employee ||--o{ sample_employee_history : "employee_history_employee_id_fkey"
    sample_employee ||--o| sample_employee_profile : "employee_profile_employee_id_fkey"
    sample_employee ||--o{ sample_project_assignment : "project_assignment_employee_id_fkey"
    sample_project ||--o{ sample_project_assignment : "project_assignment_project_id_fkey"
    sample_warehouse_zone ||--o{ sample_shipment : "shipment_warehouse_code_zone_code_fkey"
    sample_employee |o..o{ sample_audit_log : "record_id"
    sample_attendance {
        bigint attendance_id PK
        date work_date PK "勤務日（パーティションキー）"
        integer employee_id FK
    }
    sample_attendance_note {
        bigint attendance_id FK
        date work_date FK
    }
    sample_audit_log {
        integer record_id FK
    }
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
    sample_project {
        integer project_id PK "プロジェクトID"
    }
    sample_project_assignment {
        integer project_id PK, FK
        integer employee_id PK, FK
    }
    sample_shipment {
        character_varying warehouse_code FK
        character_varying zone_code FK
    }
    sample_warehouse_zone {
        character_varying warehouse_code PK
        character_varying zone_code PK
    }
```

## ER図に掲載しているテーブル

| No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
|:---|:---|:---|:---|:---|:---|
|1|sample|attendance|勤怠（月次パーティション）|table|[■](./sample/table/attendance.md)|
|2|sample|attendance_note||table|[■](./sample/table/attendance_note.md)|
|3|sample|audit_log||table|[■](./sample/table/audit_log.md)|
|4|sample|department||table|[■](./sample/table/department.md)|
|5|sample|employee|従業員|table|[■](./sample/table/employee.md)|
|6|sample|employee_history|従業員履歴|table|[■](./sample/table/employee_history.md)|
|7|sample|employee_profile||table|[■](./sample/table/employee_profile.md)|
|8|sample|parking_spot||table|[■](./sample/table/parking_spot.md)|
|9|sample|project|プロジェクト|table|[■](./sample/table/project.md)|
|10|sample|project_assignment||table|[■](./sample/table/project_assignment.md)|
|11|sample|shipment||table|[■](./sample/table/shipment.md)|
|12|sample|warehouse_zone||table|[■](./sample/table/warehouse_zone.md)|

___

[ER図一覧へ](./erDiagramList_testdb.md) / [テーブル一覧へ](./tableList_testdb.md)
