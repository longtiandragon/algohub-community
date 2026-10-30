alter table app_user add column enrollment_year integer;
alter table app_user add column class_name varchar(64);
alter table app_user add column student_no varchar(32);
alter table app_user add column onboarding_completed boolean not null default false;
-- Existing accounts retain access; prefer approved membership, then pending membership.
update app_user u set onboarding_completed=true;
update app_user u set enrollment_year=m.enrollment_year, class_name=m.class_name, student_no=m.student_no
from (select distinct on (user_id) user_id,enrollment_year,class_name,student_no
      from school_member where status in ('APPROVED','PENDING')
      order by user_id, case when status='APPROVED' then 0 else 1 end, id) m
where u.id=m.user_id;
