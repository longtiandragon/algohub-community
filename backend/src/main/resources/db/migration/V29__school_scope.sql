-- 多学校隔离：校内榜单、动态与"仅校内可见"一律按服务端记录的学校成员关系判断，不看前端传来的学校编号。

-- 每人至多一个已通过的学校。选校接口本来就不允许再加入第二所，这里在数据库层面固定下来。
create unique index uk_member_one_approved on school_member (user_id) where status = 'APPROVED';

-- 统一的公开资格：所有校内榜单、动态、完成榜与校内人数都只从这张视图取人，不再各写一套条件。
-- 账号：已验证归属、不是自报导入；用户：正常状态、在某校已通过；隐私：愿意参与校内展示且档案不是仅自己可见。
create view school_board_account as
select a.id              as account_id,
       a.user_id,
       a.platform,
       a.supports_history,
       a.history_complete,
       a.last_success_at,
       m.school_id,
       m.enrollment_year,
       m.class_name,
       u.display_name,
       u.avatar_url
  from platform_account a
  join app_user u on u.id = a.user_id and u.status = 'ACTIVE'
  join school_member m on m.user_id = a.user_id and m.status = 'APPROVED'
  left join user_privacy p on p.user_id = a.user_id
 where a.ownership_status = 'VERIFIED'
   and a.auth_mode <> 'IMPORT'
   and coalesce(p.show_on_ranking, true)
   and coalesce(p.profile_visibility, 'SCHOOL') <> 'PRIVATE';

-- 题单可见范围：PRIVATE 仅作者；SCHOOL 指定学校（school_id）；PUBLIC 全站登录用户。
alter table problem_list add column school_id bigint references school (id) on delete set null;
-- 已有系统题单原本对所有登录用户可见，保持不变
update problem_list set share_scope = 'PUBLIC' where owner_id is null;
-- 学生分享到校内的题单归到作者当前所在学校
update problem_list l
   set school_id = m.school_id
  from school_member m
 where m.user_id = l.owner_id and m.status = 'APPROVED' and l.share_scope = 'SCHOOL';
-- 作者没有学校的"校内共享"其实谁也看不到，转为仅自己可见，不留假共享
update problem_list set share_scope = 'PRIVATE' where share_scope = 'SCHOOL' and school_id is null;
alter table problem_list add constraint problem_list_scope
    check (share_scope in ('PRIVATE', 'SCHOOL', 'PUBLIC') and (share_scope <> 'SCHOOL' or school_id is not null));

-- 训练活动可见范围：SCHOOL 指定学校；PUBLIC 全站。活动榜始终只列查看者本校的同学。
alter table training_activity add column scope varchar(16) not null default 'SCHOOL';
alter table training_activity add column school_id bigint references school (id) on delete cascade;
-- 已有活动都是为首个学校（深职大）发布的
update training_activity set school_id = (select id from school order by id limit 1) where scope = 'SCHOOL';
alter table training_activity add constraint training_activity_scope
    check ((scope = 'PUBLIC' and school_id is null) or (scope = 'SCHOOL' and school_id is not null));
create index idx_training_activity_school on training_activity (school_id, start_at desc);
