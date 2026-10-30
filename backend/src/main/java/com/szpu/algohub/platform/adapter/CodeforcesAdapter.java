package com.szpu.algohub.platform.adapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Avatars;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedContest;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * Codeforces 官方公开 API。提交、时间、结果、题目难度与标签齐全，作为第一批完整打通的平台。
 * 官方文档要求调用间隔不少于 2 秒，由 PlatformRateLimiter 统一控制。
 */
@Component
public class CodeforcesAdapter implements PlatformAdapter {

    private static final String API = "https://codeforces.com/api/";
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES_PER_RUN = 50;
    private static final Pattern USER_ID_IN_PHOTO = Pattern.compile("userpic\\.codeforces\\.org/(\\d+)/");

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodeforcesAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.CODEFORCES;
    }

    @Override
    public PlatformCapability capability() {
        // 没有公开的"通过题数"接口：题数就是完整提交历史里去重的通过题，不另取总量快照
        return new PlatformCapability(Platform.CODEFORCES, true, true, false, false,
                "OFFICIAL", "OFFICIAL", true,
                "官方 API 提供完整提交历史、原始 rating 与标签；handle 可变，绑定以 userpic 中的数字 ID 为准。");
    }

    @Override
    public boolean contestHistorySupported() {
        return true;
    }

    /** 官方 user.rating：每场计分比赛一条，含名次与前后 Rating。没参加过比赛时是空列表。 */
    @Override
    public List<RatedContest> fetchContestHistory(SyncContext context) {
        return parseRatingHistory(readJson(API + "user.rating?handle=" + enc(context.handle())));
    }

    static List<RatedContest> parseRatingHistory(JsonNode root) {
        JsonNode result = root.path("result");
        if (!result.isArray()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "Codeforces Rating 记录结构与预期不符");
        }
        List<RatedContest> contests = new ArrayList<>();
        for (JsonNode node : result) {
            boolean ok = node.path("contestId").canConvertToLong() && node.path("contestName").isTextual()
                    && node.path("ratingUpdateTimeSeconds").canConvertToLong()
                    && node.path("ratingUpdateTimeSeconds").asLong() > 0
                    && node.path("oldRating").canConvertToInt() && node.path("newRating").canConvertToInt();
            if (!ok) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "Codeforces Rating 记录缺少比赛、时间或分数字段，本批未入库");
            }
            contests.add(new RatedContest(node.path("contestId").asText(), node.path("contestName").asText(),
                    node.path("rank").canConvertToInt() ? node.path("rank").asInt() : null,
                    node.path("oldRating").asInt(), node.path("newRating").asInt(), null, true,
                    Instant.ofEpochSecond(node.path("ratingUpdateTimeSeconds").asLong())));
        }
        return contests;
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String handle = input.trim();
        if (handle.contains("/")) {
            handle = handle.substring(handle.lastIndexOf('/') + 1);
        }
        return parseIdentity(readJson(API + "user.info?handles=" + enc(handle)), handle);
    }

    /**
     * user.info 的第一条。数字 ID 取自头像地址；显示名用 handle——姓名是选填项，
     * 核验期间姓氏里还放着验证串，拿它当显示名不合适。lastName（姓氏，英文）用于归属验证。
     */
    static ResolvedIdentity parseIdentity(JsonNode root, String handle) {
        JsonNode result = root.path("result");
        if (!result.isArray() || result.isEmpty()) {
            throw new ApiException(ErrorKind.NOT_FOUND, "Codeforces 未找到该 handle");
        }
        JsonNode user = result.get(0);
        String realHandle = user.path("handle").asText(handle);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("rating", user.path("rating").asText("未评级"));
        extra.put("rank", user.path("rank").asText("未评级"));
        extra.put("organization", user.path("organization").asText(""));
        extra.put("lastName", user.path("lastName").asText(""));
        String avatar = avatar(user);
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(extractUserId(user), realHandle, realHandle, extra);
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 头像取 titlePhoto（个人页上的大图），没有再取 avatar。平台默认头像也照样给，选不选由同学自己定。 */
    static String avatar(JsonNode user) {
        for (String field : new String[]{"titlePhoto", "avatar"}) {
            String url = Avatars.https(user.path(field).asText(""));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /**
     * 用「姓氏（英文）」验证：官方 user.info 公开返回，只有登录本人能在设置里改；
     * 不占用常用来写学校的「组织」一栏。实测 1.2 万个填了姓氏的活跃用户里，有 330 个含数字、75 个含连字符，
     * 验证串（AlgoHub- 加 16 位小写字母与数字）可以原样填进去。
     */
    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("lastName", "Codeforces → Settings → Social → 姓氏（英文）（Last name, in English）",
                "只用于核验，通过后可以清空或改回；「组织」一栏不用动。", 15,
                "https://codeforces.com/settings/social");
    }

    private static String extractUserId(JsonNode user) {
        for (String field : new String[]{"titlePhoto", "avatar"}) {
            Matcher m = USER_ID_IN_PHOTO.matcher(user.path(field).asText(""));
            if (m.find()) {
                return m.group(1);
            }
        }
        // 拿不到数字 ID 时退回 handle，并在同步时记录标识变更。
        return user.path("handle").asText();
    }

    @Override
    public SubmissionPage fetchSubmissions(SyncContext context) {
        // CF 返回按提交时间倒序。增量同步读到已知最大提交 ID 或回查窗口之前即停止。
        long knownMaxId = parseLong(context.cursor(), 0L);
        Instant recheckFrom = context.recheckFrom();
        List<FetchedSubmission> collected = new ArrayList<>();
        long newMaxId = knownMaxId;
        boolean reachedStart = false;
        boolean truncated = false;

        int from = 1;
        for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
            JsonNode root = readJson(API + "user.status?handle=" + enc(context.handle())
                    + "&from=" + from + "&count=" + PAGE_SIZE);
            JsonNode result = root.path("result");
            if (!result.isArray() || result.isEmpty()) {
                reachedStart = true;
                break;
            }
            boolean stop = false;
            for (JsonNode node : result) {
                requireSubmissionFields(node);
                long id = node.path("id").asLong();
                Instant createdAt = Instant.ofEpochSecond(node.path("creationTimeSeconds").asLong());
                newMaxId = Math.max(newMaxId, id);
                if (id <= knownMaxId && (recheckFrom == null || createdAt.isBefore(recheckFrom))) {
                    // 已入库且在回查窗口之外
                    stop = true;
                    break;
                }
                collected.add(toSubmission(node, id, createdAt));
            }
            if (stop) {
                break;
            }
            if (result.size() < PAGE_SIZE) {
                reachedStart = true;
                break;
            }
            from += PAGE_SIZE;
            if (page == MAX_PAGES_PER_RUN - 1) {
                truncated = true;
            }
        }
        // 本轮被截断时不推进游标，下次继续从最新页重新读取，由提交 ID 去重。
        String nextCursor = truncated ? context.cursor() : String.valueOf(newMaxId);
        return new SubmissionPage(collected, nextCursor, truncated, reachedStart && !truncated);
    }

    /** 编号、时间与题目缺一不可：缺了会被存成编号 0、1970 年的记录，宁可整批不收。 */
    static void requireSubmissionFields(JsonNode node) {
        boolean ok = node.path("id").canConvertToLong() && node.path("id").asLong() > 0
                && node.path("creationTimeSeconds").canConvertToLong() && node.path("creationTimeSeconds").asLong() > 0
                && node.path("problem").isObject() && node.path("problem").path("index").isTextual();
        if (!ok) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "Codeforces 提交记录缺少编号、时间或题目字段，本批未入库");
        }
    }

    private FetchedSubmission toSubmission(JsonNode node, long id, Instant createdAt) {
        JsonNode p = node.path("problem");
        String contestId = p.path("contestId").asText("");
        String index = p.path("index").asText("");
        String key = contestId.isEmpty() ? p.path("problemsetName").asText("acmsguru") + "-" + index
                : contestId + index;
        String url = contestId.isEmpty()
                ? "https://codeforces.com/problemsets/acmsguru/problem/99999/" + index
                : "https://codeforces.com/contest/" + contestId + "/problem/" + index;

        List<String> tags = new ArrayList<>();
        p.path("tags").forEach(t -> tags.add(t.asText()));
        String rating = p.path("rating").isIntegralNumber() && p.path("rating").canConvertToInt()
                ? String.valueOf(p.path("rating").asInt()) : null;

        FetchedProblem problem = new FetchedProblem(key, p.path("name").asText(key), url,
                rating, rating == null ? null : "CF " + rating, "OFFICIAL", tags, true);

        String verdict = node.path("verdict").asText("UNKNOWN");
        String contestRef = contestId.isEmpty() ? null : contestId;
        return new FetchedSubmission(String.valueOf(id), problem, verdict, "OK".equals(verdict),
                createdAt, "EXACT", node.path("programmingLanguage").asText(null), contestRef);
    }

    /**
     * 官方全量题库。problemStatistics 给出各题通过人数，用于展示热度；
     * 没有 rating 的题保持未评级，不猜测难度。
     */
    @Override
    public List<com.szpu.algohub.platform.PlatformDtos.CatalogProblem> fetchProblemCatalog() {
        JsonNode root = readJson(API + "problemset.problems");
        JsonNode problems = root.path("result").path("problems");
        JsonNode statistics = root.path("result").path("problemStatistics");
        if (!problems.isArray() || problems.isEmpty() || !statistics.isArray()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "Codeforces 题库为空或字段变化");
        }

        java.util.Map<String, Integer> solvedCounts = new java.util.HashMap<>();
        for (JsonNode stat : statistics) {
            solvedCounts.put(stat.path("contestId").asText("") + stat.path("index").asText(""),
                    stat.path("solvedCount").asInt(0));
        }

        List<com.szpu.algohub.platform.PlatformDtos.CatalogProblem> catalog = new ArrayList<>();
        for (JsonNode node : problems) {
            String contestId = node.path("contestId").asText("");
            String index = node.path("index").asText("");
            if (contestId.isEmpty() || index.isEmpty()) {
                continue;
            }
            String key = contestId + index;
            List<String> tags = new ArrayList<>();
            node.path("tags").forEach(t -> tags.add(t.asText()));
            Integer rating = node.path("rating").isIntegralNumber() && node.path("rating").canConvertToInt()
                    ? node.path("rating").asInt() : null;
            FetchedProblem problem = new FetchedProblem(key, node.path("name").asText(key),
                    "https://codeforces.com/contest/" + contestId + "/problem/" + index,
                    rating == null ? null : String.valueOf(rating),
                    rating == null ? null : "CF " + rating,
                    "OFFICIAL", tags, true);
            catalog.add(new com.szpu.algohub.platform.PlatformDtos.CatalogProblem(
                    problem, rating, solvedCounts.get(key)));
        }
        return catalog;
    }

    @Override
    public boolean fetchProblemCatalogSupported() {
        return true;
    }

    @Override
    public List<FetchedContest> fetchContests() {
        JsonNode root = readJson(API + "contest.list?gym=false");
        List<FetchedContest> contests = new ArrayList<>();
        Instant keepAfter = Instant.now().minus(java.time.Duration.ofDays(7));
        for (JsonNode node : root.path("result")) {
            String phase = node.path("phase").asText("");
            long startSec = node.path("startTimeSeconds").asLong(0);
            if (startSec == 0) {
                continue;
            }
            // 比赛日历只保留近期与未来场次，历史比赛不进入日历表。
            if (Instant.ofEpochSecond(startSec).isBefore(keepAfter)) {
                continue;
            }
            int duration = node.path("durationSeconds").asInt(0);
            Instant start = Instant.ofEpochSecond(startSec);
            String name = node.path("name").asText("");
            String id = node.path("id").asText();
            contests.add(new FetchedContest(Platform.CODEFORCES, id, name, start,
                    start.plusSeconds(duration), duration, mapStatus(phase),
                    // Contest.type 表示计分制，不是 Div，分类由名称规则判断。
                    ContestCategoryRules.classifyCodeforces(name),
                    "https://codeforces.com/contest/" + id, "CF_API"));
        }
        return contests;
    }

    private String mapStatus(String phase) {
        return switch (phase) {
            case "BEFORE" -> "SCHEDULED";
            case "CODING", "PENDING_SYSTEM_TEST", "SYSTEM_TEST" -> "RUNNING";
            case "FINISHED" -> "FINISHED";
            default -> "SCHEDULED";
        };
    }

    private JsonNode readJson(String url) {
        String body = http.get(Platform.CODEFORCES, url);
        try {
            JsonNode root = mapper.readTree(body);
            if (!"OK".equals(root.path("status").asText())) {
                String comment = root.path("comment").asText("Codeforces 返回失败");
                if (comment.contains("not found") || comment.contains("No such user")) {
                    throw new ApiException(ErrorKind.NOT_FOUND, comment);
                }
                if (comment.contains("limit")) {
                    throw new ApiException(ErrorKind.RATE_LIMITED, comment);
                }
                throw new ApiException(ErrorKind.UPSTREAM_ERROR, comment);
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ProtectionPage.orElse(Platform.CODEFORCES, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "Codeforces 响应解析失败: " + e.getMessage()));
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
