-- Public participation is independent of school-only participation.
alter table user_privacy add column public_activity boolean not null default true;

create view activity_board_account as
select account_id, user_id, school_id, enrollment_year from school_board_account
union all
select a.id, a.user_id, null::bigint as school_id,
       coalesce(m.enrollment_year,u.enrollment_year) as enrollment_year
from platform_account a
join app_user u on u.id=a.user_id and u.status='ACTIVE' and u.role='STUDENT'
left join user_privacy p on p.user_id=u.id
left join lateral (
    select enrollment_year from school_member where user_id=u.id and status='APPROVED' order by id limit 1
) m on true
where a.ownership_status='VERIFIED' and a.platform <> 'LANQIAO'
  and coalesce(p.show_on_ranking,true)
  and coalesce(p.profile_visibility,'SCHOOL') <> 'PRIVATE'
  and coalesce(p.public_activity,true);
