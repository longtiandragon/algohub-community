-- Keep small page receipts until the existing ticket expires. Staged records are
-- still deleted on completion; replaying an acknowledged page never imports twice.
alter table luogu_browser_import add column page_hashes jsonb not null default '{}';
alter table qoj_browser_import add column page_hashes jsonb not null default '{}';
alter table leetcode_browser_import add column page_hashes jsonb not null default '{}';
