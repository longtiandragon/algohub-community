-- 比赛与 Rating（第四阶段增强能力）：Codeforces 的 user.rating 与 AtCoder 的比赛历史。
-- 按账号 + 比赛唯一，重复同步只更新不重复；和刷题数据一样在核对执行编号的短事务里写入。
create table contest_participation (
    account_id   bigint       not null references platform_account (id) on delete cascade,
    contest_key  varchar(64)  not null,
    contest_name varchar(300) not null,
    place        integer,
    old_rating   integer,
    new_rating   integer,
    -- AtCoder 的表现分；Codeforces 没有
    performance  integer,
    -- AtCoder 有不计分的场次：参加了但 Rating 不变
    rated        boolean      not null,
    finished_at  timestamptz  not null,
    updated_at   timestamptz  not null default now(),
    primary key (account_id, contest_key)
);

create index idx_contest_participation_time on contest_participation (account_id, finished_at);
