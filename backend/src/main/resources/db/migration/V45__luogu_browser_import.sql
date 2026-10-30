-- 浏览器同步只暂存精简的 AC 字段；整轮完成才更新排行与动态。
create table luogu_browser_import (
    account_id bigint primary key references platform_account(id) on delete cascade,
    next_page int not null default 1,
    total int,
    per_page int,
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    started_at timestamptz not null default now()
);

create table luogu_browser_import_record (
    account_id bigint not null references luogu_browser_import(account_id) on delete cascade,
    record_id bigint not null,
    problem_key varchar(40) not null,
    title varchar(200),
    difficulty int,
    submitted_at timestamptz not null,
    language varchar(40),
    primary key(account_id, record_id)
);

-- 洛谷评测历史改由用户浏览器读取，服务端不再托管洛谷会话。
delete from platform_credential c using platform_account a
where c.account_id = a.id and a.platform = 'LUOGU';
update platform_account set auth_mode = 'PUBLIC' where platform = 'LUOGU';
