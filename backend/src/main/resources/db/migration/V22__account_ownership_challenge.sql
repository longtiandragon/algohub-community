create table account_ownership_challenge (
    account_id bigint primary key references platform_account(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    platform_uid varchar(128) not null,
    token varchar(80) not null,
    expires_at timestamptz not null,
    attempts int not null default 0,
    verified_at timestamptz
);
-- Earlier VERIFIED values were inferred from nonempty credentials, not proof of control.
update platform_account set ownership_status='RESOLVED'
where ownership_status='VERIFIED';
