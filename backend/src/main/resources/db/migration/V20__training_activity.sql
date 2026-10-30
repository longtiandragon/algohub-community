-- 校内训练活动：管理员发布一段有起止时间的训练，关联一个题单，同学按活动期间的通过情况看进度。
--
-- 只存活动本身，进度一律实时算：
-- 「活动期间通过」= 该题单里的题、通过时间落在 [start_at, end_at) 内的；
-- 「活动前已通过」= 通过时间早于 start_at 的。两者分开展示，不把开赛前就会的题算成训练成果。
-- 通过时间未知的记录两边都不计入，另行报数，不猜时间。
--
-- 不建「报名表」：报名与否不改变任何客观事实，进度本来就由通过记录决定，
-- 多一张表只会多一处可能对不上的状态。

create table training_activity (
    id          bigserial     primary key,
    title       varchar(128)  not null,
    description varchar(1000),
    -- 关联的题单；题单被删除时活动保留，只是不再有题目范围
    list_id     bigint        references problem_list (id) on delete set null,
    start_at    timestamptz   not null,
    end_at      timestamptz   not null,
    -- 未发布的活动只有管理员可见
    published   boolean       not null default false,
    created_by  bigint        references app_user (id) on delete set null,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now(),
    constraint training_activity_window check (end_at > start_at)
);

create index idx_training_activity_window on training_activity (start_at desc, id desc);
