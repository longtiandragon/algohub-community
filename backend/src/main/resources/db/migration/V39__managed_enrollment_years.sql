alter table study_goal_default add column enabled boolean not null default true;
insert into study_goal_default(enrollment_year,monthly_target,enabled) values(2023,0,true);
update study_goal_default set enabled=(enrollment_year in (2023,2024,2025,2026));
