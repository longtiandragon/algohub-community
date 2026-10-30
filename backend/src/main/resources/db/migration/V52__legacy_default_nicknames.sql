alter table app_user add column nickname_needs_review boolean not null default false;

-- Only old email-created default names; never match a nickname merely containing this phrase.
update app_user
set display_name = '新同学_' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8)),
    nickname_needs_review = true, updated_at = now()
where role = 'STUDENT' and username like 'mail\_%' escape '\' and display_name = '新同学';
