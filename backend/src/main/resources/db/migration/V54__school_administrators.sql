create table school_administrator (
    user_id bigint not null references app_user(id) on delete cascade,
    school_id bigint not null references school(id) on delete cascade,
    granted_by bigint references app_user(id) on delete set null,
    created_at timestamptz not null default now(),
    primary key(user_id,school_id)
);
alter table announcement add column school_id bigint references school(id) on delete cascade;
create index announcement_school_idx on announcement(school_id);
