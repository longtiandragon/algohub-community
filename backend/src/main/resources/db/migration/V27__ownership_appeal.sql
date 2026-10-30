-- 归属申诉：平台账号已被另一位同学验证时，新的申请人必须先用公开资料证明自己当前能控制该账号，
-- 然后由管理员审核。审核通过只转移"已验证归属"这一个标记：
-- 原归属者的账号行、刷题记录与登录态原样保留（转为个人关注），申请人另起一行重新同步，
-- 不把任何一方的记录或 Cookie 搬给另一方。

create table ownership_appeal (
    id               bigserial    primary key,
    platform         varchar(32)  not null,
    platform_uid     varchar(128) not null,
    handle           varchar(128) not null,
    appellant_id     bigint       not null references app_user (id) on delete cascade,
    -- 生成申诉时占有归属的账号；审核时按实时状态重新查找，这里只作记录
    owner_account_id bigint       references platform_account (id) on delete set null,
    owner_user_id    bigint       references app_user (id) on delete set null,
    -- 生成这条申诉的绑定申请（验证串已在该申请上消费）
    request_id       bigint       references binding_request (id) on delete set null,
    -- 申请人完成公开资料验证的时刻：没有证明就不会生成申诉
    proved_at        timestamptz  not null,
    reason           varchar(500),
    -- PENDING 待审核；APPROVED 已转移归属；REJECTED 已驳回；WITHDRAWN 申请人撤回
    status           varchar(16)  not null default 'PENDING',
    reviewed_by      bigint       references app_user (id) on delete set null,
    reviewed_at      timestamptz,
    review_note      varchar(500),
    created_at       timestamptz  not null default now()
);
-- 同一申请人对同一平台账号同时只保留一条待审核申诉
create unique index uk_appeal_pending on ownership_appeal (appellant_id, platform, platform_uid)
    where status = 'PENDING';
create index idx_appeal_status on ownership_appeal (status, created_at desc);

-- 归属变化的说明（例如经申诉审核转为个人关注），只给账号本人看
alter table platform_account add column ownership_note varchar(255);

-- 旧的"已登记账号挑战"表：待完成的挑战已由 V26 并入 binding_request。
-- 已完成的挑战同样并入（作为已消费的申请保留验证记录），之后删除旧表，只留一套验证数据。
insert into binding_request (user_id, platform, platform_uid, handle, account_id, token, expires_at,
                             attempts, verified_at, created_at)
select c.user_id, a.platform, c.platform_uid, a.handle, a.id, c.token, c.expires_at,
       c.attempts, c.verified_at, coalesce(c.verified_at, c.expires_at)
  from account_ownership_challenge c
  join platform_account a on a.id = c.account_id
 where c.verified_at is not null
on conflict (user_id, platform, platform_uid) do nothing;

drop table account_ownership_challenge;
