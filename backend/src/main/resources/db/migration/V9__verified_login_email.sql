-- 与可编辑的资料邮箱分离，旧邮箱不自动视为已验证。
create table verified_login_email (
    user_id bigint primary key references app_user(id) on delete cascade,
    email varchar(254) not null unique,
    verified_at timestamptz not null default now()
);
create table email_verification_challenge (
    user_id bigint primary key references app_user(id) on delete cascade,
    email varchar(254) not null,
    code_hash varchar(64) not null,
    expires_at timestamptz not null,
    sent_at timestamptz not null default now(),
    attempts int not null default 0
);
create table email_login_challenge (
    email varchar(254) primary key,
    code_hash varchar(64) not null,
    expires_at timestamptz not null,
    sent_at timestamptz not null default now(),
    attempts int not null default 0
);
