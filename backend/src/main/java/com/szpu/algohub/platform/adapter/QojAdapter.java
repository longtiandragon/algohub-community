package com.szpu.algohub.platform.adapter;

import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * QOJ：用户主页匿名访问会跳转登录，必须提供登录态。
 * 按登录后页面结构解析通过总量与 AC 评测分页。
 */
@Component
public class QojAdapter implements PlatformAdapter {

    private static final String PROFILE = "https://qoj.ac/user/profile/";

    private final PlatformHttpClient http;

    public QojAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.QOJ;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.QOJ, false, true, false, false,
                "NONE", "NONE", false,
                "填写用户名后连接浏览器插件，在已登录 QOJ 的浏览器里同步 AC 记录，无需复制 Cookie；未验证归属的账号仅用于个人统计。");
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        if (!StringUtils.hasText(credential)) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 需要先提供本人登录态才能验证账号");
        }
        Document doc = load(input.trim(), credential);
        Element title = doc.selectFirst("title");
        if (title == null || title.text().toLowerCase().contains("login")) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 登录态无效或已过期，请重新授权");
        }
        return new ResolvedIdentity(input.trim(), input.trim(), input.trim(), Map.of());
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        Document doc = load(context.handle(), context.credential());
        Element solvedList = doc.selectFirst(".uoj-ac-problems-list, #solved-problems, .uoj-solved-problems");
        if (solvedList == null) {
            for (Element heading : doc.select("h4.list-group-item-heading")) {
                if (heading.text().startsWith("Accepted problems")) {
                    Element next = heading.nextElementSibling();
                    if (next != null && next.hasClass("list-group-item-text")) solvedList = next;
                    break;
                }
            }
        }
        if (solvedList == null) {
            throw ProtectionPage.orElse(Platform.QOJ, doc.html(), new ApiException(ErrorKind.PARSE_ERROR,
                    "QOJ 页面未找到通过题目区域，需要用真实账号核对解析规则后再启用"));
        }
        var ids = new java.util.HashSet<String>();
        for (Element link : solvedList.select("a[href]")) {
            var matcher = java.util.regex.Pattern.compile("^(?:https://qoj\\.ac)?/problem/(\\d+)/?(?:[?#].*)?$").matcher(link.attr("href"));
            if (matcher.matches()) ids.add(matcher.group(1));
        }
        if (ids.isEmpty() && !solvedList.select("a[href]").isEmpty())
            throw new ApiException(ErrorKind.PARSE_ERROR, "QOJ 通过题目链接结构变化，保留原有统计");
        int solved = ids.size();
        return new TotalsSnapshot(solved, null, Map.of(), null);
    }

    private Document load(String handle, String credential) {
        if (!StringUtils.hasText(credential)) {
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 同步需要有效登录态");
        }
        String html = http.get(Platform.QOJ, PROFILE + handle, Map.of("Referer", "https://qoj.ac/"), QojCredential.normalize(credential));
        Document doc=Jsoup.parse(html);
        if (doc.title().toLowerCase(java.util.Locale.ROOT).contains("login")
                || doc.selectFirst("input[type=password]") != null)
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 返回登录页面，请重新提供有效登录态");
        return doc;
    }

    @Override
    public com.szpu.algohub.platform.PlatformDtos.SubmissionPage fetchSubmissions(SyncContext context) {
        if (!StringUtils.hasText(context.credential()))
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ AC 历史需要本人登录态");
        int page;
        try { page = context.cursor() == null ? 1 : Integer.parseInt(context.cursor()); }
        catch (NumberFormatException e) { throw ApiException.validation("QOJ 分页游标无效"); }
        if (page < 1 || page > 5000) throw ApiException.validation("QOJ 分页超出范围");
        String handle = java.net.URLEncoder.encode(context.handle(), java.nio.charset.StandardCharsets.UTF_8);
        String html = http.get(Platform.QOJ, "https://qoj.ac/submissions?submitter=" + handle
                + "&min_score=100&max_score=100&page=" + page, Map.of("Referer", "https://qoj.ac/"), QojCredential.normalize(context.credential()));
        return QojRecordParser.parse(html, context.handle(), page);
    }
}
