# close_project

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/11|

## 定義

```sql
CREATE OR REPLACE PROCEDURE sample.close_project(IN p_project_id integer)
 LANGUAGE plpgsql
AS $procedure$
declare
    v_count integer;
begin
    -- アサインを外す前に、監査ログへ残す
    insert
      into sample.audit_log   -- 監査ログ
           (table_name, record_id, action, changed_by)
    select 'project_assignment', pa.employee_id, 'DELETE', current_user
      from sample.project_assignment pa /* 対象のプロジェクトのみ */
     where pa.project_id = p_project_id;

    /*
     * 旧仕様ではプロジェクトごと削除していた
     * delete from sample.project where project_id = p_project_id;
     */
    delete from sample.project_assignment pa
     using sample.project p
     where pa.project_id = p.project_id
       and p.project_id = p_project_id;
    get diagnostics v_count = row_count;

    -- 外れた従業員の更新日時を進める
    update sample.employee e
       set updated_at = now()
      from sample.audit_log l
     where l.record_id = e.employee_id
       and l.table_name = 'project_assignment';

    raise notice 'DELETE FROM sample.project_assignment: % rows', v_count;
end;
$procedure$

```

___

[関数・プロシージャ一覧へ](../../functionList_testdb.md)
