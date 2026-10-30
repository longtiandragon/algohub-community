package com.szpu.algohub.platform.adapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 力扣中国站：公开 GraphQL 可取得按难度分的通过题数，以及最近通过的若干道题（noj-go 接口，每题一条）。
 * 更早的提交时间线不公开：逐题明细只补得到最近这部分，历史永远标为不完整，不伪造逐日历史。
 */
@Component
public class LeetCodeCnAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeCnAdapter.class);
    private static final String GRAPHQL = "https://leetcode.cn/graphql/";
    /** 个人主页"最近通过"用的接口，匿名可查 */
    private static final String NOJ_GRAPHQL = "https://leetcode.cn/graphql/noj-go/";

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LeetCodeCnAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.LEETCODE_CN;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.LEETCODE_CN, true, true, true, false,
                "OFFICIAL", "NONE", true,
                "公开接口提供 Easy/Medium/Hard 通过题数与周赛信息；逐题明细只公开最近 15 道通过的题"
                        + "（时间是最近一次通过），可用浏览器插件补历史 AC 明细，热力图每题只计首次 AC。");
    }

    @Override
    public String partialHistoryNote() {
        return "力扣只公开最近 15 道通过的题（每题一条，时间是最近一次通过），更早的 AC 明细可用力扣浏览器同步补充；首次 AC 日期以完整读取的历史为准";
    }

    /** 最近通过的题：每题一条、时间是最近一次通过。没有游标与分页，每轮都取这一份。 */
    @Override
    public SubmissionPage fetchSubmissions(SyncContext context) {
        JsonNode list = query(NOJ_GRAPHQL, """
                query recentAcSubmissions($userSlug: String!) {
                  recentACSubmissions(userSlug: $userSlug) {
                    submissionId submitTime
                    question { title translatedTitle titleSlug questionFrontendId }
                  }
                }
                """, Map.of("userSlug", context.handle()), null).path("recentACSubmissions");
        return parseRecentAccepted(list, context.cursor());
    }

    static SubmissionPage parseRecentAccepted(JsonNode list, String previousCursor) {
        if (!list.isArray()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "力扣最近通过记录结构与预期不符");
        }
        long maxId = 0;
        try {
            maxId = previousCursor == null ? 0 : Long.parseLong(previousCursor);
        } catch (NumberFormatException ignored) {
            // 旧游标不是数字：从这一份重新记
        }
        List<FetchedSubmission> submissions = new ArrayList<>();
        for (JsonNode node : list) {
            JsonNode question = node.path("question");
            String slug = question.path("titleSlug").asText("");
            boolean ok = node.path("submissionId").canConvertToLong() && node.path("submissionId").asLong() > 0
                    && node.path("submitTime").canConvertToLong() && node.path("submitTime").asLong() > 0
                    && slug.matches("[A-Za-z0-9_-]{1,128}");
            if (!ok) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "力扣最近通过记录缺少编号、时间或题目字段，本批未入库");
            }
            String frontendId = question.path("questionFrontendId").asText("");
            String name = question.path("translatedTitle").asText("");
            if (name.isBlank()) {
                name = question.path("title").asText(slug);
            }
            FetchedProblem problem = new FetchedProblem(slug, frontendId.isBlank() ? name : frontendId + ". " + name,
                    "https://leetcode.cn/problems/" + slug + "/", null, null, "NONE", List.of(), false);
            long submissionId = node.path("submissionId").asLong();
            maxId = Math.max(maxId, submissionId);
            submissions.add(new FetchedSubmission(String.valueOf(submissionId), problem, "AC", true,
                    Instant.ofEpochSecond(node.path("submitTime").asLong()), "EXACT", null, null));
        }
        // 永远不会"回填完"：只公开最近这部分
        return new SubmissionPage(submissions, maxId > 0 ? String.valueOf(maxId) : previousCursor, false, false);
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String slug = normalizeSlug(input);
        JsonNode data = query("""
                query u($userSlug:String!){
                  userProfilePublicProfile(userSlug:$userSlug){
                    username
                    profile{ userSlug realName aboutMe userAvatar }
                  }
                }
                """, Map.of("userSlug", slug), credential);
        JsonNode profile = data.path("userProfilePublicProfile");
        if (profile.isMissingNode() || profile.isNull()) {
            throw new ApiException(ErrorKind.NOT_FOUND, "力扣未找到该用户，请确认主页地址中的 slug");
        }
        String realSlug = profile.path("profile").path("userSlug").asText(slug);
        String username = profile.path("username").asText(realSlug);
        String realName = profile.path("profile").path("realName").asText(username);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("username", username);
        extra.put("aboutMe", profile.path("profile").path("aboutMe").asText(""));
        String avatar = avatar(profile.path("profile").path("userAvatar").asText(""));
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(realSlug, realSlug, realName, Map.copyOf(extra));
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 公开资料里的 userAvatar。平台默认头像也照样给，选不选由同学自己定。 */
    static String avatar(String raw) {
        return Avatars.https(raw);
    }

    /** 公开资料 aboutMe（个人简介）只有本人能改；源站会先审核，审核通过后公开接口才返回新内容。 */
    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("aboutMe", "力扣个人主页 → 编辑资料 → 个人简介",
                "力扣会先审核简介，审核通过、公开主页显示验证串后再点核验。", 60,
                "https://leetcode.cn/settings/profile/");
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        JsonNode data = query("""
                query q($userSlug:String!){
                  userProfileUserQuestionProgressV2(userSlug:$userSlug){
                    numAcceptedQuestions{ difficulty count }
                  }
                }
                """, Map.of("userSlug", context.handle()), context.credential());
        JsonNode accepted = data.path("userProfileUserQuestionProgressV2").path("numAcceptedQuestions");
        if (!accepted.isArray()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "力扣返回结构与预期不符");
        }
        if (accepted.isEmpty()) {
            // 实测：不存在的用户返回空列表；真实的零题用户三个难度都在、数量为 0
            throw new ApiException(ErrorKind.NOT_FOUND, "力扣没有返回该用户的题数（账号可能已改名、注销或主页不公开），不按 0 题处理");
        }
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode node : accepted) {
            String difficulty = node.path("difficulty").asText("UNKNOWN");
            int count = PlatformCount.read(node.path("count"), "力扣通过题数");
            if (difficulty.equals("UNKNOWN") || breakdown.containsKey(difficulty))
                throw new ApiException(ErrorKind.PARSE_ERROR, "力扣难度字段缺失或重复，保留原有统计");
            breakdown.put(difficulty, count);
            if (count > Integer.MAX_VALUE - total) throw new ApiException(ErrorKind.PARSE_ERROR, "力扣通过题数超出范围");
            total += count;
        }
        return new TotalsSnapshot(total, null, breakdown, null);
    }

    @Override
    public List<FetchedContest> fetchContests() {
        List<FetchedContest> contests = new ArrayList<>();
        try {
            JsonNode data = query("""
                    {
                      contestUpcomingContests{ title titleSlug startTime duration }
                    }
                    """, Map.of(), null);
            for (JsonNode node : data.path("contestUpcomingContests")) {
                String slug = node.path("titleSlug").asText();
                String title = node.path("title").asText(slug);
                long start = node.path("startTime").asLong();
                int duration = node.path("duration").asInt(5400);
                Instant startAt = Instant.ofEpochSecond(start);
                contests.add(new FetchedContest(Platform.LEETCODE_CN, slug, title, startAt,
                        startAt.plusSeconds(duration), duration, "SCHEDULED",
                        ContestCategoryRules.classifyLeetCode(title),
                        "https://leetcode.cn/contest/" + slug, "LEETCODE_GRAPHQL"));
            }
        } catch (ApiException e) {
            // 源站结构变化时不阻塞其他平台的比赛刷新。
            log.warn("力扣比赛列表获取失败: {}", e.getMessage());
        }
        return contests;
    }

    private String normalizeSlug(String input) {
        String slug = input.trim();
        if (slug.contains("leetcode.cn/u/")) {
            slug = slug.substring(slug.indexOf("/u/") + 3);
        }
        return slug.replaceAll("/+$", "");
    }

    private JsonNode query(String query, Map<String, Object> variables, String credential) {
        return query(GRAPHQL, query, variables, credential);
    }

    private JsonNode query(String endpoint, String query, Map<String, Object> variables, String credential) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("variables", variables);
            String body = http.postJson(Platform.LEETCODE_CN, endpoint, mapper.writeValueAsString(payload),
                    Map.of("Referer", "https://leetcode.cn/"), credential);
            JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (Exception e) {
                throw ProtectionPage.orElse(Platform.LEETCODE_CN, body,
                        new ApiException(ErrorKind.PARSE_ERROR, "力扣响应不是 JSON"));
            }
            if (root.has("errors") && !root.path("errors").isEmpty()) {
                String message = root.path("errors").get(0).path("message").asText("力扣接口返回错误");
                throw new ApiException(ErrorKind.PARSE_ERROR, "力扣接口错误: " + message);
            }
            return root.path("data");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "力扣响应解析失败: " + e.getMessage());
        }
    }
}
