package com.szpu.algohub.platform.adapter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformHttpClient;

/**
 * 杭电 OJ：userstatus.php 页面提供 Solved / Submissions 等总量统计，页面为 GBK 编码。
 * status.php 返回按 Run ID 倒序的公开练习提交，first 参数为包含边界的上限。
 */
@Component
public class HduAdapter implements PlatformAdapter {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String USER_STATUS = "https://acm.hdu.edu.cn/userstatus.php?user=";
    private static final String STATUS = "https://acm.hdu.edu.cn/status.php?user=";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    // 页面标签：Problems Solved 为去重通过题数，Submissions 为总提交次数，Accepted 为通过提交次数。
    private static final String KEY_SOLVED = "Problems Solved";
    private static final String KEY_SUBMISSIONS = "Submissions";

    private final PlatformHttpClient http;

    public HduAdapter(PlatformHttpClient http) {
        this.http = http;
    }

    @Override
    public Platform platform() {
        return Platform.HDU;
    }

    @Override
    public PlatformCapability capability() {
        return new PlatformCapability(Platform.HDU, true, true, true, false,
                "NONE", "NONE", false,
                "公开练习提交按 Run ID 分页，包含通过与失败记录，时间按北京时间解析；不涵盖独立比赛系统，无可靠难度或标签。");
    }

    @Override
    public ResolvedIdentity resolveIdentity(String input, String credential) {
        String user = input.trim();
        Map<String, String> stats = loadStats(user);
        if (!stats.containsKey(KEY_SOLVED)) {
            throw new ApiException(ErrorKind.NOT_FOUND, "杭电 OJ 未找到该用户或页面结构已变化");
        }
        return new ResolvedIdentity(user, user, user, Map.of("rank", stats.getOrDefault("Rank", "")));
    }

    @Override
    public TotalsSnapshot fetchTotals(SyncContext context) {
        Map<String, String> stats = loadStats(context.handle());
        Integer solved = parseInt(stats.get(KEY_SOLVED));
        Integer submissions = parseInt(stats.get(KEY_SUBMISSIONS));
        if (solved == null) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "杭电 OJ 页面结构与预期不符");
        }
        return new TotalsSnapshot(solved, submissions, Map.of(),
                "rank=" + stats.getOrDefault("Rank", "") + "; acceptedSubmissions="
                        + stats.getOrDefault("Accepted", ""));
    }

    // 完成游标为最大 Run ID；回填中为 scan:已知最大:本轮最大:下一页上限。
    // 每个任务只请求一页，保存真实分页位置，避免大账号反复从第一页开始。
    @Override
    public SubmissionPage fetchSubmissions(SyncContext context) {
        long known = 0, newest = 0, upper = 0;
        try {
            if (context.cursor() != null && !context.cursor().isBlank()) {
                if (context.cursor().startsWith("scan:")) {
                    String[] parts = context.cursor().split(":");
                    if (parts.length != 4) throw new IllegalArgumentException();
                    known = Long.parseLong(parts[1]);
                    newest = Long.parseLong(parts[2]);
                    upper = Long.parseLong(parts[3]);
                    if (upper <= 0 || newest < known) throw new IllegalArgumentException();
                } else {
                    known = Long.parseLong(context.cursor());
                    newest = known;
                }
                if (known < 0) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "杭电同步游标无效，请重建该账号历史");
        }
        String url = STATUS + URLEncoder.encode(context.handle(), StandardCharsets.UTF_8)
                + (upper > 0 ? "&first=" + upper : "");
        ParsedPage page = parseStatus(http.get(Platform.HDU, url, Map.of(), null, GBK), context.handle());
        List<FetchedSubmission> collected = new ArrayList<>();
        long previous = Long.MAX_VALUE;
        for (FetchedSubmission submission : page.rows()) {
            long id = Long.parseLong(submission.platformSubId());
            if (id >= previous || (upper > 0 && id > upper)) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "杭电分页未按 Run ID 向历史推进");
            }
            previous = id;
            newest = Math.max(newest, id);
            if (known > 0 && id <= known && (context.recheckFrom() == null
                    || submission.submittedAt().isBefore(context.recheckFrom()))) {
                return new SubmissionPage(collected, String.valueOf(newest), false, false);
            }
            collected.add(submission);
        }
        if (!page.hasNext()) {
            return new SubmissionPage(collected, String.valueOf(newest), false, true);
        }
        if (collected.isEmpty() || previous <= 1) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "杭电下一页存在但无法确定分页边界");
        }
        return new SubmissionPage(collected, "scan:" + known + ":" + newest + ":" + (previous - 1),
                true, false);
    }

    record ParsedPage(List<FetchedSubmission> rows, boolean hasNext) { }

    static ParsedPage parseStatus(String html, String handle) {
        Document doc = Jsoup.parse(html);
        Element table = doc.select("table").stream().filter(t -> t.select("tr.table_header").stream()
                .anyMatch(r -> r.text().contains("Run ID") && r.text().contains("Submit Time")
                        && r.text().contains("Judge Status")))
                .reduce((outer, inner) -> inner).orElse(null);
        if (table == null) throw ProtectionPage.orElse(Platform.HDU, html,
                new ApiException(ErrorKind.PARSE_ERROR, "杭电提交表结构无法识别，保留旧记录"));
        List<FetchedSubmission> rows = new ArrayList<>();
        for (Element row : table.select("tr")) {
            Elements cells = row.children().select("td");
            if (cells.isEmpty() || !cells.first().text().matches("\\d+")) continue;
            try {
                if (cells.size() != 9) throw new IllegalArgumentException();
                if (!cells.get(8).text().equals(handle)) throw new IllegalArgumentException();
                String pid = cells.get(3).text();
                if (!pid.matches("\\d+") || cells.get(3).selectFirst("a[href*=showproblem.php]") == null)
                    throw new IllegalArgumentException();
                String verdict = cells.get(2).text();
                if (verdict.isBlank()) throw new IllegalArgumentException();
                FetchedProblem problem = new FetchedProblem(pid, "HDU " + pid,
                        "https://acm.hdu.edu.cn/showproblem.php?pid=" + pid,
                        null, null, "NONE", List.of(), false);
                rows.add(new FetchedSubmission(cells.get(0).text(), problem, verdict,
                        "Accepted".equals(verdict),
                        LocalDateTime.parse(cells.get(1).text(), TIME).atZone(TimeSupport.CAMPUS_ZONE).toInstant(),
                        "EXACT", cells.get(7).text(), null));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorKind.PARSE_ERROR, "杭电提交字段或账号不匹配，未将异常页面当作空历史");
            }
        }
        boolean hasNext = doc.select("a[href]").stream().anyMatch(a -> a.text().contains("Next Page")
                && a.attr("href").contains("status.php?"));
        return new ParsedPage(rows, hasNext);
    }

    private Map<String, String> loadStats(String user) {
        String html = http.get(Platform.HDU, USER_STATUS + URLEncoder.encode(user, StandardCharsets.UTF_8), Map.of(), null, GBK);
        Document doc = Jsoup.parse(html);
        Map<String, String> stats = new LinkedHashMap<>();
        for (Element row : doc.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() == 2) {
                String label = cells.get(0).text().trim();
                String value = cells.get(1).text().trim();
                if (!label.isEmpty()) {
                    stats.put(label, value);
                }
            }
        }
        return stats;
    }

    private Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.parseInt(digits);
    }
}
