-- Preserve previously issued tokens and deadlines when upgrading the old account flow.
insert into binding_request(user_id,platform,platform_uid,handle,account_id,token,expires_at,attempts)
select c.user_id,a.platform,c.platform_uid,a.handle,a.id,c.token,c.expires_at,c.attempts
from account_ownership_challenge c join platform_account a on a.id=c.account_id
where c.verified_at is null and a.ownership_status<>'VERIFIED'
  and a.platform in ('CODEFORCES','LEETCODE_CN')
on conflict(user_id,platform,platform_uid) do nothing;
