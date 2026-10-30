-- 同步任务的执行编号与续租。
--
-- 每次领取任务都换一个新的执行编号（run_token）。执行中定期续租；保存任务状态、游标与刷题数据时，
-- 先在同一个短事务里锁住任务行并核对执行编号——租约过期被别的执行接管后编号已变，
-- 旧执行的结果整批放弃，不能回退游标、重复计数或覆盖新执行的结果。
alter table sync_task add column run_token varchar(36);
alter table sync_task add column heartbeat_at timestamptz;

-- 平台级冷却：某平台返回限流后，同平台的所有账号一起退避，而不是每个账号各撞一次。
create table platform_throttle (
    platform      varchar(32)  primary key,
    blocked_until timestamptz  not null,
    reason        varchar(255),
    updated_at    timestamptz  not null default now()
);

-- 领取时按平台统计正在执行的任务
create index idx_task_running_platform on sync_task (platform) where status = 'RUNNING';
