-- 文件导入：记录来源、覆盖范围与验证状态。
-- 导入记录属于用户自报数据，不等同于服务器可独立核验的记录，因此单独标识、不与已核验数据无区别混排。

create table import_batch (
    id                 bigserial primary key,
    user_id            bigint      not null references app_user (id),
    account_id         bigint      not null references platform_account (id) on delete cascade,
    platform           varchar(32) not null,
    source_format      varchar(32) not null,
    file_name          varchar(255),
    -- 实际覆盖的题集范围：只声明本次文件包含的题集，不代表该平台全部历史
    problem_set_ids    text,
    record_count       int         not null default 0,
    accepted_count     int         not null default 0,
    with_time_count    int         not null default 0,
    without_time_count int         not null default 0,
    covered_from       timestamptz,
    covered_to         timestamptz,
    verification_state varchar(32) not null default 'SELF_REPORTED',
    note               varchar(512),
    created_at         timestamptz not null default now()
);
create index idx_import_batch_user on import_batch (user_id, created_at desc);

-- 区分同步入库与文件导入的提交记录
alter table submission add column source varchar(16) not null default 'SYNC';
alter table submission add column import_batch_id bigint references import_batch (id) on delete set null;
create index idx_submission_source on submission (account_id, source);
