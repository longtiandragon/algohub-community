package com.szpu.algohub.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PlatformDtos {

    private PlatformDtos() {
    }

    /** 平台能力声明。不用单一“支持 / 不支持”表示全部功能。 */
    public record PlatformCapability(
            Platform platform,
            /** 能否用公开接口确认用户存在与稳定 UID。 */
            boolean publicIdentity,
            /** 能否取得带时间的逐条提交记录。 */
            boolean submissionHistory,
            /** 能否取得累计通过数等总量快照。 */
            boolean totalsSnapshot,
            /** 是否必须提供本人登录态。 */
            boolean requiresCredential,
            /** 难度来源：OFFICIAL / THIRD_PARTY / NONE。 */
            String difficultySource,
            /** 标签来源：OFFICIAL / NONE。 */
            String tagSource,
            /** 是否提供比赛列表。 */
            boolean contestList,
            /** 说明与已知限制。 */
            String notes) {
    }

    /** 由平台返回的本人身份，用于校验绑定关系，不只相信用户手填的名字。 */
    public record ResolvedIdentity(String platformUid, String handle, String displayName, Map<String, String> extra) {
    }

    /**
     * 公开资料归属证明：本人把验证串写进平台公开资料里只有本人能改的字段，服务端再读公开资料核对。
     * 登录态、截图或客户端自报的结果都不能代替它。
     */
    public record OwnershipProof(
            /** ResolvedIdentity.extra 中存放该字段内容的键名。 */
            String field,
            /** 告诉同学去哪里改：页面与字段名。 */
            String location,
            /** 额外说明，例如源站需要审核资料；没有则为空。 */
            String note,
            /** 验证串有效期（分钟）。重复获取不会延长。 */
            int validMinutes,
            /** 直接改这个字段的页面（如 AtCoder 的 https://atcoder.jp/settings）；没有就为空，前端退回平台首页。 */
            String settingsUrl) {

        public OwnershipProof(String field, String location, String note, int validMinutes) {
            this(field, location, note, validMinutes, null);
        }
    }

    public record FetchedProblem(
            String problemKey,
            String title,
            String url,
            String difficultyRaw,
            String difficultyLabel,
            String difficultySource,
            List<String> tags,
            boolean tagsComplete) {
    }

    public record FetchedSubmission(
            String platformSubId,
            FetchedProblem problem,
            String verdict,
            boolean accepted,
            Instant submittedAt,
            /** EXACT / DATE_ONLY：只有日期的来源不补造时间戳。 */
            String timePrecision,
            String language,
            String contestRef) {
    }

    /**
     * 题库条目：平台公开的题目清单，用于推荐与标签统计。
     * difficultyValue 只在原始难度确实是数字时给出，不做跨平台换算；
     * solvedCount 为平台公开的通过人数，没有就留空。
     */
    public record CatalogProblem(FetchedProblem problem, Integer difficultyValue, Integer solvedCount) {
    }

    /**
     * 一场比赛的参赛记录：名次与 Rating 变化。performance 只有 AtCoder 有；
     * 不计分的场次 rated 为 false（参加了，Rating 不变）。
     */
    public record RatedContest(String contestKey, String contestName, Integer place, Integer oldRating,
                               Integer newRating, Integer performance, boolean rated, Instant finishedAt) {
    }

    /** 一次抓取的结果切片。nextCursor 为 null 表示本轮已到末尾。 */
    public record SubmissionPage(List<FetchedSubmission> submissions, String nextCursor, boolean hasMore,
                                 boolean reachedHistoryStart) {
    }

    /** 只有总数的平台保存快照，用于计算观察增量。 */
    public record TotalsSnapshot(Integer solvedTotal, Integer submissionTotal,
                                 Map<String, Integer> difficultyBreakdown, String rawExtra,
                                 List<FetchedProblem> solvedProblems,
                                 Map<java.time.LocalDate, Integer> dailyActivity) {
        public TotalsSnapshot(Integer solvedTotal, Integer submissionTotal,
                              Map<String, Integer> difficultyBreakdown, String rawExtra,
                              List<FetchedProblem> solvedProblems) {
            this(solvedTotal, submissionTotal, difficultyBreakdown, rawExtra, solvedProblems, Map.of());
        }
        public TotalsSnapshot(Integer solvedTotal, Integer submissionTotal,
                              Map<String, Integer> difficultyBreakdown, String rawExtra) {
            this(solvedTotal, submissionTotal, difficultyBreakdown, rawExtra, List.of());
        }
    }

    public record FetchedContest(
            Platform platform,
            String contestKey,
            String title,
            Instant startAt,
            Instant endAt,
            Integer durationSec,
            String status,
            String category,
            String url,
            String source) {
    }

    /** 同步上下文：账号标识、游标与可选登录态。 */
    public record SyncContext(Long accountId, String handle, String platformUid, String cursor,
                              String credential, Instant recheckFrom) {
    }
}
