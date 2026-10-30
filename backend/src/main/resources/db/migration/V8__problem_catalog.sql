-- 题库同步：把平台的公开题目清单（含难度与标签）入库，让推荐与标签统计不再只覆盖“我做过的题”。
-- difficulty_raw 是各平台原样保留的字符串（可能是 1800、也可能是“普及-”），
-- 这里额外存一个数值列，只在原始难度确实是数字时填充，用于按难度检索，不做跨平台换算。

alter table problem add column difficulty_value int;
-- 平台公开的通过人数，用于“多少人做过”这类热度展示；平台不提供时为空
alter table problem add column solved_count int;
alter table problem add column catalog_synced_at timestamptz;

update problem
   set difficulty_value = nullif(regexp_replace(difficulty_raw, '\D', '', 'g'), '')::int
 where difficulty_raw ~ '^\d+$';

create index idx_problem_difficulty on problem (platform, difficulty_value)
    where difficulty_value is not null;
create index idx_problem_solved_count on problem (platform, solved_count desc nulls last);
