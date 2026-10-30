-- 持续目标独立于原来的单周、分类练习计划。
create table study_goal_default (
    enrollment_year integer primary key,
    monthly_target integer not null check (monthly_target between 0 and 10000)
);
insert into study_goal_default values (2024,60),(2025,50),(2026,30);

create table study_goal_setting (
    user_id bigint primary key references app_user(id) on delete cascade,
    personal_weekly integer check (personal_weekly between 1 and 10000),
    personal_monthly integer check (personal_monthly between 1 and 10000),
    assigned_weekly integer check (assigned_weekly between 0 and 10000),
    assigned_monthly integer check (assigned_monthly between 0 and 10000),
    email_enabled boolean not null default true,
    updated_at timestamptz not null default now()
);

create table study_notification (
    id bigserial primary key,
    user_id bigint not null references app_user(id) on delete cascade,
    kind varchar(32) not null,
    dedup_key varchar(160) not null,
    title varchar(120) not null,
    body text not null,
    created_by bigint references app_user(id) on delete set null,
    created_at timestamptz not null default now(),
    read_at timestamptz,
    unique(user_id,dedup_key)
);
create index idx_study_notification_inbox on study_notification(user_id,id desc);
create index idx_study_notification_unread on study_notification(user_id) where read_at is null;

-- 与站内消息同一事务入队。多实例通过租约领取，失败重试；SMTP 是至少一次交付。
create table study_mail_outbox (
    notification_id bigint primary key references study_notification(id) on delete cascade,
    status varchar(24) not null default 'PENDING'
        check (status in ('PENDING','SENDING','SENT','FAILED','SKIPPED')),
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    lease_token uuid,
    lease_until timestamptz,
    last_error varchar(160),
    sent_at timestamptz
);
create index idx_study_mail_due on study_mail_outbox(available_at) where status in ('PENDING','SENDING');

create table study_goal_audit (
    id bigserial primary key,
    user_id bigint not null references app_user(id) on delete cascade,
    actor_id bigint references app_user(id) on delete set null,
    weekly integer,
    monthly integer,
    created_at timestamptz not null default now()
);
