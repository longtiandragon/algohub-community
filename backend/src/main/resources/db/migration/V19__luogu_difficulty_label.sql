-- 洛谷难度标签改成官网的评级名。
--
-- 之前存的是「洛谷难度 2」，同学在洛谷上看到的是「普及-」。
-- 原始编号仍然保存在 difficulty_raw 里，这里只改展示用的标签，
-- 不改变任何题目归属、通过记录或统计口径。
--
-- 只改动能对上 0～7 的行；对不上的（理论上不该有）保持原样，不猜。

update problem set difficulty_label = case difficulty_raw
        when '0' then '暂无评定'
        when '1' then '入门'
        when '2' then '普及-'
        when '3' then '普及/提高-'
        when '4' then '普及+/提高'
        when '5' then '提高+/省选-'
        when '6' then '省选/NOI-'
        when '7' then 'NOI/NOI+/CTSC'
    end
 where platform = 'LUOGU'
   and difficulty_raw in ('0', '1', '2', '3', '4', '5', '6', '7')
   and difficulty_label is distinct from case difficulty_raw
        when '0' then '暂无评定'
        when '1' then '入门'
        when '2' then '普及-'
        when '3' then '普及/提高-'
        when '4' then '普及+/提高'
        when '5' then '提高+/省选-'
        when '6' then '省选/NOI-'
        when '7' then 'NOI/NOI+/CTSC'
    end;
