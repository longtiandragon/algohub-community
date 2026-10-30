-- 比赛收藏：同学自己标记关注的场次，并按收藏导出 .ics 日历。
--
-- 只存「谁收藏了哪场」，比赛本身的时间、标题、链接一律实时读 contest 表：
-- 源站改期、改名或取消后，收藏列表与导出的日历跟着变，不会留下一份对不上的旧副本。
-- 比赛行被清理（结束 30 天后）时收藏随之级联删除。

create table contest_bookmark (
    user_id    bigint      not null references app_user (id) on delete cascade,
    contest_id bigint      not null references contest (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, contest_id)
);

create index idx_contest_bookmark_user on contest_bookmark (user_id, created_at desc);
