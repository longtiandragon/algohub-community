package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.*;
import org.jsoup.Jsoup;
import java.time.Instant;
import java.util.*;

/** AC 列表仍保留每个评测 ID；入库层按账号和题目取最早 AC，不能按页面首条去重。 */
public final class LuoguRecordParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static SubmissionPage parse(String body, String uid, int page) {
        return parse(body, uid, page, null);
    }

    public static SubmissionPage parse(String body, String uid, int page, Instant recheckFrom) {
        try {
            String json = body.strip();
            if (!json.startsWith("{")) {
                var script = Jsoup.parse(body).selectFirst("script#lentille-context");
                if (script == null) throw new IllegalArgumentException();
                json = script.data();
            }
            JsonNode root = JSON.readTree(json);
            JsonNode data = root.has("data") ? root.path("data") : root.path("currentData");
            JsonNode records = data.path("records");
            JsonNode rows = records.path("result");
            if (!rows.isArray() || !records.path("count").isIntegralNumber()
                    || !records.path("count").canConvertToInt()
                    || !records.path("perPage").isIntegralNumber()
                    || !records.path("perPage").canConvertToInt()) throw new IllegalArgumentException();
            int count = records.path("count").intValue(), size = records.path("perPage").intValue();
            if (count < 0 || size < 1 || size > 1000 || page < 1 || rows.size() > size
                    || (rows.isEmpty() && (long)(page - 1) * size < count)) throw new IllegalArgumentException();
            long offset = (long) (page - 1) * size;
            // 短页或越界页不能证明历史已读完，否则会漏掉更早的首次 AC。
            if ((count == 0 && page != 1) || (count > 0 && offset >= count)
                    || rows.size() != Math.min((long) size, count - offset)) throw new IllegalArgumentException();
            List<FetchedSubmission> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode row : rows) {
                // 页面可能忽略姓名筛选；绝不把全站记录或别人的记录计到绑定账号。
                if (!uid.equals(row.path("user").path("uid").asText()))
                    throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷评测记录 UID 与绑定账号不一致，未入库");
                if (!row.path("status").isIntegralNumber() || !row.path("status").canConvertToInt())
                    throw new IllegalArgumentException();
                if (row.path("status").intValue() != 12) continue;
                String id = row.path("id").asText(), pid = row.path("problem").path("pid").asText();
                if (!id.matches("[1-9][0-9]*") || !ids.add(id) || !pid.matches("[A-Za-z0-9_-]+")
                        || !row.path("submitTime").isIntegralNumber() || !row.path("submitTime").canConvertToLong()
                        || row.path("submitTime").longValue() <= 0)
                    throw new IllegalArgumentException();
                JsonNode p = row.path("problem");
                String difficulty = p.path("difficulty").isIntegralNumber() ? p.path("difficulty").asText() : null;
                var problem = new FetchedProblem(pid, p.path("name").asText(pid),
                        "https://www.luogu.com.cn/problem/" + pid, difficulty,
                        LuoguPracticeParser.difficultyLabel(difficulty), "OFFICIAL", List.of(), false);
                result.add(new FetchedSubmission(id, problem, "AC", true,
                        Instant.ofEpochSecond(row.path("submitTime").longValue()), "EXACT",
                        row.path("language").asText(null), row.path("contest").path("id").asText(null)));
            }
            boolean more = (long) page * size < count;
            // 仅已完整回填的调用方提供回查边界。保留整页数据，遇到乱序不提前结束。
            boolean descending = result.size() == rows.size();
            for (int i = 1; i < result.size(); i++) {
                if (result.get(i).submittedAt().isAfter(result.get(i - 1).submittedAt())) descending = false;
            }
            if (more && recheckFrom != null && descending && !result.isEmpty()
                    && result.get(result.size() - 1).submittedAt().isBefore(recheckFrom)) {
                return new SubmissionPage(result, null, false, false);
            }
            return new SubmissionPage(result, more ? String.valueOf(page + 1) : null, more, !more);
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw new ApiException(ErrorKind.PARSE_ERROR, "洛谷未返回有效 AC 评测分页，保留已有题目与时间");
        }
    }
}
