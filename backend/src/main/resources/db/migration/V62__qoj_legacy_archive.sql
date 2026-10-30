-- No accounts are changed automatically. Run the audited deployment migration only
-- after the Motto flow and browser import have passed acceptance tests.
create table qoj_legacy_archive (
    account_id bigint primary key,
    user_id bigint not null references app_user(id) on delete cascade,
    archived_at timestamptz not null default now(),
    account_data jsonb not null,
    related_data jsonb not null,
    migration_key varchar(64) not null
);
