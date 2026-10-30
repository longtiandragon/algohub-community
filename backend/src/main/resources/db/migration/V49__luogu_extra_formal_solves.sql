-- Promote archived extra AC problems without overwriting official metadata or inventing dates.
insert into problem(platform,problem_key,title,url,difficulty_raw,difficulty_label,difficulty_source)
select distinct on (problem_key) 'LUOGU',problem_key,title,
       'https://www.luogu.com.cn/problem/' || problem_key,difficulty::text,
       case difficulty when 0 then '暂无评定' when 1 then '入门' when 2 then '普及−'
            when 3 then '普及' when 4 then '普及+/提高−' when 5 then '提高'
            when 6 then '提高+/省选−' when 7 then '省选/NOI−' when 8 then 'NOI/NOI+/CTS' end,
       'USER_REPORTED'
from luogu_extra_solve order by problem_key,ac_at nulls last,account_id
on conflict(platform,problem_key) do nothing;

insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence,source)
select a.user_id,e.account_id,p.id,'LUOGU',e.ac_at,
       case when e.ac_at is null then 'UNKNOWN' else 'USER_REPORTED' end,'BROWSER_SYNC'
from luogu_extra_solve e join platform_account a on a.id=e.account_id and a.platform='LUOGU'
join problem p on p.platform='LUOGU' and p.problem_key=e.problem_key
on conflict(account_id,problem_id) do update
set earliest_known_ac_at=case
      when user_solved_problem.earliest_known_ac_at is null then excluded.earliest_known_ac_at
      when user_solved_problem.first_ac_confidence='USER_REPORTED'
        then least(user_solved_problem.earliest_known_ac_at,excluded.earliest_known_ac_at)
      else user_solved_problem.earliest_known_ac_at end,
    first_ac_confidence=case
      when user_solved_problem.earliest_known_ac_at is null and excluded.earliest_known_ac_at is not null
        then 'USER_REPORTED' else user_solved_problem.first_ac_confidence end;

insert into daily_stat(user_id,account_id,platform,stat_date,metric,value,source)
select user_id,account_id,platform,(earliest_known_ac_at at time zone 'Asia/Shanghai')::date,
       'FIRST_AC',count(*),'SUBMISSION'
from user_solved_problem where platform='LUOGU' and earliest_known_ac_at is not null
  and first_ac_confidence in ('EXACT','USER_REPORTED')
group by user_id,account_id,platform,4
on conflict(account_id,stat_date,metric) do update set value=excluded.value,updated_at=now();
