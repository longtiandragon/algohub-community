-- 身份体系：个人资料、隐私设置、第三方账号绑定与认证审计。
-- 令牌本身不落库，放在 Redis 中（可失效、可全端下线）；这里只保存与身份有关的持久数据。

alter table app_user add column avatar_url varchar(512);
alter table app_user add column bio varchar(255);
-- 仅用 GitHub 绑定登录的账号可以没有本地密码
alter table app_user alter column password_hash drop not null;
alter table app_user add column last_login_at timestamptz;
-- 修改密码或主动下线后，签发时间早于该时刻的令牌一律失效
alter table app_user add column tokens_valid_from timestamptz not null default now();

-- 隐私设置：默认只对校内成员可见，身份类信息默认不公开
create table user_privacy (
    user_id            bigint      primary key references app_user (id) on delete cascade,
    profile_visibility varchar(16) not null default 'SCHOOL',
    show_on_ranking    boolean     not null default true,
    show_accounts      boolean     not null default true,
    show_student_no    boolean     not null default false,
    show_email         boolean     not null default false,
    updated_at         timestamptz not null default now()
);

-- 第三方账号绑定。一个平台账号只能绑定一个本地用户，一个用户每个平台只能绑一个。
create table user_oauth_binding (
    id            bigserial primary key,
    user_id       bigint       not null references app_user (id) on delete cascade,
    provider      varchar(32)  not null,
    provider_uid  varchar(64)  not null,
    login         varchar(128),
    display_name  varchar(128),
    avatar_url    varchar(512),
    profile_url   varchar(255),
    linked_at     timestamptz  not null default now(),
    last_login_at timestamptz,
    unique (provider, provider_uid),
    unique (user_id, provider)
);

-- 认证审计：登录、登出、改密、绑定等，用于排查异常登录。不记录密码与令牌内容。
create table auth_event (
    id         bigserial   primary key,
    user_id    bigint references app_user (id) on delete set null,
    username   varchar(64),
    event      varchar(32) not null,
    ip         varchar(64),
    user_agent varchar(255),
    detail     varchar(255),
    created_at timestamptz not null default now()
);
create index idx_auth_event_user on auth_event (user_id, created_at desc);

-- 已有用户补齐默认隐私设置
insert into user_privacy (user_id)
select id from app_user
on conflict (user_id) do nothing;
