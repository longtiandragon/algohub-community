-- Preserve every existing user's privacy, including legacy accounts without a settings row.
-- Block concurrent registrations until the legacy backfill and default change commit together.
lock table app_user in share row exclusive mode;

insert into user_privacy (user_id, profile_visibility)
select id, 'SCHOOL' from app_user
on conflict (user_id) do nothing;

-- Only future inserts use PUBLIC. Existing SCHOOL / PRIVATE / PUBLIC rows are untouched.
alter table user_privacy alter column profile_visibility set default 'PUBLIC';
