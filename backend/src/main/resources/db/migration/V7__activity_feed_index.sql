-- 刷题动态与历史导出都按“已知最早通过时间”倒序翻页，这里补上支撑索引。
-- 动态列表本身不新建表：它就是 user_solved_problem 的一个视角，
-- 这样重算统计、修正记录时动态会跟着变，不会出现两份互相矛盾的数据。

create index if not exists idx_solved_feed
    on user_solved_problem (earliest_known_ac_at desc, id desc)
    where earliest_known_ac_at is not null;

create index if not exists idx_solved_user_feed
    on user_solved_problem (user_id, earliest_known_ac_at desc)
    where earliest_known_ac_at is not null;
