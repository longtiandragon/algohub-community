package com.szpu.algohub.platform.adapter;

import java.util.LinkedHashMap;
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
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 码蹄集：官网前端（Nuxt）通过 /exam-back 前缀调用后端接口，
 * 其中 /exam-back/pc/queryUserDetailById.do 匿名即可按数字用户 ID 查询公开资料。
 *
 * 字段口径按官网前端的展示文案确认：
 * passNum 对应页面上的“已通过”，passPercent 对应“准确率”，rankOrder 对应“刷题排名”；
 * queryInfo.recordList 是成就勋章列表，不是提交记录，因此不作为提交历史使用。
 * finishNum 没有对应的公开文案，只作为原始附加信息保留，不赋予语义。
 *
 * 用户可按昵称分页查找候选；选择后以数字 ID 绑定，昵称只作展示。
 */
@Component
public class MatijiAdapter implements PlatformAdapter {

    private static final String USER_DETAIL = "https://www.matiji.net/exam-back/pc/queryUserDetailById.do";
    private static final Pattern USER_ID_IN_URL = Pattern.compile("(?:other-homepage|user|homepage)/(\\d+)");

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MatijiAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.MATIJI;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.MATIJI, true, false, true, false,
                "NONE", "NONE", false,
                "官网前端接口可匿名按数字用户 ID 查询“已通过”题数、准确率与刷题排名；"
                        + "没有公开的逐条提交时间线，因此只保存总量快照与观察增量。"
                        + "按昵称查找时先查共享昵称索引（排行榜上看到过的账号），找不到再实时翻排行榜；"
                        + "选中账号后用个性签名验证归属，绑定后按数字 ID 同步，不因改昵称换号。");
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String userId = extractUserId(input);
        JsonNode data = queryUser(userId, credential);
        String nickname = data.path("nickname").asText(userId);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("school", data.path("school").asText(""));
        extra.put("passNum", data.path("passNum").asText("0"));
        extra.put("rankOrder", data.path("rankOrder").asText(""));
        // 公开资料里的个性签名，归属验证用
        extra.put("signature", data.path("signature").isTextual() ? data.path("signature").asText() : "");
        String avatar = avatar(data.path("headimgurl").asText(""));
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(userId, nickname, nickname, Map.copyOf(extra));
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 公开资料里的 headimgurl 是站内路径（/img/日期/文件名），图片在 www.matiji.net 上。平台默认头像也照样给。 */
    static String avatar(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        String url = Avatars.https(value.startsWith("/") && !value.startsWith("//") ? "https://www.matiji.net" + value : value);
        return url;
    }

    /** 个性签名随公开资料一起返回，只有登录本人能改。账号按数字 ID 绑定，改昵称不影响。 */
    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("signature", "码蹄集个人中心 → 编辑资料 → 个性签名", null, 30,
                "https://www.matiji.net/exam/personalcenter/homepage");
    }

    /** 昵称会重名，也不能直接查询：重新查询一律用数字 ID */
    @Override
    public String lookupKey(String platformUid, String handle) {
        return platformUid != null ? platformUid : handle;
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        String userId = context.platformUid() != null ? context.platformUid() : extractUserId(context.handle());
        JsonNode data = queryUser(userId, context.credential());
        if (!data.has("passNum")) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "码蹄集返回结构与预期不符，需要重新核对接口字段");
        }
        // 只有“已通过”是明确的题数口径；准确率、排名与 finishNum 作为原始附加信息保留。
        String extra = "passPercent=" + data.path("passPercent").asText("")
                + "; rankOrder=" + data.path("rankOrder").asText("")
                + "; finishNum=" + data.path("finishNum").asText("")
                + "; school=" + data.path("school").asText("");
        return new TotalsSnapshot(PlatformCount.read(data.path("passNum"), "码蹄集已通过题数"), null, Map.of(), extra);
    }

    /** 接受数字 ID 或个人主页链接；昵称无法定位唯一用户，直接拒绝。 */
    private String extractUserId(String input) {
        if (input == null || input.isBlank()) {
            throw ApiException.validation("请填写码蹄集的数字用户 ID 或个人主页链接");
        }
        String value = input.trim();
        if (value.matches("\\d+")) {
            return value;
        }
        Matcher matcher = USER_ID_IN_URL.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw ApiException.validation(
                "请先按昵称查找并选择账号；昵称可能重复或被修改，绑定时使用所选账号 ID。个人中心通用链接不包含账号 ID。");
    }

    public record Candidate(String userId, String nickname, String school, int passNum, int rankOrder) {}
    /** 排行榜的一页（全部行）：用于建立昵称索引，也用于按昵称筛选候选。 */
    public record RankPage(java.util.List<Candidate> rows, int nextStart, int total) {
        public boolean hasMore() {
            return nextStart < total;
        }
    }

    public static final int RANK_PAGE_SIZE = 50;

    /** 读排行榜的一页。每次只请求一页，由调用方控制进度与频率。 */
    public RankPage rankPage(int start) {
        if (start < 0 || start > 1000000) {
            throw ApiException.validation("查询起点无效");
        }
        String body = http.postForm(Platform.MATIJI, "https://www.matiji.net/exam-back/pc/ojRankByType.do",
                Map.of("rankType", "0", "start", String.valueOf(start), "limit", String.valueOf(RANK_PAGE_SIZE)),
                Map.of("Referer", "https://www.matiji.net/"), null);
        return parseRankPage(body, start);
    }

    public static RankPage parseRankPage(String body, int start) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            if (!"0".equals(root.path("error_no").asText()))
                throw new ApiException(ErrorKind.UPSTREAM_ERROR, "码蹄集排行榜暂不可用，请稍后继续查找");
            JsonNode data = root.path("data"), rows = data.path("datas");
            if (!rows.isArray() || !data.path("total").canConvertToInt() || data.path("total").asInt() < 0)
                throw new IllegalArgumentException();
            int total = data.path("total").asInt();
            if (rows.isEmpty() && start < total) throw new IllegalArgumentException();
            var result = new java.util.ArrayList<Candidate>();
            for (JsonNode row : rows) {
                String id = row.path("userId").asText();
                if (!id.matches("\\d+") || !row.path("passNum").canConvertToInt()) throw new IllegalArgumentException();
                result.add(new Candidate(id, row.path("nickname").asText(), row.path("school").asText(""),
                        row.path("passNum").asInt(), row.path("rankOrder").asInt()));
            }
            return new RankPage(result, start + rows.size(), total);
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw ProtectionPage.orElse(Platform.MATIJI, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "码蹄集排行榜结构变化，未绑定任何账号"));
        }
    }

    private JsonNode queryUser(String userId, String credential) {
        String body = http.postForm(Platform.MATIJI, USER_DETAIL,
                Map.of("userId", userId),
                Map.of("Referer", "https://www.matiji.net/",
                        "Origin", "https://www.matiji.net",
                        "X-Requested-With", "XMLHttpRequest"),
                credential);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            // 返回 HTML 通常意味着接口路径变化或被拦截，不当作“用户不存在”。
            throw ProtectionPage.orElse(Platform.MATIJI, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "码蹄集响应不是 JSON，接口可能已调整"));
        }
        String errorNo = root.path("error_no").asText("");
        JsonNode data = root.path("data");
        if (!"0".equals(errorNo) || !data.isObject()) {
            String message = data.isTextual() ? data.asText() : "码蹄集返回失败";
            if (message.contains("登陆") || message.contains("登录")) {
                throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "码蹄集该接口需要登录态：" + message);
            }
            if (message.contains("参数")) {
                throw new ApiException(ErrorKind.NOT_FOUND, "码蹄集未找到该用户 ID（" + userId + "）");
            }
            throw new ApiException(ErrorKind.UPSTREAM_ERROR, "码蹄集返回：" + message);
        }
        return data;
    }
}
