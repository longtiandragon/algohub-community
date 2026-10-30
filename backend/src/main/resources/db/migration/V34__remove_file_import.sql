-- 文件导入（自报导入）的剩余部分：V33 随 PTA 删掉了导入功能本身，这里删掉留下的底层结构。
-- 不再有 auth_mode = 'IMPORT' 的账号，也不再有导入批次；auth_mode 列没有 CHECK 约束，不用改约束。

-- 自报导入的账号：登录态、提交、通过记录、每日统计、快照、同步任务、导入批次等按账号级联删除，
-- 归属申诉里指向它的 owner_account_id 置空。
delete from platform_account where auth_mode = 'IMPORT';

-- 校内公开资格视图去掉「不是自报导入」这一条，其余条件与 V29 相同
create or replace view school_board_account as
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
   and coalesce(p.show_on_ranking, true)
   and coalesce(p.profile_visibility, 'SCHOOL') <> 'PRIVATE';

-- 提交上的导入批次列（连同外键）与导入批次表（连同 idx_import_batch_user）
alter table submission drop column import_batch_id;
drop table import_batch;
