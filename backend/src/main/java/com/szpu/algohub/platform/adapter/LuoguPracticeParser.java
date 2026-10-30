package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import org.jsoup.Jsoup;
import java.util.*;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Reads Luogu content-only JSON and embedded page data; never executes page scripts. */
public final class LuoguPracticeParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode payload(String body, String expectedUid) {
        JsonNode root;
        try {
            String value = body.strip();
            if (!value.startsWith("{")) {
                var script = Jsoup.parse(body).selectFirst("script#lentille-context");
                if (script != null) value = script.data();
                else {
                    var old = Pattern.compile("decodeURIComponent\\((\"(?:[^\"\\\\]|\\\\.)*\")\\)").matcher(body);
                    if (old.find()) value = URLDecoder.decode(JSON.readValue(old.group(1), String.class).replace("+", "%2B"), StandardCharsets.UTF_8);
                    else if (body.toLowerCase(Locale.ROOT).matches("(?s).*(challenge|captcha|spilopelia|turnstile).*"))
                        throw new ApiException(ErrorKind.RATE_LIMITED, "洛谷返回防护验证页，稍后重试；这不代表登录态已失效");
                    else throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷页面没有可解析的练习数据");
                }
            }
            root = JSON.readTree(value);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷练习数据解析失败"); }
        JsonNode data = root.has("data") ? root.path("data") : root.has("currentData") ? root.path("currentData") : root;
        String uid = data.path("user").path("uid").asText("");
        if (!uid.equals(expectedUid)) throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷返回的用户 UID 与绑定账号不一致或缺失");
        return data;
    }

    public static Map<java.time.LocalDate, Integer> activity(String body, String expectedUid) {
        JsonNode counts = payload(body, expectedUid).path("dailyCounts");
        if (!counts.isObject()) throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷未返回每日活动数据");
        Map<java.time.LocalDate, Integer> result = new TreeMap<>();
        try {
            var fields = counts.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var date = java.time.LocalDate.parse(entry.getKey());
                var values = entry.getValue();
                if (!values.isArray() || values.size() != 2 || !values.get(0).isIntegralNumber()
                        || !values.get(0).canConvertToInt() || values.get(0).asInt() < 0)
                    throw new IllegalArgumentException();
                result.put(date, values.get(0).asInt());
            }
        } catch (RuntimeException e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷每日活动格式变化，未更新活动缓存");
        }
        return Map.copyOf(result);
    }

    public static TotalsSnapshot parse(String body, String expectedUid) {
        JsonNode data = payload(body, expectedUid);
        JsonNode passed = data.has("passed") ? data.path("passed") : data.path("passedProblems");
        if (!passed.isArray()) throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷未返回已通过题单，可能为隐私限制或结构变化");
        Map<String, FetchedProblem> unique = new LinkedHashMap<>();
        for (JsonNode p : passed) {
            String pid = p.path("pid").asText("");
            if (!pid.matches("[A-Za-z0-9_-]+") || !p.path("name").isTextual())
                throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷题目字段不完整，未更新统计");
            String difficulty = p.path("difficulty").isIntegralNumber() ? p.path("difficulty").asText() : null;
            Set<String> tags = new LinkedHashSet<>();
            if (p.path("tags").isArray()) for (JsonNode tag : p.path("tags")) {
                if (tag.isTextual() && !tag.asText().isBlank()) tags.add(tag.asText());
                else if (tag.path("name").isTextual()) tags.add(tag.path("name").asText());
            }
            unique.put(pid, new FetchedProblem(pid, p.path("name").asText(),
                    "https://www.luogu.com.cn/problem/" + pid, difficulty,
                    difficultyLabel(difficulty), "OFFICIAL", List.copyOf(tags), false));
        }
        JsonNode count = data.path("user").path("passedProblemCount");
        if (count.isIntegralNumber() && (!count.canConvertToInt() || count.asInt() < 0
                || count.asInt() > unique.size()))
            throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷题单数量与账号总量不一致，未更新不完整数据");
        String note = "公开练习页已通过题单；无提交时间；不是完整提交历史";
        if (count.isIntegralNumber() && count.asInt() < unique.size()) {
            note += "；公开题单比资料汇总多 " + (unique.size() - count.asInt()) + " 题，按逐题题单收录";
        }
        return new TotalsSnapshot(unique.size(), null, Map.of(),
                note, List.copyOf(unique.values()));
    }

    /**
     * 洛谷的 difficulty 是 0～8 的评级编号，官网显示的是「普及−」这类名字。
     * 存原始编号（difficulty_raw）之外，标签直接写官网的评级名：
     * 「洛谷难度 2」对同学没有意义，「普及−」才是他在源站看到的那个词。
     * 超出 0～8 的取值不猜，原样标出编号。
     */
    public static String difficultyLabel(String raw) {
        if (raw == null) {
            return null;
        }
        // 洛谷现行的九档评级名（官网 /_lfe/config 的 ProblemDifficulty），减号是全角「−」
        return switch (raw) {
            case "0" -> "暂无评定";
            case "1" -> "入门";
            case "2" -> "普及−";
            case "3" -> "普及";
            case "4" -> "普及+/提高−";
            case "5" -> "提高";
            case "6" -> "提高+/省选−";
            case "7" -> "省选/NOI−";
            case "8" -> "NOI/NOI+/CTS";
            default -> "洛谷难度 " + raw;
        };
    }
}
