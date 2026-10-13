# テーブル一覧（DB名：testdb）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/13|

## 関連ドキュメント

* [ER図一覧](./erDiagramList_testdb.md)  
* [関数・プロシージャ一覧](./functionList_testdb.md)  
* [シーケンス一覧](./sequenceList_testdb.md)  
* [ユーザー定義型一覧](./typeList_testdb.md)  
* [トリガー一覧](./triggerList_testdb.md)  
* [観点一覧](./viewpointList_testdb.md)  

## テーブル情報

| No. | スキーマ名 | 論理テーブル名 | 物理テーブル名 | 区分 | Link |
|:---|:---|:---|:---|:---|:---|
|1|sample|勤怠（月次パーティション）|attendance|table|[■](./sample/table/attendance.md)|
|2|sample|部署別の月次勤務時間|attendance_monthly_view|view|[■](./sample/view/attendance_monthly_view.md)|
|3|sample||attendance_note|table|[■](./sample/table/attendance_note.md)|
|4|sample||audit_log|table|[■](./sample/table/audit_log.md)|
|5|sample||department|table|[■](./sample/table/department.md)|
|6|sample|従業員|employee|table|[■](./sample/table/employee.md)|
|7|sample||employee_directory_view|view|[■](./sample/view/employee_directory_view.md)|
|8|sample|従業員履歴|employee_history|table|[■](./sample/table/employee_history.md)|
|9|sample||employee_profile|table|[■](./sample/table/employee_profile.md)|
|10|sample||parking_spot|table|[■](./sample/table/parking_spot.md)|
|11|sample|プロジェクト|project|table|[■](./sample/table/project.md)|
|12|sample||project_assignment|table|[■](./sample/table/project_assignment.md)|
|13|sample|プロジェクト別要員数集計|project_summary_mv|materialized_view|[■](./sample/materialized_view/project_summary_mv.md)|
|14|sample||shipment|table|[■](./sample/table/shipment.md)|
|15|sample||warehouse_zone|table|[■](./sample/table/warehouse_zone.md)|

