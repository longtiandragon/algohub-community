package com.szpu.algohub.account;

/**
 * 牛客 Tracker 题与竞赛题库的对应规则。
 *
 * Tracker 的 problemId 与竞赛题库可能共用题号；只有"题号相同且标题一致（忽略大小写与空白）"才算同一题、合并去重；
 * 题号撞上了但标题不同，另记为 tracker-题号，不合并也不丢。
 */
final class NowcoderTrackerMapping {

    private NowcoderTrackerMapping() {
    }

    /** 每道 Tracker 通过题应记到哪个题目标识（problem_key）。 */
    static final String MAPPED = """
            with mapped as (
                select t.account_id, t.pid, t.title, t.url, t.difficulty, t.updated_at,
                       case when p.id is null or p.title is null
                                 or regexp_replace(lower(p.title), '\\s', '', 'g')
                                    = regexp_replace(lower(t.title), '\\s', '', 'g')
                            then t.pid else 'tracker-' || t.pid end as problem_key
                  from nowcoder_tracker_problem t
                  left join problem p on p.platform = 'NOWCODER' and p.problem_key = t.pid
            )
            """;
}
