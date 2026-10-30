create table user_follow (
    follower_id bigint not null references app_user(id) on delete cascade,
    following_id bigint not null references app_user(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key(follower_id,following_id),
    check(follower_id <> following_id)
);
create index user_follow_followers on user_follow(following_id,follower_id);
alter table study_notification add column category varchar(16) not null default 'SYSTEM'
    check(category in ('SYSTEM','FRIEND'));
alter table study_notification add column template_code varchar(32);
create index study_notification_category on study_notification(user_id,category,id desc);
create index study_notification_friend_sender on study_notification(created_by,created_at)
    where category='FRIEND';
