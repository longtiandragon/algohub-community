create table weekly_training (
 id bigserial primary key,
 school_id bigint not null references school(id),
 week_start date not null,
 platform varchar(32) not null,
 contest_ref varchar(100) not null,
 metadata jsonb not null,
 targets jsonb not null,
 created_by bigint references app_user(id) on delete set null,
 unique(school_id,week_start,platform,contest_ref)
);
create index weekly_training_templates on weekly_training(school_id,platform,(metadata->>'category'),id desc);
create table weekly_account_result (
 training_id bigint not null references weekly_training(id) on delete cascade,
 account_id bigint not null references platform_account(id) on delete cascade,
 result jsonb,
 refreshed_at timestamptz,
 next_attempt timestamptz not null default now(),
 failed boolean not null default false,
 primary key(training_id,account_id)
);
create index weekly_account_result_due on weekly_account_result(next_attempt,training_id,account_id);
