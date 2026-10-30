package com.szpu.algohub.platform.adapter;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.*;
import org.jsoup.Jsoup;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** QOJ AC 表格：保留评测 ID，首次 AC 由入库层跨页取最早时间。 */
final class QojRecordParser {
    /** Server collector returns allowlisted fields from the original HTML, before local timezone conversion. */
    static SubmissionPage parseCollected(com.fasterxml.jackson.databind.JsonNode data, String handle, int page) {
        try {
            var rows = data.path("rows");
            if (!rows.isArray() || rows.size() > 200 || !data.path("pagination").isArray()
                    || !data.path("sourceClock").asText().contains("(UTC+8)")) throw new IllegalArgumentException();
            boolean empty = rows.isEmpty() && data.path("empty").asBoolean(false);
            if (!empty && !Integer.toString(page).equals(data.path("current").asText()))
                throw new IllegalArgumentException(); // QOJ clamps an out-of-range request to its last page.
            List<FetchedSubmission> submissions = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var row : rows) {
                if (!handle.equals(row.path("submitter").asText())) throw new IllegalArgumentException();
                var sid = Pattern.compile("^/submission/(\\d+)$").matcher(row.path("id").asText());
                var pid = Pattern.compile("^/(?:contest/\\d+/)?problem/(\\d+)$").matcher(row.path("problem").asText());
                if (!sid.matches() || !pid.matches() || !ids.add(sid.group(1))) throw new IllegalArgumentException();
                String result = row.path("result").asText().trim();
                boolean accepted = result.matches("AC(?:\\s*[✓✔])?");
                if (result.isBlank()) throw new IllegalArgumentException();
                if (accepted && (new java.math.BigDecimal(row.path("score").asText()).compareTo(new java.math.BigDecimal("100")) != 0
                        || new java.math.BigDecimal(row.path("full").asText()).compareTo(new java.math.BigDecimal("100")) != 0))
                    throw new IllegalArgumentException();
                // An unknown date stops this page. Never substitute collection time or epoch.
                var at = LocalDateTime.parse(row.path("time").asText(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).toInstant(ZoneOffset.ofHours(8));
                if (at.isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException();
                var problem = new FetchedProblem(pid.group(1), row.path("title").asText(),
                        "https://qoj.ac/problem/" + pid.group(1), null, null, "NONE", List.of(), false);
                submissions.add(new FetchedSubmission(sid.group(1), problem, accepted ? "AC" : result,
                        accepted, at, "EXACT", row.path("language").asText(null), null));
            }
            boolean more = false;
            for (var value : data.path("pagination")) {
                java.net.URI uri = java.net.URI.create("https://qoj.ac").resolve(value.asText());
                if (!"https".equals(uri.getScheme()) || !"qoj.ac".equals(uri.getHost()) || uri.getPort() != -1
                        || !"/submissions".equals(uri.getPath()) || uri.getRawQuery() == null)
                    throw new IllegalArgumentException();
                Map<String,String> params = new HashMap<>();
                for (String pair : uri.getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length != 2 || params.put(parts[0], java.net.URLDecoder.decode(parts[1],
                            java.nio.charset.StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
                }
                if (!handle.equals(params.get("submitter")) || !Set.of("submitter", "page").containsAll(params.keySet()))
                    throw new IllegalArgumentException();
                int destination = Integer.parseInt(params.getOrDefault("page", "1"));
                if (destination < 1 || destination > 5000) throw new IllegalArgumentException();
                if (destination == page + 1) more = true;
            }
            if (rows.isEmpty() && (!empty || page != 1 || more || !data.path("pagination").isEmpty()))
                throw new IllegalArgumentException();
            return new SubmissionPage(submissions, more ? Integer.toString(page + 1) : null, more, false);
        } catch (Exception e) {
            throw QojServerClient.invalid();
        }
    }

    static SubmissionPage parse(String html, String handle, int page) {
        var doc = Jsoup.parse(html);
        if (doc.selectFirst("input[type=password]") != null || doc.title().toLowerCase(Locale.ROOT).contains("login")
                || doc.text().toLowerCase(Locale.ROOT).contains("must now be logged in to view submissions"))
            throw new ApiException(ErrorKind.CREDENTIAL_INVALID, "QOJ 提交列表要求重新登录");
        try {
            var table = doc.select("table").stream().filter(t -> t.select("th").eachText().containsAll(
                    List.of("ID", "Problem", "Submitter", "Result", "Submit time"))).findFirst().orElseThrow();
            var headers = table.select("thead th").eachText();
            if (headers.isEmpty()) headers = table.select("tr").first().select("th").eachText();
            List<FetchedSubmission> submissions = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (var row : table.select("tr")) {
                var cells = row.select("td");
                if (cells.isEmpty()) continue;
                if (cells.size() != headers.size()) throw new IllegalArgumentException();
                var submitter = cells.get(headers.indexOf("Submitter")).selectFirst(".uoj-username");
                if (submitter == null || !handle.equals(submitter.text().trim())) throw new IllegalArgumentException();
                var score = cells.get(headers.indexOf("Result")).selectFirst(".uoj-score[data-score][data-full]");
                if (score == null || new java.math.BigDecimal(score.attr("data-score")).compareTo(new java.math.BigDecimal("100")) != 0
                        || new java.math.BigDecimal(score.attr("data-full")).compareTo(new java.math.BigDecimal("100")) != 0)
                    throw new IllegalArgumentException();
                var sub = cells.get(headers.indexOf("ID")).selectFirst("a[href]");
                var problem = cells.get(headers.indexOf("Problem")).selectFirst("a[href]");
                var sid = Pattern.compile("^/submission/(\\d+)$").matcher(sub.attr("href"));
                var pid = Pattern.compile("^/(?:contest/\\d+/)?problem/(\\d+)$").matcher(problem.attr("href"));
                if (!sid.matches() || !pid.matches() || !seen.add(sid.group(1))) throw new IllegalArgumentException();
                var time = LocalDateTime.parse(cells.get(headers.indexOf("Submit time")).text().trim(),
                        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT))
                        .toInstant(ZoneOffset.ofHours(8));
                var p = new FetchedProblem(pid.group(1), problem.text(), "https://qoj.ac/problem/" + pid.group(1),
                        null, null, "NONE", List.of(), false);
                submissions.add(new FetchedSubmission(sid.group(1), p, "AC", true, time, "EXACT",
                        headers.contains("Language") ? cells.get(headers.indexOf("Language")).text() : null, null));
            }
            boolean more = doc.select(".pagination a[href]").stream().anyMatch(a ->
                    Pattern.compile("(?:[?&])page=" + (page + 1) + "(?:&|$)").matcher(a.attr("href")).find());
            if (submissions.isEmpty() && (page != 1 || more || !doc.text().toLowerCase(Locale.ROOT).matches("(?s).*(no submissions|no records|暂无记录|没有提交).*")))
                throw new IllegalArgumentException();
            return new SubmissionPage(submissions, more ? String.valueOf(page + 1) : null, more, !more);
        } catch (Exception e) {
            throw ProtectionPage.orElse(com.szpu.algohub.platform.Platform.QOJ, html,
                    new ApiException(ErrorKind.PARSE_ERROR, "QOJ AC 表格结构、账号或记录字段不符，保留已有数据"));
        }
    }
}
