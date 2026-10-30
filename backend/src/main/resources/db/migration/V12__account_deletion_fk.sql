-- 修复账号注销：此前多处外键没有删除规则（默认 NO ACTION），
-- 只要用户有绑定账号、通过记录、入校申请、导入批次，或者建过题单 / 审核过成员，
-- 删除 app_user 就会因外键约束失败——「注销账号」在真实数据下走不通。
--
-- 处理原则：
-- 属于本人的数据随账号一起删除（CASCADE）；
-- 只是记录“谁做的”的引用改为置空（SET NULL），保留对象本身，避免误删别人的数据。

-- 本人数据：随账号删除
alter table platform_account drop constraint platform_account_user_id_fkey;
alter table platform_account add constraint platform_account_user_id_fkey
    foreign key (user_id) references app_user (id) on delete cascade;

alter table user_solved_problem drop constraint user_solved_problem_user_id_fkey;
alter table user_solved_problem add constraint user_solved_problem_user_id_fkey
    foreign key (user_id) references app_user (id) on delete cascade;

alter table daily_stat drop constraint daily_stat_user_id_fkey;
alter table daily_stat add constraint daily_stat_user_id_fkey
    foreign key (user_id) references app_user (id) on delete cascade;

alter table import_batch drop constraint import_batch_user_id_fkey;
alter table import_batch add constraint import_batch_user_id_fkey
    foreign key (user_id) references app_user (id) on delete cascade;

alter table school_member drop constraint school_member_user_id_fkey;
alter table school_member add constraint school_member_user_id_fkey
    foreign key (user_id) references app_user (id) on delete cascade;

-- “谁做的”：置空即可，不能连带删除对象
alter table school_member drop constraint school_member_reviewed_by_fkey;
alter table school_member add constraint school_member_reviewed_by_fkey
    foreign key (reviewed_by) references app_user (id) on delete set null;

alter table problem_group drop constraint problem_group_created_by_fkey;
alter table problem_group add constraint problem_group_created_by_fkey
    foreign key (created_by) references app_user (id) on delete set null;

alter table problem_group_member drop constraint problem_group_member_created_by_fkey;
alter table problem_group_member add constraint problem_group_member_created_by_fkey
    foreign key (created_by) references app_user (id) on delete set null;

alter table problem_list drop constraint problem_list_created_by_fkey;
alter table problem_list add constraint problem_list_created_by_fkey
    foreign key (created_by) references app_user (id) on delete set null;
