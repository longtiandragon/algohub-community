-- 题目收藏与复习记录：同学自己标记的「稍后再做」「待复习」，以及一条私人笔记。
--
-- 这张表只保存**自己声明的意图**，不参与任何通过量统计，也不改写 user_solved_problem：
-- 标了「待复习」不代表通过过，取消收藏也不会动真实的 AC 记录。
-- 是否已通过一律实时查 user_solved_problem，不在这里冗余存一份，避免两份数据对不上。
--
-- 笔记默认仅本人可见，接口层只按当前登录用户查询，不提供按他人 ID 查询的入口；
-- 也不保存源站的提交代码。

create table problem_bookmark (
    id         bigserial    primary key,
    user_id    bigint       not null references app_user (id) on delete cascade,
    problem_id bigint       not null references problem (id) on delete cascade,
    -- LATER 稍后再做；REVIEW 待复习；DONE 自己认为这题已经过关（都是自报，不是平台数据）
    status     varchar(16)  not null default 'LATER',
    -- 私人笔记，仅本人可见
    note       varchar(500),
    -- 自选的复习日期（北京时间自然日），到期只在站内提示，不发邮件或推送
    remind_on  date,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    unique (user_id, problem_id)
);

-- 列表按「最近更新」排，到期复习的另走 remind_on
create index idx_bookmark_user_updated on problem_bookmark (user_id, updated_at desc, id desc);
create index idx_bookmark_remind on problem_bookmark (user_id, remind_on) where remind_on is not null;
