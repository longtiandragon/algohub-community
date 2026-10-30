-- 头像：不做上传，从本人已验证的 OJ 账号里选一个，用那个平台的头像。
--
-- 事实来源只有一个：platform_account.avatar_url（账号同步时顺路刷新，没有或是平台默认头像时为空）
-- 加上 app_user.avatar_account_id（本人选的是哪个账号，空 = 用默认字标）。
-- app_user.avatar_url 保留给已有的读取方（校内动态、完成榜、当前用户等），但改成由数据库维护的副本：
-- 应用代码写进去的值会被触发器覆盖，永远等于所选账号当前的头像。

alter table platform_account add column avatar_url varchar(512);
-- 上次查公开资料取头像的时间：距今超过 24 小时，同步成功后才再查一次
alter table platform_account add column avatar_checked_at timestamptz;

-- 账号解绑（删除）时自动置空，头像随之变回默认字标
alter table app_user add column avatar_account_id bigint references platform_account (id) on delete set null;
create index idx_app_user_avatar_account on app_user (avatar_account_id) where avatar_account_id is not null;

-- 以前的头像是自己填的图片链接，改成只能从 OJ 选之后不再沿用
update app_user set avatar_url = null where avatar_url is not null;

-- app_user 每次写入都按所选账号重算 avatar_url；所选账号已经不属于本人、或不再是已验证归属（申诉转移等）时，
-- 连选择一起清掉。
create function app_user_sync_avatar() returns trigger as $$
declare
    v_url      varchar(512);
    v_owner    bigint;
    v_verified boolean;
begin
    if new.avatar_account_id is not null then
        select a.avatar_url, a.user_id, a.ownership_status = 'VERIFIED' into v_url, v_owner, v_verified
          from platform_account a where a.id = new.avatar_account_id;
        if v_owner is distinct from new.id or v_verified is not true then
            new.avatar_account_id := null;
            v_url := null;
        end if;
    end if;
    new.avatar_url := v_url;
    return new;
end
$$ language plpgsql;

create trigger trg_app_user_sync_avatar
    before insert or update on app_user
    for each row execute function app_user_sync_avatar();

-- 账号的头像变了、换了主人或归属状态变了：让选了它的用户重算一次
create function platform_account_avatar_changed() returns trigger as $$
begin
    update app_user set avatar_account_id = avatar_account_id where avatar_account_id = new.id;
    return null;
end
$$ language plpgsql;

create trigger trg_platform_account_avatar_changed
    after update of avatar_url, user_id, ownership_status on platform_account
    for each row
    when (old.avatar_url is distinct from new.avatar_url or old.user_id is distinct from new.user_id
          or old.ownership_status is distinct from new.ownership_status)
    execute function platform_account_avatar_changed();
