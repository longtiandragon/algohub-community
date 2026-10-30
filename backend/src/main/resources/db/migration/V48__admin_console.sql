-- 管理端扩充（docs/2026-09-30-管理端扩充.md）：管理员权限、访问统计、操作日志。

-- 管理员权限：null = 全部权限（已有管理员、引导创建的管理员都是 null，行为不变）；数组 = 只有这些权限
alter table app_user add column admin_permissions varchar(32)[];
-- 最近一次打开页面的时间（访问上报时更新，至多 5 分钟写一次）
alter table app_user add column last_seen_at timestamptz;

-- 每个访客每天一行：登录用户 'u:<id>'；未登录访客 'a:<当天 IP + UA 的哈希>'，不设 Cookie、不跨天跟踪
create table site_visit_daily (
    day         date        not null,
    visitor_key varchar(64) not null,
    user_id     bigint references app_user (id) on delete set null,
    views       int         not null default 1,
    first_at    timestamptz not null default now(),
    last_at     timestamptz not null default now(),
    primary key (day, visitor_key)
);
create index idx_site_visit_daily_user on site_visit_daily (user_id, day) where user_id is not null;

-- 每个页面每天被打开的次数：只记路由名，不记完整路径（避免记下别人档案的编号之类）
create table site_page_daily (
    day   date        not null,
    route varchar(64) not null,
    views int         not null default 1,
    primary key (day, route)
);

-- 管理操作日志：/api/admin/** 的非 GET 请求处理完后记一条，成功失败都记
create table admin_audit_log (
    id         bigserial primary key,
    actor_id   bigint references app_user (id) on delete set null,
    actor_name varchar(64)  not null,
    method     varchar(8)   not null,
    pattern    varchar(160) not null,
    target     varchar(160),
    status     int          not null,
    ip         varchar(64),
    created_at timestamptz  not null default now()
);
create index idx_admin_audit_created on admin_audit_log (created_at desc);
create index idx_admin_audit_actor on admin_audit_log (actor_id, created_at desc);
