-- 学生自建题单与收藏。
-- 原先题单只能由管理员创建（owner_id 为空的即「系统题单」），现在每个同学都能建自己的训练计划，
-- 并决定是只给自己看，还是分享给校内同学。

alter table problem_list add column owner_id bigint references app_user (id) on delete cascade;
-- PRIVATE 仅自己可见；SCHOOL 校内可见。系统题单不用这个字段，由 visible 控制。
alter table problem_list add column share_scope varchar(16) not null default 'PRIVATE';

-- 已有题单都是管理员建的系统题单，保持全校可见
update problem_list set share_scope = 'SCHOOL' where owner_id is null;

create index idx_problem_list_owner on problem_list (owner_id, updated_at desc);

create table problem_list_favorite (
    user_id    bigint      not null references app_user (id) on delete cascade,
    list_id    bigint      not null references problem_list (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, list_id)
);
create index idx_list_favorite_list on problem_list_favorite (list_id);
