package com.szpu.algohub.platform.adapter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.CredentialPayload;
import com.szpu.algohub.platform.Avatars;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 蓝桥云课（2026-09-28 用真实账号实测）：
 * <ul>
 *   <li>公开资料 {@code /api/v2/users/{id}/} 匿名可读：昵称、职位（job_title）等，没有通过题数。</li>
 *   <li>本人提交记录 {@code /api/v2/problems/submissions/} 必须登录（只认 Cookie 里的 lqtoken），
 *       只返回登录者自己的记录（带 user_id 参数也一样）；每条有题号、题名、是否通过、提交时间、语言，没有提交编号。
 *       按提交时间倒序，偏移分页：cursor 是 base64("o=偏移")，page_size 最大 100。</li>
 *   <li>刷题排行 {@code /api/v2/problems/pass-count-rank/?type=2} 登录后带 user_info（登录者的 ID 和通过数），
 *       用来核对登录态属于绑定的账号。那里的通过数实测停在 2025 年 6 月不再更新，不当作题数用。</li>
 * </ul>
 * 归属用公开资料里的「职位」验证：只有登录本人能改。
 */
@Component
public class LanqiaoAdapter implements PlatformAdapter {

    private static final String API = "https://www.lanqiao.cn/api/v2/";
    private static final Pattern USER_ID_IN_URL = Pattern.compile("lanqiao\\.cn/users/(\\d+)");
    private static final int PAGE_SIZE = 100;
    /** 一轮最多读的页数（2000 条提交）；再早的留到下一轮接着读 */
    static final int MAX_PAGES_PER_RUN = 20;

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LanqiaoAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.LANQIAO;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.LANQIAO, true, true, false, true,
                "NONE", "NONE", false,
                "公开资料可匿名按数字用户 ID 查询，用「职位」验证归属；"
                        + "逐条提交记录（含通过时间）只有本人登录后能看，需要保存本人登录态（Cookie 里的 lqtoken）。"
                        + "官方排行榜上的通过数长期不更新，题数以提交记录里通过的题为准。");
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String userId = extractUserId(input);
        if (StringUtils.hasText(credential)) {
            // 保存 / 试用登录态时：先核对这串登录态是不是这个账号的，贴错了在保存这一步就拦下
            requireOwner(userId, credentialOf(credential));
        }
        JsonNode user = readJson(API + "users/" + userId + "/", null);
        if (!user.hasNonNull("id")) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "蓝桥云课用户资料结构和预期不符，需要重新核对接口字段");
        }
        String nickname = user.path("name").asText(userId);
        Map<String, String> extra = new LinkedHashMap<>();
        // 公开资料里的职位，归属验证用
        extra.put("jobTitle", user.path("job_title").isTextual() ? user.path("job_title").asText() : "");
        extra.put("joinDate", user.path("join_date").asText(""));
        String avatar = avatar(user.path("avatar_url").asText(""));
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(String.valueOf(user.path("id").asLong()), nickname, nickname, Map.copyOf(extra));
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 公开资料里的 avatar_url（后面带缩放参数 ?eo-img…）。平台默认头像也照样给，选不选由同学自己定。 */
    static String avatar(String raw) {
        return Avatars.https(raw);
    }

    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("jobTitle", "蓝桥云课 → 右上角头像 → 个人设置 → 基本信息 → 职位",
                "请暂时将职位替换为完整验证串，保存后等几秒再点核验；职位栏平时可以空着，核验通过后改回原样也行。", 30,
                "https://passport.lanqiao.cn/main?action=userinfo&usertype=-1");
    }

    /** 昵称会重名，查询一律用数字 ID */
    @Override
    public String lookupKey(String platformUid, String handle) {
        return platformUid != null ? platformUid : handle;
    }

    @Override
    public SubmissionPage fetchSubmissions(SyncContext context) {
        String credential = credentialOf(context.credential());
        requireOwner(context.platformUid(), credential);

        Cursor cursor = Cursor.parse(context.cursor());
        boolean resuming = cursor.offset() > 0;
        long pending = resuming ? cursor.pending() : 0L;
        int offset = cursor.offset();
        List<FetchedSubmission> collected = new ArrayList<>();
        boolean reachedStart = false;
        boolean truncated = false;

        for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
            String url = API + "problems/submissions/?page_size=" + PAGE_SIZE
                    + (offset > 0 ? "&cursor=" + URLEncoder.encode(offsetCursor(offset), StandardCharsets.UTF_8) : "");
            JsonNode root = readJson(url, credential);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "蓝桥云课提交记录结构和预期不符，需要重新核对接口字段");
            }
            boolean stop = false;
            for (JsonNode node : results) {
                FetchedSubmission submission = toSubmission(node);
                long ms = submission.submittedAt().toEpochMilli();
                pending = Math.max(pending, ms);
                // 增量：读到已入库、又不在回查窗口里的记录就停
                if (!resuming && cursor.latest() > 0 && ms <= cursor.latest()
                        && (context.recheckFrom() == null || submission.submittedAt().isBefore(context.recheckFrom()))) {
                    stop = true;
                    break;
                }
                collected.add(submission);
            }
            offset += results.size();
            if (stop) {
                break;
            }
            if (results.isEmpty() || !StringUtils.hasText(root.path("next").asText(null))) {
                reachedStart = true;
                break;
            }
            if (page == MAX_PAGES_PER_RUN - 1) {
                truncated = true;
            }
        }
        if (truncated) {
            // 还有更早的页：记下读到哪、这一轮见过的最新时间，下一轮接着往前读，全部读完才推进「最新」
            return new SubmissionPage(collected, new Cursor(cursor.latest(), offset, pending).format(), true, false);
        }
        long latest = Math.max(cursor.latest(), pending);
        return new SubmissionPage(collected, new Cursor(latest, 0, 0).format(), false, reachedStart);
    }

    /** 登录态必须属于绑定的账号：提交记录接口只认登录者，贴错了会把别人的记录记到这个账号上 */
    private void requireOwner(String platformUid, String credential) {
        JsonNode info = readJson(API + "problems/pass-count-rank/?type=2", credential).path("user_info");
        if (!info.hasNonNull("id")) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "蓝桥云课登录态已失效，请重新复制 lqtoken");
        }
        String owner = String.valueOf(info.path("id").asLong());
        if (platformUid != null && !platformUid.equals(owner)) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID,
                    "这串登录态属于另一个蓝桥云课账号（ID " + owner + "），和绑定的账号（ID " + platformUid + "）不一致");
        }
    }

    static FetchedSubmission toSubmission(JsonNode node) {
        if (!node.hasNonNull("problem_id") || !node.hasNonNull("created_at") || !node.has("is_passed")) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "蓝桥云课提交记录缺少题号、时间或结果字段");
        }
        String problemId = String.valueOf(node.path("problem_id").asLong());
        Instant at;
        try {
            at = OffsetDateTime.parse(node.path("created_at").asText()).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "蓝桥云课提交时间格式变化：" + node.path("created_at").asText());
        }
        boolean passed = node.path("is_passed").asBoolean(false);
        String title = node.path("problem_name").asText(null);
        FetchedProblem problem = new FetchedProblem(problemId, title,
                "https://www.lanqiao.cn/problems/" + problemId + "/learning/", null, null, null, List.of(), false);
        String contest = node.hasNonNull("contest_id") ? node.path("contest_id").asText() : null;
        // 接口没有提交编号：同一题同一毫秒不会有两次提交，用「题号-提交时间」当编号去重
        return new FetchedSubmission(problemId + "-" + at.toEpochMilli(), problem, passed ? "AC" : "未通过", passed, at,
                "EXACT", node.path("language").asText(null), contest);
    }

    static String offsetCursor(int offset) {
        return Base64.getEncoder().encodeToString(("o=" + offset).getBytes(StandardCharsets.US_ASCII));
    }

    /** 接受整段 Cookie、「lqtoken=…」或只有值；只保留 lqtoken 发给蓝桥，其余 Cookie 不带 */
    static String credentialOf(String raw) {
        String cookie = CredentialPayload.parse(raw).cookie();
        if (!StringUtils.hasText(cookie)) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "蓝桥云课同步需要本人登录态（Cookie 里的 lqtoken）");
        }
        cookie = cookie.trim();
        if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) {
            cookie = cookie.substring(7).trim();
        }
        Matcher m = Pattern.compile("(?:^|;\\s*)lqtoken=([A-Za-z0-9]+)").matcher(cookie);
        String token = m.find() ? m.group(1) : cookie.matches("[A-Za-z0-9]{16,64}") ? cookie : null;
        if (token == null) {
            throw ApiException.validation("蓝桥云课请填写 Cookie 里 lqtoken 的值，或整段 Cookie");
        }
        return new CredentialPayload("lqtoken=" + token, null, Map.of()).toStorage();
    }

    /** 接受数字用户 ID 或个人主页链接（https://www.lanqiao.cn/users/3057126/） */
    static String extractUserId(String input) {
        if (input == null || input.isBlank()) {
            throw ApiException.validation("请填写蓝桥云课的数字用户 ID 或个人主页链接");
        }
        String value = input.trim();
        if (value.matches("\\d{1,12}")) {
            return value;
        }
        Matcher matcher = USER_ID_IN_URL.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw ApiException.validation("请填写蓝桥云课个人主页链接（形如 https://www.lanqiao.cn/users/3057126/）或其中的数字 ID");
    }

    private JsonNode readJson(String url, String credential) {
        String body = http.get(Platform.LANQIAO, url, Map.of("Referer", "https://www.lanqiao.cn/"), credential);
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw ProtectionPage.orElse(Platform.LANQIAO, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "蓝桥云课返回的不是 JSON，接口可能变了"));
        }
    }

    /** 同步位置：latest 已完整读到的最新提交时间（毫秒）；offset、pending 是没读完的那一轮读到哪、见过的最新时间 */
    record Cursor(long latest, int offset, long pending) {
        static Cursor parse(String raw) {
            long latest = 0;
            int offset = 0;
            long pending = 0;
            if (StringUtils.hasText(raw)) {
                for (String part : raw.split(";")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length != 2) {
                        continue;
                    }
                    try {
                        switch (kv[0].trim()) {
                            case "latest" -> latest = Long.parseLong(kv[1].trim());
                            case "off" -> offset = Integer.parseInt(kv[1].trim());
                            case "pending" -> pending = Long.parseLong(kv[1].trim());
                            default -> { }
                        }
                    } catch (NumberFormatException ignored) {
                        // 坏掉的游标当作从头读，由编号去重
                    }
                }
            }
            return new Cursor(Math.max(0, latest), Math.max(0, offset), Math.max(0, pending));
        }

        String format() {
            return offset > 0 ? "latest=" + latest + ";off=" + offset + ";pending=" + pending : "latest=" + latest;
        }
    }
}
