-- 2026-09-28 起不再分辨默认头像：之前因为是默认头像而存成空的账号，清掉检查时间，下次同步就重新取
update platform_account set avatar_checked_at = null where avatar_url is null;
