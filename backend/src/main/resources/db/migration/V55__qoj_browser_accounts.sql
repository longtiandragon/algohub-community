-- QOJ 通过用户浏览器同步，保留账号、归属状态和历史，不再托管登录态。
delete from platform_credential c using platform_account a
where c.account_id=a.id and a.platform='QOJ';

update sync_task set status='PAUSED', lease_owner=null, lease_until=null,
    error_kind='UNSUPPORTED', error_msg='QOJ 已改用浏览器插件同步', updated_at=now()
where platform='QOJ' and status in ('PENDING','RUNNING');

update platform_account set auth_mode='BROWSER', sync_status='IDLE',
    last_error_kind=null, last_error_msg=null,
    note='通过 QOJ 浏览器插件同步，无需 Cookie；未验证归属的记录仅用于个人统计。'
where platform='QOJ';
