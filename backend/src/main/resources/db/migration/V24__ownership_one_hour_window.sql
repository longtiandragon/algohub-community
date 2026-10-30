-- V23/new LC challenges had a 48-hour window. Preserve issuance time and token;
-- reduce that window to one hour, without revoking already completed proofs.
update account_ownership_challenge c
set expires_at=c.expires_at - interval '47 hours'
from platform_account a
where a.id=c.account_id and a.platform='LEETCODE_CN' and c.verified_at is null;
