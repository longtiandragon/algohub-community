package com.szpu.algohub.platform.adapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Avatars;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 牛客竞赛：读取公开练习页分页提交；Tracker 总量按自己的范围单列，不重复累计。
 * 归属验证读公开主页上的个人简介（签名），比赛与 Rating 取公开的 rating-history。
 */
@Component
public class NowcoderAdapter implements PlatformAdapter {

    private static final String PROFILE = "https://ac.nowcoder.com/acm/contest/profile/";
    private static final String RATING_HISTORY = "https://ac.nowcoder.com/acm/contest/rating-history?uid=";
    private static final String PROFILE_TITLE_SUFFIX = "的比赛主页";
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final PlatformHttpClient http;

    public NowcoderAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.NOWCODER;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.NOWCODER, true, true, true, false,
                "NONE", "NONE", false,
                "读取竞赛练习主页的分页提交记录与原始时间；Tracker 与竞赛题可能重叠，不重复累计。历史覆盖以源站公开分页为准。"
                + "比赛与 Rating 取公开的 Rating 记录；归属验证读公开主页上的个人简介。");
    }

    @Override
    public com.szpu.algohub.platform.PlatformDtos.SubmissionPage fetchSubmissions(SyncContext context) {
        int page;
        try { page = context.cursor() == null ? 1 : Integer.parseInt(context.cursor()); }
        catch (NumberFormatException e) { throw ApiException.validation("牛客分页游标无效"); }
        String uid = extractUid(context.platformUid() == null ? context.handle() : context.platformUid());
        String html = http.get(Platform.NOWCODER, PROFILE + uid + "/practice-coding?page=" + page + "&pageSize=100&orderType=DESC",
                Map.of("Referer", "https://ac.nowcoder.com/"), context.credential());
        return parseSubmissions(html, page, uid);
    }

    public static com.szpu.algohub.platform.PlatformDtos.SubmissionPage parseSubmissions(String html, int page, String uid) {
        Document doc = Jsoup.parse(html);
        if (doc.select(".my-state-item").isEmpty() || doc.select("table tbody").isEmpty())
            throw ProtectionPage.orElse(Platform.NOWCODER, html,
                    new ApiException(ErrorKind.PARSE_ERROR, "牛客未返回公开提交列表，保留原有记录"));
        var rows = doc.select("table tbody tr");
        Element next = doc.selectFirst(".js-next-pager:not(.disabled) a[data-page]");
        // 零提交主页会用跨列占位行代替提交记录；只认已核实的空态与明确的零提交计数。
        if (rows.size() == 1 && next == null && isZeroSubmissionPlaceholder(doc, rows.getFirst())) {
            if (ProtectionPage.looksLike(html)) throw ProtectionPage.error(Platform.NOWCODER);
            return new com.szpu.algohub.platform.PlatformDtos.SubmissionPage(List.of(), null, false, true);
        }
        var submissions = new java.util.ArrayList<com.szpu.algohub.platform.PlatformDtos.FetchedSubmission>();
        for (Element row : rows) {
            var cells = row.select("td");
            if (cells.size() != 9) throw new ApiException(ErrorKind.PARSE_ERROR, "牛客提交列表字段变化");
            Element problem = cells.get(1).selectFirst("a[href^=/acm/problem/]");
            Element run = cells.get(0).selectFirst("a");
            if (problem == null || run == null || !run.text().matches("\\d+") || !run.attr("href").contains("uid="+uid))
                throw new ApiException(ErrorKind.PARSE_ERROR, "牛客提交身份或题目字段异常");
            String pid = problem.attr("href").substring("/acm/problem/".length());
            if (!pid.matches("\\d+")) throw new ApiException(ErrorKind.PARSE_ERROR, "牛客题号异常");
            java.time.Instant time;
            try { time = java.time.LocalDateTime.parse(cells.get(8).text(), java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant(); }
            catch (Exception e) { throw new ApiException(ErrorKind.PARSE_ERROR, "牛客提交时间解析失败"); }
            String verdict = cells.get(2).text();
            var fetched = new com.szpu.algohub.platform.PlatformDtos.FetchedProblem(pid, problem.text(),
                    "https://ac.nowcoder.com/acm/problem/"+pid, null, null, "NONE", java.util.List.of(), false);
            submissions.add(new com.szpu.algohub.platform.PlatformDtos.FetchedSubmission(run.text(), fetched,
                    verdict, "答案正确".equals(verdict), time, "EXACT", cells.get(7).text(), null));
        }
        boolean more = next != null;
        if (more && !String.valueOf(page+1).equals(next.attr("data-page")))
            throw new ApiException(ErrorKind.PARSE_ERROR, "牛客分页未推进");
        return new com.szpu.algohub.platform.PlatformDtos.SubmissionPage(submissions, more ? String.valueOf(page+1) : null, more, !more);
    }

    private static boolean isZeroSubmissionPlaceholder(Document doc, Element row) {
        var cells = row.select("td");
        if (cells.size() != 1 || !"1024".equals(cells.getFirst().attr("colspan"))) return false;
        Element message = cells.getFirst().selectFirst(".empty-tip-mod p");
        if (message == null || !message.text().startsWith("没有找到你想要的内容呢")) return false;
        for (Element item : doc.select(".my-state-item")) {
            Element label = item.selectFirst("span");
            Element count = item.selectFirst(".state-num");
            if (label != null && count != null && "次提交".equals(label.text()) && "0".equals(count.text()))
                return true;
        }
        return false;
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String uid = extractUid(input);
        String html = http.get(Platform.NOWCODER, PROFILE + uid + "/practice-coding",
                Map.of("Referer", "https://ac.nowcoder.com/"), credential);
        return parseIdentity(uid, html);
    }

    /**
     * 公开主页：标题是「昵称的比赛主页」，头像旁的 .coder-info-main 里有个人简介（.coder-brief），
     * 归属验证就读这一项。用户不存在时源站返回「牛客网-用户不存在」页，不能把这几个字当成昵称。
     * 页面结构变了读不到简介区时，extra 里不放 brief，核验会提示「暂时读不到」而不扣次数。
     */
    public static ResolvedIdentity parseIdentity(String uid, String html) {
        Document doc = Jsoup.parse(html);
        String title = doc.title().trim();
        if (title.contains("用户不存在")) {
            throw new ApiException(ErrorKind.NOT_FOUND, "牛客未找到该 UID 对应的用户");
        }
        String displayName = title.endsWith(PROFILE_TITLE_SUFFIX)
                ? title.substring(0, title.length() - PROFILE_TITLE_SUFFIX.length()).trim()
                : title;
        if (displayName.isBlank()) {
            throw ProtectionPage.orElse(Platform.NOWCODER, html,
                    new ApiException(ErrorKind.NOT_FOUND, "牛客未找到该 UID 对应的用户"));
        }
        Map<String, String> extra = new LinkedHashMap<>();
        Element info = doc.selectFirst(".coder-info-main");
        if (info != null) {
            Element brief = info.selectFirst(".coder-brief");
            extra.put("brief", brief == null ? "" : brief.text().trim());
        }
        String avatar = avatar(doc);
        if (avatar != null) {
            extra.put(Avatars.KEY, avatar);
        }
        return new ResolvedIdentity(uid, uid, displayName, extra);
    }

    @Override
    public boolean avatarSupported() {
        return true;
    }

    /** 公开主页左上角的头像（.coder-info-wrap .head-pic img）。平台默认头像也照样给，选不选由同学自己定。 */
    static String avatar(Document doc) {
        Element img = doc.selectFirst(".coder-info-wrap .head-pic img");
        return img == null ? null : Avatars.https(img.attr("src"));
    }

    @Override
    public OwnershipProof ownershipProof() {
        return new OwnershipProof("brief", "牛客个人主页 → 编辑资料 → 个人简介（一句话介绍）",
                "保存后打开 ac.nowcoder.com/acm/contest/profile/你的 UID，确认名字下方的简介里能看到验证串再点核验。", 30);
    }

    @Override
    public boolean contestHistorySupported() {
        return true;
    }

    /** 公开的 rating-history：每场计分比赛一条，含名次、赛后 Rating 与变化值。没参加过比赛时是空列表。 */
    @Override
    public List<RatedContest> fetchContestHistory(SyncContext context) {
        String uid = extractUid(context.platformUid() == null ? context.handle() : context.platformUid());
        String body = http.get(Platform.NOWCODER, RATING_HISTORY + uid,
                Map.of("Referer", PROFILE + uid), context.credential());
        return parseRatingHistory(body);
    }

    /** time 是源站给的比赛时间（毫秒）；赛前 Rating = 赛后 Rating − 变化值。 */
    public static List<RatedContest> parseRatingHistory(String body) {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw ProtectionPage.orElse(Platform.NOWCODER, body,
                    new ApiException(ErrorKind.PARSE_ERROR, "牛客 Rating 记录不是预期的 JSON"));
        }
        if (root == null || !root.path("code").isIntegralNumber() || root.path("code").asInt() != 0
                || !root.path("data").isArray()) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "牛客 Rating 记录结构与预期不符");
        }
        List<RatedContest> contests = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode node : root.path("data")) {
            boolean ok = node.path("contestId").canConvertToLong() && node.path("contestName").isTextual()
                    && node.path("rating").isNumber() && node.path("changeValue").isNumber()
                    && node.path("time").canConvertToLong() && node.path("time").asLong() > 0;
            if (!ok) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "牛客 Rating 记录缺少比赛、时间或分数字段，本批未入库");
            }
            double rating = node.path("rating").asDouble();
            double change = node.path("changeValue").asDouble();
            Integer place = node.path("rank").canConvertToInt() && node.path("rank").asInt() > 0
                    ? node.path("rank").asInt() : null;
            contests.add(new RatedContest(node.path("contestId").asText(), node.path("contestName").asText(), place,
                    (int) Math.round(rating - change), (int) Math.round(rating), null, true,
                    Instant.ofEpochMilli(node.path("time").asLong())));
        }
        return contests;
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        Document doc = load(context.handle(), context.credential());
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Element item : doc.select(".my-state-item")) {
            Element num = item.selectFirst(".state-num");
            Element label = item.selectFirst("span");
            if (num == null || label == null) {
                continue;
            }
            String value = num.text().trim();
            if (value.matches("\\d+")) {
                stats.put(label.text().trim(), Integer.parseInt(value));
            }
        }
        Integer solved = stats.get("题已通过");
        Integer submissions = stats.get("次提交");
        if (solved == null) {
            throw ProtectionPage.orElse(Platform.NOWCODER, doc.html(),
                    new ApiException(ErrorKind.PARSE_ERROR, "牛客练习主页结构与预期不符，需要重新核对解析规则"));
        }
        String trackerNote;
        try {
            String uid = extractUid(context.platformUid() == null ? context.handle() : context.platformUid());
            String body = http.get(Platform.NOWCODER,
                    "https://www.nowcoder.com/problem/tracker/ranks/problem?userId="+uid,
                    Map.of("Referer", "https://www.nowcoder.com/problem/tracker"), context.credential());
            trackerNote = trackerSummary(body, uid);
        } catch (RuntimeException e) { trackerNote = "Tracker 本次未获取（不表示 0 题）"; }
        return new TotalsSnapshot(solved, submissions, Map.of(),
                "牛客竞赛主页："+solved+" 题；"+trackerNote+"；两者范围可能重叠，不相加");
    }

    public static String trackerSummary(String body, String uid) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (!root.path("code").isIntegralNumber() || root.path("code").asInt() != 0) throw new IllegalArgumentException();
            for (var row : root.path("data").path("ranks")) {
                if (uid.equals(row.path("uid").asText()) && row.path("count").canConvertToInt() && row.path("count").asInt() >= 0)
                    return "牛客 Tracker："+row.path("count").asInt()+" 题（Tracker 统计口径）";
            }
            throw new IllegalArgumentException();
        } catch (Exception e) { throw new ApiException(ErrorKind.PARSE_ERROR, "Tracker 未返回该用户的有效统计"); }
    }

    private Document load(String uid, String credential) {
        String html = http.get(Platform.NOWCODER, PROFILE + uid + "/practice-coding",
                Map.of("Referer", "https://ac.nowcoder.com/"), credential);
        return Jsoup.parse(html);
    }

    private String extractUid(String input) {
        String value = input.trim();
        if (value.contains("/")) {
            String[] parts = value.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].matches("\\d+")) {
                    return parts[i];
                }
            }
        }
        if (!value.matches("\\d+")) {
            throw ApiException.validation("牛客需要数字 UID，请填写个人主页链接中的数字部分");
        }
        return value;
    }
}
