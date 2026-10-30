create table nowcoder_tracker_progress (
 account_id bigint primary key references platform_account(id) on delete cascade,
 next_page integer not null default 1,
 completed_pages integer not null default 0,
 total_contests integer not null default 0,
 complete boolean not null default false,
 next_run_at timestamptz not null default now(),
 last_success_at timestamptz,
 error_message varchar(256)
);
create table nowcoder_tracker_problem (
 account_id bigint not null references platform_account(id) on delete cascade,
 pid varchar(64) not null,
 title text not null,
 url text not null,
 difficulty varchar(64),
 updated_at timestamptz not null default now(),
 primary key(account_id,pid)
);
