create table password_reset_challenge (
 email varchar(254) primary key,
 user_id bigint not null references app_user(id) on delete cascade,
 code_hash varchar(64) not null,
 expires_at timestamptz not null,
 attempts integer not null default 0
);
