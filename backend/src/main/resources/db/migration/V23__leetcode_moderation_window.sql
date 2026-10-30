-- Preserve previously issued public tokens while allowing profile moderation to finish.
update account_ownership_challenge c
set expires_at=c.expires_at + interval '47 hours 45 minutes'
from platform_account a
where a.id=c.account_id and a.platform='LEETCODE_CN' and c.verified_at is null;
