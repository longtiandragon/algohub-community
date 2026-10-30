-- A private observation does not reserve ownership. Existing rows and credentials remain.
drop index uk_platform_uid;
create unique index uk_platform_uid on platform_account(platform,platform_uid)
where platform_uid is not null and ownership_status='VERIFIED';
create unique index uk_user_platform_uid on platform_account(user_id,platform,platform_uid)
where platform_uid is not null;

-- Independent local observations must not steal each other's submission rows.
alter table submission drop constraint submission_platform_platform_sub_id_key;
alter table submission add constraint uk_submission_account unique(account_id,platform,platform_sub_id);

create table binding_request (
 id bigserial primary key,
 user_id bigint not null references app_user(id) on delete cascade,
 platform varchar(32) not null,
 platform_uid varchar(128) not null,
 handle varchar(128) not null,
 account_id bigint references platform_account(id) on delete cascade,
 token varchar(80) not null,
 expires_at timestamptz not null,
 attempts int not null default 0,
 attempt_token varchar(36),
 attempt_until timestamptz,
 verified_at timestamptz,
 created_at timestamptz not null default now(),
 unique(user_id,platform,platform_uid)
);
