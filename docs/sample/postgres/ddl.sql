-- ============================================================================
-- dbxray サンプルDDL（PostgreSQL）
--
-- テーブル定義書の出力結果サンプルを作成するためのDDLです。
-- 出力対象となりうるオブジェクト種別（テーブル／ビュー／マテリアライズドビュー／
-- インデックス／制約／外部キー／シーケンス／トリガー／関数・プロシージャ／
-- ユーザー定義型（ENUM/COMPOSITE/DOMAIN）／パーティション表）と、ER図の多重度パターン
-- （1対1・0..1対1・1対多・0..1対多・多対多・自己参照・複合外部キー・関連なし）
-- を一通り含めています。
--
-- 実行後、conf/config.yml の target.schemas に [sample] を、
-- annotations に annotations.sample.yml のパスを指定して実行すると
-- 出力結果を確認できます。
-- ============================================================================

/*
 * スキーマ作成
 */
create schema sample;

/*
 * 型定義（ユーザー定義型を3種類網羅。RANGE型は作成時に構築関数が自動生成され
 * 関数一覧サンプルが冗長になるため、ここでは扱わない）
 */
-- ENUM型：employeeのstatus列で使用
create type sample.employee_status_enum as enum ('ACTIVE', 'ON_LEAVE', 'RETIRED');

-- COMPOSITE型：employee_profileのaddress列で使用
create type sample.address_type as (
    street varchar(100),
    city varchar(50),
    postal_code varchar(10)
);

-- DOMAIN型：projectのbudget列で使用
create domain sample.positive_numeric as numeric(12, 2) check (value >= 0);

/*
 * シーケンス
 */
-- どの列にも紐付かない独立したシーケンス（owned_byが空になるパターン）
create sequence sample.invoice_no_seq
    increment by 1
    minvalue 1000
    maxvalue 999999
    start with 1000
    cache 5
    cycle;

/*
 * DDL作成（テーブル）
 */
-- 親テーブル（コメントは付与せず、annotations.sample.yml側で論理名・備考を補う）
create table sample.department (
    department_id serial primary key,
    department_code varchar(10) not null unique,
    department_name varchar(50) not null
);

-- 単独テーブル（他のテーブルと参照関係を持たない）
create table sample.parking_spot (
    parking_spot_id serial primary key,
    spot_code varchar(10) not null unique
);

-- 1対多（必須）：department → employee
-- 0..1対多（任意・自己参照）：employee.manager_id → employee
-- 0..1対1（任意・一意）：parking_spot → employee.parking_spot_id
create table sample.employee (
    employee_id serial primary key,
    employee_code varchar(10) not null unique,
    employee_name varchar(50) not null,
    department_id integer not null references sample.department(department_id),
    manager_id integer references sample.employee(employee_id),
    parking_spot_id integer unique references sample.parking_spot(parking_spot_id),
    status sample.employee_status_enum not null default 'ACTIVE',
    salary numeric(10, 2) not null default 0 check (salary >= 0),
    profile jsonb not null default '{}'::jsonb,
    hired_date date not null default current_date,
    updated_at timestamp not null default now()
);

-- 1対1（必須）：employee ⇔ employee_profile（PKがそのままFK）
create table sample.employee_profile (
    employee_id integer primary key references sample.employee(employee_id),
    address sample.address_type,
    emergency_contact varchar(50),
    notes text
);

create table sample.project (
    project_id serial primary key,
    project_code varchar(10) not null unique,
    project_name varchar(100) not null,
    budget sample.positive_numeric
);

-- 多対多：project ⇔ employee（複合主キー＝2本の1対多外部キーの組み合わせ）
create table sample.project_assignment (
    project_id integer not null references sample.project(project_id),
    employee_id integer not null references sample.employee(employee_id),
    role varchar(30) not null default 'MEMBER',
    assigned_at date not null default current_date,
    primary key (project_id, employee_id)
);

create table sample.warehouse_zone (
    warehouse_code varchar(5) not null,
    zone_code varchar(5) not null,
    zone_name varchar(50) not null,
    primary key (warehouse_code, zone_code)
);

-- 1対多（必須・複合外部キー）：warehouse_zone → shipment
create table sample.shipment (
    shipment_id serial primary key,
    warehouse_code varchar(5) not null,
    zone_code varchar(5) not null,
    shipped_at timestamp not null default now(),
    foreign key (warehouse_code, zone_code) references sample.warehouse_zone(warehouse_code, zone_code)
);

-- 単独テーブル（関連なし。トリガーからの書き込み先）
create table sample.audit_log (
    log_id bigserial primary key,
    table_name varchar(50) not null,
    record_id integer,
    action varchar(10) not null check (action in ('INSERT', 'UPDATE', 'DELETE')),
    changed_at timestamp not null default now(),
    changed_by varchar(50)
);

-- パーティション表（月次のレンジパーティション）。子のパーティションはテーブル一覧・個別の定義書・ER図に並ばず、
-- 親の定義書の「パーティション情報」にまとまる。2026年3月は半月ごとに分ける多段パーティションで、DEFAULTパーティションも持つ
create table sample.attendance (
    attendance_id bigint not null,
    work_date date not null,
    employee_id integer not null references sample.employee(employee_id),
    work_minutes integer,
    primary key (attendance_id, work_date)
) partition by range (work_date);

create table sample.attendance_2026_01 partition of sample.attendance
    for values from ('2026-01-01') to ('2026-02-01');
create table sample.attendance_2026_02 partition of sample.attendance
    for values from ('2026-02-01') to ('2026-03-01');
create table sample.attendance_2026_03 partition of sample.attendance
    for values from ('2026-03-01') to ('2026-04-01') partition by range (work_date);
create table sample.attendance_2026_03_a partition of sample.attendance_2026_03
    for values from ('2026-03-01') to ('2026-03-16');
create table sample.attendance_2026_03_b partition of sample.attendance_2026_03
    for values from ('2026-03-16') to ('2026-04-01');
create table sample.attendance_default partition of sample.attendance default;

-- パーティションは親と別のスキーマにも置ける（出力対象のスキーマは親で決まる）
create schema sample_archive;
create table sample_archive.attendance_2025 partition of sample.attendance
    for values from ('2025-01-01') to ('2026-01-01');

-- 別スキーマに同名のテーブル（sample.department）があっても、索引・カラム等は取り違えず、スキーマごとに取得する
create table sample_archive.department (
    archive_id integer primary key,
    note varchar(50)
);
create index idx_archive_department_note on sample_archive.department (note);

-- パーティション表を参照するテーブル（外部キー制約は参照先のパーティションごとにDB内で複製されるが、定義書・ER図には1本だけ出す）
create table sample.attendance_note (
    note_id serial primary key,
    attendance_id bigint not null,
    work_date date not null,
    note text,
    foreign key (attendance_id, work_date) references sample.attendance(attendance_id, work_date)
);

/*
 * ビュー
 */
create view sample.employee_directory_view as
    select
        e.employee_id,
        e.employee_code,
        e.employee_name,
        d.department_name,
        e.status
    from sample.employee e
    join sample.department d on e.department_id = d.department_id;

-- ビュー・パーティション・出力対象外のスキーマのテーブルを参照するビュー。
-- 定義書の「参照するテーブル」では、パーティションへの参照を親のパーティション表（sample.attendance）への参照としてまとめる
create view sample.attendance_monthly_view as
    select
        dv.department_name,
        date_trunc('month', a.work_date)::date as work_month,
        sum(a.work_minutes) as total_minutes,
        max(ad.note) as archive_note
    from sample_archive.attendance_2025 a
    join sample.employee_directory_view dv on dv.employee_id = a.employee_id
    left join sample_archive.department ad on ad.note = dv.department_name
    group by dv.department_name, date_trunc('month', a.work_date);

/*
 * マテリアライズドビュー
 */
create materialized view sample.project_summary_mv as
    select
        p.project_id,
        p.project_name,
        count(pa.employee_id) as member_count
    from sample.project p
    left join sample.project_assignment pa on pa.project_id = p.project_id
    group by p.project_id, p.project_name;

/*
 * インデックス（btree以外の索引種別・複合索引・マテビューへの索引も含める）
 */
create index idx_employee_department_status on sample.employee (department_id, status);
create index idx_employee_profile_gin on sample.employee using gin (profile);
create unique index project_summary_mv_pkey on sample.project_summary_mv (project_id);
-- パーティション表の索引は、親にパーティションインデックスとして付く（子のパーティションへは自動で複製される）
create index idx_attendance_employee on sample.attendance (employee_id);

/*
 * トリガー用関数
 */
create or replace function sample.set_updated_at() returns trigger
language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create or replace function sample.log_employee_change() returns trigger
language plpgsql as $$
begin
    insert into sample.audit_log(table_name, record_id, action, changed_by)
    values ('employee', coalesce(new.employee_id, old.employee_id), tg_op, current_user);
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create or replace function sample.notify_truncate() returns trigger
language plpgsql as $$
begin
    raise notice 'sample.project_assignment was truncated';
    return null;
end;
$$;

create or replace function sample.check_work_minutes() returns trigger
language plpgsql as $$
begin
    if new.work_minutes < 0 then
        raise exception 'work_minutes must not be negative';
    end if;
    return new;
end;
$$;

create or replace function sample.employee_directory_insert() returns trigger
language plpgsql as $$
declare
    v_department_id integer;
begin
    select department_id into v_department_id
    from sample.department
    where department_name = new.department_name;

    insert into sample.employee(employee_code, employee_name, department_id)
    values (new.employee_code, new.employee_name, v_department_id);
    return new;
end;
$$;

/*
 * トリガー（BEFORE / AFTER / INSTEAD OF、ROW / STATEMENT を一通り網羅）
 */
create trigger trg_employee_set_updated_at
    before update on sample.employee
    for each row execute function sample.set_updated_at();

create trigger trg_employee_audit
    after insert or update or delete on sample.employee
    for each row execute function sample.log_employee_change();

create trigger trg_project_assignment_truncate
    after truncate on sample.project_assignment
    for each statement execute function sample.notify_truncate();

create trigger trg_employee_directory_insert
    instead of insert on sample.employee_directory_view
    for each row execute function sample.employee_directory_insert();

-- パーティション表の行トリガーは、子のパーティションへ複製される
create trigger trg_attendance_check_work_minutes
    before insert or update on sample.attendance
    for each row execute function sample.check_work_minutes();

/*
 * 関数・プロシージャ（オーバーロード関数、プロシージャを含める）
 */
create or replace function sample.calculate_bonus(p_salary numeric, p_rate numeric) returns numeric
language sql immutable as $$
    select p_salary * p_rate;
$$;

create or replace function sample.calculate_bonus(p_salary numeric) returns numeric
language sql immutable as $$
    select sample.calculate_bonus(p_salary, 0.1);
$$;

create or replace procedure sample.raise_salary(p_employee_id integer, p_amount numeric)
language plpgsql as $$
begin
    update sample.employee set salary = salary + p_amount where employee_id = p_employee_id;
end;
$$;

/*
 * 関数・プロシージャが利用しているテーブル（--previewの機能）のサンプル。
 * 改行・コメント・文字列を散らした書き方でも、コメント・文字列の中のDMLは拾わず、
 * INSERT…SELECT・DELETE…USING・UPDATE…FROMの各テーブルの操作を読み分ける
 */
create or replace procedure sample.close_project(p_project_id integer)
language plpgsql as $$
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
$$;

-- スキーマ修飾の無い名前は実行時のsearch_pathで決まるため、スキーマを決めずに候補を示す。
-- テーブル名を引数で受け取る部分は動的SQL（EXECUTE）のため、中のテーブルは抽出できない
create or replace function sample.count_rows(p_table_name text) returns bigint
language plpgsql stable as $$
declare
    v_count bigint;
begin
    if p_table_name = 'employee' then
        select count(*) into v_count from employee;
    else
        execute format('select count(*) from sample.%I', p_table_name) into v_count;
    end if;
    return v_count;
end;
$$;

-- 関数にsearch_pathを指定すると、スキーマ修飾の無い名前のスキーマが決まる
create or replace function sample.department_headcount(p_department_id integer) returns bigint
language sql stable
set search_path = sample
as $$
    select count(*) from employee where department_id = p_department_id;
$$;

/*
 * コメント（論理名）
 * employeeとprojectはDBコメントで論理名を付与し、departmentは無コメントのまま
 * annotations.sample.yml側で説明・備考を補う対比になるようにしている
 */
comment on table sample.employee is '従業員';
comment on column sample.employee.employee_id is '従業員ID';
comment on column sample.employee.employee_code is '従業員コード';
comment on column sample.employee.employee_name is '従業員名';
comment on column sample.employee.department_id is '所属部署ID';
comment on column sample.employee.manager_id is '上長の従業員ID';
comment on column sample.employee.parking_spot_id is '駐車場ID';
comment on column sample.employee.status is '在籍状況';
comment on column sample.employee.salary is '給与';
comment on column sample.employee.profile is 'プロフィール（JSON）';
comment on column sample.employee.hired_date is '入社日';
comment on column sample.employee.updated_at is '更新日時';

comment on table sample.project is 'プロジェクト';
comment on column sample.project.project_id is 'プロジェクトID';
comment on column sample.project.project_code is 'プロジェクトコード';
comment on column sample.project.project_name is 'プロジェクト名';
comment on column sample.project.budget is '予算';

comment on materialized view sample.project_summary_mv is 'プロジェクト別要員数集計';
comment on view sample.attendance_monthly_view is '部署別の月次勤務時間';

comment on table sample.attendance is '勤怠（月次パーティション）';
comment on column sample.attendance.work_date is '勤務日（パーティションキー）';
comment on column sample.attendance.work_minutes is '勤務時間（分）';
