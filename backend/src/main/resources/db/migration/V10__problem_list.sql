-- 题单 / 训练计划：把题库里的题组织成有顺序的练习清单，并按用户已通过记录计算完成进度。
-- 进度不单独存表，而是查询时用 user_solved_problem 实时计算，
-- 这样重算统计或解绑账号后进度会跟着回退，不会留下对不上的完成标记。

create table problem_list (
    id             bigserial    primary key,
    -- 稳定标识，便于引用与去重，如 CF-STARTER-30
    code           varchar(64)  not null unique,
    title          varchar(128) not null,
    description    varchar(512),
    -- 入门 / 专题 / 校内 等分类，仅用于展示分组
    category       varchar(64),
    -- 主要平台；混合题单可为空
    platform       varchar(32),
    -- MANUAL 人工挑选；GENERATED 按规则从题库生成（规则记录在 generated_rule）
    source         varchar(32)  not null default 'MANUAL',
    generated_rule varchar(255),
    visible        boolean      not null default true,
    sort_order     int          not null default 100,
    created_by     bigint references app_user (id),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

create table problem_list_item (
    id         bigserial   primary key,
    list_id    bigint      not null references problem_list (id) on delete cascade,
    problem_id bigint      not null references problem (id) on delete cascade,
    position   int         not null default 0,
    note       varchar(255),
    created_at timestamptz not null default now(),
    unique (list_id, problem_id)
);
create index idx_list_item_order on problem_list_item (list_id, position, id);
