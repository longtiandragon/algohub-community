-- Shared invitation codes establish no academic identity. Preserve profiles/history,
-- but send automatically approved non-admin memberships back for human review.
update school_member m set status='PENDING', reviewed_at=null
from app_user u where u.id=m.user_id and u.role<>'ADMIN'
and m.status='APPROVED' and m.reviewed_by is null;
