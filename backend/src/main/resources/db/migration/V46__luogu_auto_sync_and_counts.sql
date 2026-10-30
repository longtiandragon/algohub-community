-- 只有公开已通过题单决定洛谷题数；浏览器记录只给其中的题目补时间。
create table luogu_extra_solve (
    account_id bigint not null references platform_account(id) on delete cascade,
    problem_key varchar(40) not null,
    title varchar(200),
    difficulty int,
    ac_at timestamptz,
    primary key(account_id,problem_key)
);

insert into luogu_extra_solve(account_id,problem_key,title,difficulty,ac_at)
select s.account_id,p.problem_key,p.title,
       case when p.difficulty_raw ~ '^[0-8]$' then p.difficulty_raw::int end,
       s.earliest_known_ac_at
from user_solved_problem s join problem p on p.id=s.problem_id
where s.platform = 'LUOGU' and s.source = 'BROWSER_SYNC';

delete from user_solved_problem s
where s.platform = 'LUOGU' and s.source = 'BROWSER_SYNC'
  and not exists (
    select 1 from user_solved_problem public_solve
    where public_solve.account_id = s.account_id
      and public_solve.problem_id = s.problem_id
      and public_solve.source = 'SYNC'
  );

delete from luogu_extra_solve e
where exists (
    select 1 from user_solved_problem s join problem p on p.id=s.problem_id
    where s.account_id=e.account_id and p.problem_key=e.problem_key and s.source='SYNC'
);

-- Tracker 是牛客练习题的采集来源，不是评级名称。
update problem
set difficulty_label = substring(difficulty_label from 9)
where platform = 'NOWCODER' and difficulty_label like 'Tracker %';

create table luogu_auto_sync_grant (
    account_id bigint primary key references platform_account(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    last_used_at timestamptz,
    created_at timestamptz not null default now()
);
