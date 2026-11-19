alter table weekly_training
 add column audience text not null default 'ALL' check (audience in ('ALL','SELECTED')),
 add column student_ids bigint[] not null default '{}',
 add column status text not null default 'PUBLISHED' check (status in ('DRAFT','PUBLISHED','SCHEDULED')),
 add column publish_at timestamptz,
 add check ((status='SCHEDULED')=(publish_at is not null));
create index weekly_school_week on weekly_training(school_id,week_start);
create table weekly_defaults (
 school_id bigint not null references school(id), platform text not null, category text not null,
 targets jsonb not null, audience text not null check (audience in ('ALL','SELECTED')),
 primary key(school_id,platform,category)
);
insert into weekly_defaults(school_id,platform,category,targets,audience)
 select distinct on (school_id,platform,metadata->>'category') school_id,platform,metadata->>'category',targets,'ALL'
 from weekly_training order by school_id,platform,metadata->>'category',id desc;
create view weekly_effective_training as
 select t.*,case when status='SCHEDULED' and publish_at<=now() then 'PUBLISHED' else status end as effective_status
 from weekly_training t;
