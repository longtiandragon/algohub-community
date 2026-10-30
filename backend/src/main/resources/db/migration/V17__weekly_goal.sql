-- 个人周目标：自己给自己定「这周新通过几题」，可以只针对某个算法方向或某个平台。
--
-- 只存目标本身，进度不存：每次查询都用 user_solved_problem 里**通过时间落在本周**的记录实时算。
-- 这样重算统计、解绑账号或纠错之后，进度会跟着回退，不会留下对不上的完成标记。
--
-- 周起点按 Asia/Shanghai 的周一，与校内其它按自然日的统计保持同一时区。

create table weekly_goal (
    id           bigserial    primary key,
    user_id      bigint       not null references app_user (id) on delete cascade,
    -- 该周的周一（Asia/Shanghai）
    week_start   date         not null,
    -- 本周想新通过的题数
    target       int          not null,
    -- 可选：只统计某个统一算法分类（如「动态规划」）
    category     varchar(64),
    -- 可选：只统计某个平台
    platform     varchar(32),
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    unique (user_id, week_start)
);

create index idx_weekly_goal_user on weekly_goal (user_id, week_start desc);
