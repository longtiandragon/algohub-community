-- 停用蓝桥同步，清除托管登录态；保留账号及历史刷题记录。
delete from platform_credential c using platform_account a
where c.account_id = a.id and a.platform = 'LANQIAO';

update sync_task set status = 'PAUSED', lease_owner = null, lease_until = null,
    error_kind = 'UNSUPPORTED', error_msg = '蓝桥云课同步已停用', updated_at = now()
where platform = 'LANQIAO' and status in ('PENDING', 'RUNNING');

update platform_account set auth_mode = 'PUBLIC', sync_status = 'PAUSED'
where platform = 'LANQIAO';

-- 派生的每日指标不再计入近期榜；原始提交和通过记录仍保留。
delete from daily_stat where account_id in
    (select id from platform_account where platform = 'LANQIAO');

update nav_resource set enabled = false
where url like 'https://www.lanqiao.cn/%' or title = '蓝桥云课';

create or replace view school_board_account as
select a.id as account_id, a.user_id, a.platform, a.supports_history,
       a.history_complete, a.last_success_at, m.school_id, m.enrollment_year,
       m.class_name, u.display_name, u.avatar_url
from platform_account a
join app_user u on u.id = a.user_id and u.status = 'ACTIVE'
join school_member m on m.user_id = a.user_id and m.status = 'APPROVED'
left join user_privacy p on p.user_id = a.user_id
where a.ownership_status = 'VERIFIED' and a.platform <> 'LANQIAO'
  and coalesce(p.show_on_ranking, true)
  and coalesce(p.profile_visibility, 'SCHOOL') <> 'PRIVATE';
