package com.szpu.algohub.platform.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * AtCoder：提交数据来自第三方 AtCoder Problems，难度为第三方估计值，界面需明确标注来源。
 * 官方基础数据没有完整算法标签，因此标签来源为 NONE。
 */
@Component
public class AtCoderAdapter implements PlatformAdapter {

    private static final String API = "https://kenkoooo.com/atcoder/atcoder-api/";
    private static final String RES = "https://kenkoooo.com/atcoder/resources/";
    private static final int PAGE_LIMIT = 500;
    private static final int MAX_PAGES_PER_RUN = 20;
    private static final Duration METADATA_TTL = Duration.ofHours(1);

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<MetadataCache> metadata = new AtomicReference<>();

    public AtCoderAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    private record ProblemMeta(String title, String contestId, Double difficulty) {
    }

    private record MetadataCache(Instant loadedAt, Map<String, ProblemMeta> problems) {
    }

    @Override
    public Platform platform() {
        return Platform.ATCODER;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.ATCODER, true, true, true, false,
                "THIRD_PARTY", "NONE", true,
                "提交与难度来自 AtCoder Problems（第三方）。历史覆盖范围以该服务为准，难度为估计值，无官方算法标签。");
    }

    @Override
    public boolean contestHistorySupported() {
        return true;
    }

    /** AtCoder 官方的比赛历史（个人页 → Competition History 的数据），含不计分的场次。 */
    @Override
    public List<RatedContest> fetchContestHistory(SyncContext context) {
        return parseContestHistory(http.get(Platform.ATCODER,
                "https://atcoder.jp/users/" + enc(context.handle()) + "/history/json"));
    }

    static List<RatedContest> parseContestHistory(String body) {
        JsonNode list;
        try {
            list = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw ProtectionPage.orElse(Platform.ATCODER, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "AtCoder 比赛记录不是 JSON"));
        }
        if (list == null || !list.isArray()) {
            throw ProtectionPage.orElse(Platform.ATCODER, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "AtCoder 比赛记录结构与预期不符"));
        }
        List<RatedContest> contests = new ArrayList<>();
        for (JsonNode node : list) {
            String screenName = node.path("ContestScreenName").asText("");
            Instant endTime;
            try {
                endTime = java.time.OffsetDateTime.parse(node.path("EndTime").asText()).toInstant();
            } catch (RuntimeException e) {
                endTime = null;
            }
            boolean ok = !screenName.isBlank() && node.path("ContestName").isTextual() && endTime != null
                    && node.path("IsRated").isBoolean()
                    && node.path("OldRating").canConvertToInt() && node.path("NewRating").canConvertToInt();
            if (!ok) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "AtCoder 比赛记录缺少比赛、时间或分数字段，本批未入库");
            }
            boolean rated = node.path("IsRated").asBoolean();
            String key = screenName.endsWith(".contest.atcoder.jp")
                    ? screenName.substring(0, screenName.length() - ".contest.atcoder.jp".length())
                    : screenName;
            // 不计分的场次表现分是 0，不代表真的是 0 分
            Integer performance = rated && node.path("Performance").canConvertToInt()
                    ? node.path("Performance").asInt() : null;
            contests.add(new RatedContest(key, node.path("ContestName").asText(),
                    node.path("Place").canConvertToInt() ? node.path("Place").asInt() : null,
                    node.path("OldRating").asInt(), node.path("NewRating").asInt(), performance, rated, endTime));
        }
        return contests;
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String handle = input.trim();
        if (handle.contains("/")) {
            handle = handle.substring(handle.lastIndexOf('/') + 1);
        }
        // 先用官方用户页确认账号存在（顺带读公开的 Affiliation，归属验证用），再用第三方接口取统计。
        String page = http.get(Platform.ATCODER, "https://atcoder.jp/users/" + enc(handle));
        JsonNode rank = readJson(API + "v3/user/ac_rank?user=" + enc(handle));
        Map<String, String> extra = new java.util.LinkedHashMap<>();
        extra.put("acCount", rank.path("count").asText("0"));
        extra.put("acRank", rank.path("rank").asText(""));
        extra.put("affiliation", affiliation(page));
        String avatar = avatar(page);
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(handle, handle, handle, Map.copyOf(extra));
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 官方用户页资料表上方的头像（页面里第一个 img.avatar）。平台默认头像也照样给，选不选由同学自己定。 */
    static String avatar(String userPage) {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(userPage == null ? "" : userPage);
        org.jsoup.nodes.Element img = doc.selectFirst("img.avatar");
        return img == null ? null : Avatars.https(img.attr("src"));
    }

    /** 官方用户页资料表里的 Affiliation（机构）。本人在设置里填写，公开显示；没填时整行不出现，返回空串。 */
    static String affiliation(String userPage) {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(userPage == null ? "" : userPage);
        for (org.jsoup.nodes.Element row : doc.select("table.dl-table tr")) {
            org.jsoup.nodes.Element th = row.selectFirst("th");
            org.jsoup.nodes.Element td = row.selectFirst("td");
            if (th != null && td != null && "Affiliation".equals(th.text().trim())) {
                return td.text().trim();
            }
        }
        return "";
    }

    /** 公开资料里的 Affiliation 只有登录本人能改。 */
    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("affiliation", "AtCoder → 右上角用户名 → General Settings → Affiliation（机构）",
                "保存后打开自己的 AtCoder 个人页，能看到 Affiliation 一行再点核验。", 30, "https://atcoder.jp/settings");
    }

    @Override
    public SubmissionPage fetchSubmissions(SyncContext context) {
        long fromSecond = parseLong(context.cursor(), 0L);
        if (context.recheckFrom() != null) {
            // 回查窗口：重新读取近几天，处理迟到与重判数据。
            fromSecond = Math.min(fromSecond, context.recheckFrom().getEpochSecond());
        }
        List<FetchedSubmission> collected = new ArrayList<>();
        long maxSecond = fromSecond;
        boolean truncated = false;
        boolean reachedEnd = false;

        for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
            JsonNode arr = readJson(API + "v3/user/submissions?user=" + enc(context.handle())
                    + "&from_second=" + maxSecond);
            if (!arr.isArray() || arr.isEmpty()) {
                reachedEnd = true;
                break;
            }
            long pageMax = maxSecond;
            for (JsonNode node : arr) {
                requireSubmissionFields(node);
                collected.add(toSubmission(node));
                pageMax = Math.max(pageMax, node.path("epoch_second").asLong());
            }
            if (arr.size() < PAGE_LIMIT) {
                maxSecond = pageMax;
                reachedEnd = true;
                break;
            }
            if (pageMax == maxSecond) {
                // 同一秒内提交超过一页，前移一秒避免死循环；重复提交由提交 ID 去重。
                maxSecond = pageMax + 1;
            } else {
                maxSecond = pageMax;
            }
            if (page == MAX_PAGES_PER_RUN - 1) {
                truncated = true;
            }
        }
        return new SubmissionPage(collected, String.valueOf(maxSecond), truncated, reachedEnd);
    }

    /** 编号、时间与题号缺一不可：缺了会存成空编号、1970 年的记录，宁可整批不收。 */
    static void requireSubmissionFields(JsonNode node) {
        boolean ok = node.path("id").canConvertToLong() && node.path("id").asLong() > 0
                && node.path("epoch_second").canConvertToLong() && node.path("epoch_second").asLong() > 0
                && node.path("problem_id").isTextual() && !node.path("problem_id").asText().isBlank()
                && node.path("contest_id").isTextual();
        if (!ok) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "AtCoder Problems 提交记录缺少编号、时间或题号字段，本批未入库");
        }
    }

    private FetchedSubmission toSubmission(JsonNode node) {
        String problemId = node.path("problem_id").asText();
        String contestId = node.path("contest_id").asText();
        ProblemMeta meta = metadata().problems().get(problemId);
        String title = meta != null ? meta.title() : problemId;
        Double difficulty = meta != null ? meta.difficulty() : null;
        String difficultyRaw = difficulty == null ? null : String.valueOf(Math.round(difficulty));

        FetchedProblem problem = new FetchedProblem(problemId, title,
                "https://atcoder.jp/contests/" + contestId + "/tasks/" + problemId,
                difficultyRaw,
                difficultyRaw == null ? null : "AtCoder Problems 估计 " + difficultyRaw,
                "THIRD_PARTY", List.of(), false);

        String result = node.path("result").asText("UNKNOWN");
        return new FetchedSubmission(node.path("id").asText(), problem, result, "AC".equals(result),
                Instant.ofEpochSecond(node.path("epoch_second").asLong()), "EXACT",
                node.path("language").asText(null), contestId);
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        JsonNode rank = readJson(API + "v3/user/ac_rank?user=" + enc(context.handle()));
        return new TotalsSnapshot(rank.path("count").asInt(0), null, Map.of(),
                "acRank=" + rank.path("rank").asText(""));
    }

    /**
     * 题库来自 AtCoder Problems（第三方）：标题来自 problems.json，
     * 难度是该服务的估计值，界面需标注来源；该数据源没有算法标签。
     */
    @Override
    public List<com.szpu.algohub.platform.PlatformDtos.CatalogProblem> fetchProblemCatalog() {
        MetadataCache cache = metadata();
        List<com.szpu.algohub.platform.PlatformDtos.CatalogProblem> catalog = new ArrayList<>();
        cache.problems().forEach((id, meta) -> {
            Integer difficulty = meta.difficulty() == null ? null : (int) Math.round(meta.difficulty());
            FetchedProblem problem = new FetchedProblem(id, meta.title(),
                    "https://atcoder.jp/contests/" + meta.contestId() + "/tasks/" + id,
                    difficulty == null ? null : String.valueOf(difficulty),
                    difficulty == null ? null : "AtCoder Problems 估计 " + difficulty,
                    "THIRD_PARTY", List.of(), false);
            catalog.add(new com.szpu.algohub.platform.PlatformDtos.CatalogProblem(problem, difficulty, null));
        });
        return catalog;
    }

    @Override
    public boolean fetchProblemCatalogSupported() {
        return true;
    }

    @Override
    public List<FetchedContest> fetchContests() {
        JsonNode arr = readJson(RES + "contests.json");
        List<FetchedContest> contests = new ArrayList<>();
        Instant now = Instant.now();
        for (JsonNode node : arr) {
            long start = node.path("start_epoch_second").asLong();
            int duration = node.path("duration_second").asInt();
            Instant startAt = Instant.ofEpochSecond(start);
            // 只保留近期与未来比赛，历史比赛不进入日历表。
            if (startAt.isBefore(now.minus(Duration.ofDays(7)))) {
                continue;
            }
            String id = node.path("id").asText();
            String title = node.path("title").asText(id);
            contests.add(new FetchedContest(Platform.ATCODER, id, title, startAt,
                    startAt.plusSeconds(duration), duration,
                    startAt.isAfter(now) ? "SCHEDULED"
                            : (startAt.plusSeconds(duration).isAfter(now) ? "RUNNING" : "FINISHED"),
                    ContestCategoryRules.classifyAtCoder(id, title),
                    "https://atcoder.jp/contests/" + id, "ATCODER_PROBLEMS"));
        }
        return contests;
    }

    private synchronized MetadataCache metadata() {
        MetadataCache cache = metadata.get();
        if (cache != null && cache.loadedAt().isAfter(Instant.now().minus(METADATA_TTL))) {
            return cache;
        }
        Map<String, ProblemMeta> problems = new HashMap<>();
        JsonNode list = readJson(RES + "problems.json");
        if (!list.isArray() || list.isEmpty()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "AtCoder Problems 题目目录为空或字段变化");
        }
        for (JsonNode node : list) {
            problems.put(node.path("id").asText(),
                    new ProblemMeta(node.path("title").asText(), node.path("contest_id").asText(), null));
        }
        JsonNode models = readJson(RES + "problem-models.json");
        if (!models.isObject() || models.isEmpty()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "AtCoder Problems 难度模型为空或字段变化");
        }
        models.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.path("difficulty").isNumber()) {
                return;
            }
            ProblemMeta existing = problems.get(entry.getKey());
            double difficulty = value.path("difficulty").asDouble();
            problems.put(entry.getKey(), new ProblemMeta(
                    existing != null ? existing.title() : entry.getKey(),
                    existing != null ? existing.contestId() : "",
                    difficulty));
        });
        MetadataCache fresh = new MetadataCache(Instant.now(), problems);
        metadata.set(fresh);
        return fresh;
    }

    private JsonNode readJson(String url) {
        String body = http.get(Platform.ATCODER, url);
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw ProtectionPage.orElse(Platform.ATCODER, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "AtCoder Problems 响应解析失败: " + e.getMessage()));
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
