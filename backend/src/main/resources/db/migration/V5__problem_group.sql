-- 跨平台题目映射：同一来源的复用题、跨平台搬运题通过明确映射处理，不靠题名猜测。
-- 只有被人工映射到同一个题目组的题目才会在统计中合并计数；未映射的题目仍按各平台分别计数。

create table problem_group (
    id            bigserial primary key,
    -- 人工指定的规范标识，如 LUOGU/P1001；用于展示与查重
    canonical_key varchar(128) not null unique,
    title         varchar(255),
    note          varchar(512),
    created_by    bigint references app_user (id),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

create table problem_group_member (
    id         bigserial primary key,
    group_id   bigint      not null references problem_group (id) on delete cascade,
    -- 一道题最多属于一个题目组，避免出现互相冲突的合并口径
    problem_id bigint      not null unique references problem (id) on delete cascade,
    relation   varchar(32) not null default 'SAME_PROBLEM',
    -- MANUAL：人工确认；不提供按题名自动猜测的来源
    source     varchar(32) not null default 'MANUAL',
    created_by bigint references app_user (id),
    created_at timestamptz not null default now()
);
create index idx_group_member_group on problem_group_member (group_id);
