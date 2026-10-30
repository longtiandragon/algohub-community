package com.szpu.algohub.platform.adapter;

import java.util.Map;

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
 * 洛谷：公开练习页提供通过题单；登录后的评测分页提供原始提交时间。
 * 两条采集路径独立，记录页鉴权失败不能抹掉已收录的公开题单。
 */
@Component
public class LuoguAdapter implements PlatformAdapter {

    private static final String SEARCH = "https://www.luogu.com.cn/api/user/search?keyword=";
    private static final String USER = "https://www.luogu.com.cn/user/";

    private final PlatformHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LuoguAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.LUOGU;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.LUOGU, true, false, true, false,
                "OFFICIAL", "OFFICIAL", false,
                "公开题单无需登录；AC 评测时间由用户本人在洛谷浏览器内主动同步。站点不保存洛谷 Cookie。首次完整同步后进入近期排行与动态。");
    }

    @Override
    public com.szpu.algohub.platform.PlatformDtos.SubmissionPage fetchSubmissions(SyncContext context) {
        throw new ApiException(ErrorKind.UNSUPPORTED, "洛谷 AC 评测时间请在本人浏览器内主动同步");
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String keyword = input.trim();
        if (keyword.contains("/")) {
            keyword = keyword.substring(keyword.lastIndexOf('/') + 1);
        }
        String body = http.get(Platform.LUOGU, SEARCH + enc(keyword),
                Map.of("x-luogu-type", "content-only", "Referer", "https://www.luogu.com.cn/"), credential);
        JsonNode root = readJson(body);
        JsonNode users = root.path("users");
        if (!users.isArray() || users.isEmpty() || users.get(0).isNull()) {
            throw new ApiException(ErrorKind.NOT_FOUND, "洛谷未找到该用户名对应的账号");
        }
        JsonNode user = users.get(0);
        String uid = user.path("uid").asText();
        String name = user.path("name").asText(keyword);
        Map<String, String> extra = new java.util.LinkedHashMap<>();
        extra.put("color", user.path("color").asText(""));
        extra.put("ccfLevel", user.path("ccfLevel").asText("0"));
        String avatar = Avatars.https(user.path("avatar").asText(""));
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        try {
            // 公开主页里的个性签名只有本人能改，归属验证用；匿名读取，不带登录态
            String home = http.get(Platform.LUOGU, USER + uid,
                    Map.of("Referer", "https://www.luogu.com.cn/", "x-lentille-request", "content-only"), null);
            extra.put("slogan", slogan(home, uid));
        } catch (ApiException e) {
            // 读不到公开主页（例如防护页）不影响查询与添加；核验时会说明"暂时读不到"，不算失败
        }
        return new ResolvedIdentity(uid, name, name, Map.copyOf(extra));
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 公开主页 JSON 里的个性签名。主页属于别的 UID 或不是 JSON（防护页）时报错，不返回空签名。 */
    static String slogan(String body, String uid) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new ApiException(ErrorKind.RATE_LIMITED, "洛谷返回了防护页或非 JSON 内容，稍后再试");
        }
        JsonNode user = root.path("data").path("user");
        if (!user.isObject() || !uid.equals(user.path("uid").asText())) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷公开主页不是该 UID 的资料");
        }
        return user.path("slogan").isTextual() ? user.path("slogan").asText() : "";
    }

    /** 个性签名在公开主页上显示，只有登录本人能改。 */
    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("slogan", "洛谷个人主页 → 直接点一下个性签名修改",
                "改好后刷新自己的主页，签名里能看到验证串再点核验。", 30);
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        String uid = context.platformUid() != null ? context.platformUid() : context.handle();
        String body = http.get(Platform.LUOGU, USER + uid + "/practice",
                Map.of("Referer", "https://www.luogu.com.cn/", "x-lentille-request", "content-only"), null);
        TotalsSnapshot snapshot = LuoguPracticeParser.parse(body, uid);
        Map<java.time.LocalDate, Integer> activity = Map.of();
        String note = snapshot.rawExtra() + "；公开数据匿名同步，无需维持 Cookie";
        try {
            String home = http.get(Platform.LUOGU, USER + uid,
                    Map.of("Referer", "https://www.luogu.com.cn/", "x-lentille-request", "content-only"), null);
            activity = LuoguPracticeParser.activity(home, uid);
            note += "；每日平台活动已读取，仅覆盖源站公开日期，不是首次 AC";
        } catch (ApiException e) {
            note += "；每日平台活动暂不可用，保留此前活动缓存";
        }
        return new TotalsSnapshot(snapshot.solvedTotal(), snapshot.submissionTotal(),
                snapshot.difficultyBreakdown(), note, snapshot.solvedProblems(), activity);
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷响应解析失败: " + e.getMessage());
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
