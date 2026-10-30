-- 洛谷改了难度评级的名称：编号仍是 0～8，名称按官网配置（https://www.luogu.com.cn/_lfe/config 的 ProblemDifficulty）更新。
--
-- 旧名称（V19 写入）：普及/提高-、普及+/提高、提高+/省选-、省选/NOI-、NOI/NOI+/CTSC，只有 0～7 八档；
-- 现在是九档，多了「普及」「提高」，最高一档 8 是 NOI/NOI+/CTS，减号是全角的「−」。
-- 核对过：本地题目的原始编号与同学洛谷主页「难易度统计」逐档对得上（如 5 → 提高、6 → 提高+/省选−），
-- 说明原始编号本来就是新口径，只是展示名称用错了。
--
-- 只改展示用的 difficulty_label，原始编号 difficulty_raw 不动，不影响题目归属、通过记录与统计。

update problem set difficulty_label = case difficulty_raw
        when '0' then '暂无评定'
        when '1' then '入门'
        when '2' then '普及−'
        when '3' then '普及'
        when '4' then '普及+/提高−'
        when '5' then '提高'
        when '6' then '提高+/省选−'
        when '7' then '省选/NOI−'
        when '8' then 'NOI/NOI+/CTS'
    end
 where platform = 'LUOGU'
   and difficulty_raw in ('0', '1', '2', '3', '4', '5', '6', '7', '8');
