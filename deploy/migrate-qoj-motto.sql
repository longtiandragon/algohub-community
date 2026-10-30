\set ON_ERROR_STOP on
-- Requires psql -v cutoff=<predeployment UTC timestamp>, a verified backup, and
-- a working deployed Motto flow. No passwords or tokens belong in this file.
begin;
select pg_advisory_xact_lock(hashtext('algohub-qoj-motto-v1'));
create temporary table qoj_migration_target on commit drop as
 select id from platform_account where platform='QOJ' and created_at < :'cutoff'::timestamptz;
select a.id from platform_account a join qoj_migration_target t on t.id=a.id order by a.id for update of a;
insert into qoj_legacy_archive(account_id,user_id,account_data,related_data,migration_key)
select a.id,a.user_id,to_jsonb(a),'{}','qoj-motto-v1'
from platform_account a join qoj_migration_target t on t.id=a.id
on conflict(account_id) do nothing;
-- Archive all directly dependent account rows before the active binding is removed.
do $$
declare dependency record;
begin
 for dependency in
  select distinct c.conrelid::regclass as relation, att.attname as column_name
  from pg_constraint c
  join pg_attribute att on att.attrelid=c.conrelid and att.attnum=c.conkey[1]
  where c.contype='f' and c.confrelid='platform_account'::regclass and array_length(c.conkey,1)=1
    and c.conrelid <> 'app_user'::regclass
 loop
  execute format('update qoj_legacy_archive a set related_data=related_data || jsonb_build_object(%L, coalesce((select jsonb_agg(to_jsonb(r)) from %s r where r.%I=a.account_id),''[]''::jsonb)) where a.account_id in (select id from qoj_migration_target)', dependency.relation::text,dependency.relation,dependency.column_name);
 end loop;
end $$;
-- Account row locks serialize this removal with plugin writes and task result saves.
delete from platform_account where id in (select id from qoj_migration_target);
insert into study_notification(user_id,kind,dedup_key,title,body,category,template_code)
select distinct user_id,'SYSTEM','qoj-motto-v1',
 'QOJ 绑定验证升级，请重新绑定',
 'QOJ 现已支持 Motto 归属验证。旧版绑定已解除，请前往“平台账号”填写用户名并完成 Motto 验证，然后重新连接 QOJ 插件同步本人可见的提交记录。服务器更新主页累计通过数，验证后的记录按平台规则参与统计与排行。无需提供个人 Cookie 或密码。',
 'SYSTEM','QOJ_MOTTO_UPGRADE'
from qoj_legacy_archive where migration_key='qoj-motto-v1'
on conflict(user_id,dedup_key) do nothing;
select count(*) as archived_accounts,count(distinct user_id) as affected_users from qoj_legacy_archive where migration_key='qoj-motto-v1';
select count(*) as system_notifications from study_notification where dedup_key='qoj-motto-v1';
commit;
