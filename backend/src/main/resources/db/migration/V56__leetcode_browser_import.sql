-- 浏览器同步只暂存精简的 AC 字段；整轮完成才更新排行与动态。
create table leetcode_browser_import (
    account_id bigint primary key references platform_account(id) on delete cascade,
    next_page int not null default 1,
    total int,
    per_page int,
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    started_at timestamptz not null default now()
);

create table leetcode_browser_import_record (
    account_id bigint not null references leetcode_browser_import(account_id) on delete cascade,
    record_id bigint not null,
    problem_key varchar(128) not null,
    title varchar(200),
    difficulty int,
    submitted_at timestamptz not null,
    language varchar(40),
    primary key(account_id, record_id)
);


create table leetcode_auto_sync_grant (
    account_id bigint primary key references platform_account(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    last_used_at timestamptz,
    created_at timestamptz not null default now()
);
