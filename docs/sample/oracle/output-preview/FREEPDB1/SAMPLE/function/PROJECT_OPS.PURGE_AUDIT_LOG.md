# PROJECT_OPS.PURGE_AUDIT_LOG

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|Oracle 23|FREEPDB1|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

動的SQL（EXECUTE IMMEDIATE）を含むため、この一覧に無いテーブルを利用している可能性があります。

| No. | テーブル | 区分 | C | R | U | D |
|:---|:---|:---|:---|:---|:---|:---|
|1|SAMPLE.AUDIT_LOG|table||||○|

## 定義

```sql
CREATE OR REPLACE package project_ops as
    procedure close_project(p_project_id number);
    procedure close_project(p_project_code varchar2);
    procedure purge_audit_log(p_table_name varchar2);
end project_ops;

CREATE OR REPLACE package body project_ops as
    -- コードからIDを引いて、ID版に委ねる
    procedure close_project(p_project_code varchar2) is
        v_project_id sample.project.project_id%type;
    begin
        select project_id into v_project_id from sample.project where project_code = p_project_code;
        close_project(v_project_id);
    end close_project;

    procedure close_project(p_project_id number) is
    begin
        /* アサインを外した記録を監査ログへ残す（同じ従業員の記録があれば日時だけ更新する） */
        merge into sample.audit_log l
        using (
            select pa.employee_id
              from sample.project_assignment pa
             where pa.project_id = p_project_id
        ) s
           on (l.table_name = 'project_assignment' and l.record_id = s.employee_id)
         when matched then
            update set l.changed_at = systimestamp
         when not matched then
            insert (table_name, record_id, action, changed_by)
            values ('project_assignment', s.employee_id, 'DELETE', user);

        -- delete from sample.project where project_id = p_project_id;  -- 旧仕様
        delete sample.project_assignment
         where project_id = p_project_id;
        dbms_output.put_line(q'[DELETE FROM sample.project_assignment: 'done']');
    end close_project;

    -- 修飾の無い名前は、定義者権限のため所有者（SAMPLE）のテーブルとみなす。件数は動的SQLで数える
    procedure purge_audit_log(p_table_name varchar2) is
        v_count number;
    begin
        execute immediate 'select count(*) from sample.audit_log where table_name = :1'
            into v_count using p_table_name;
        delete from audit_log
         where table_name = p_table_name
           and changed_at < add_months(systimestamp, -12);
    end purge_audit_log;
end project_ops;
```

___

[関数・プロシージャ一覧へ](../../functionList_FREEPDB1.md)
