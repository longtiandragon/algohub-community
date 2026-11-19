package com.szpu.algohub.weekly;

import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.szpu.algohub.weekly.WeeklySource.*;

final class AtCoderPractice {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxx");
    private record Submission(long id, long epoch, String index, String verdict, double score) {}

    static Row read(PlatformHttpClient http, Meta meta, String handle, String cookie,
                    List<Map<Long,Long>> virtualAnchors) {
        String base = meta.url() + "/submissions?f.User=" + URLEncoder.encode(handle, StandardCharsets.UTF_8) + "&lang=en";
        Map<String,String> taskPaths = new HashMap<>();
        meta.tasks().forEach(t -> taskPaths.put("/contests/" + meta.ref() + "/tasks/" + t.key(), t.index()));
        Map<Long,Submission> submissions = new TreeMap<>();
        long end = 0, duration = 0;
        for (int page = 1; ; page++) {
            if (page > 500) throw invalid();
            var doc = Jsoup.parse(http.get(Platform.ATCODER, base + "&page=" + page, Map.of(), cookie));
            if (doc.selectFirst("input[type=password]") != null) throw atcoderLoginRequired();
            var times = doc.select(".contest-duration time");
            if (times.size() != 2) throw invalid();
            end = epoch(times.get(1));
            duration = end - epoch(times.getFirst());
            if (duration <= 0) throw invalid();
            var entries = doc.select("table tbody tr");
            if (entries.isEmpty() && doc.getElementsMatchingOwnText("^No Submissions$").isEmpty()) throw invalid();
            for (Element tr : entries) {
                var score = tr.selectFirst("td.submission-score[data-id]");
                var task = tr.selectFirst("a[href*=/tasks/]");
                var user = tr.selectFirst("a[href^=/users/]");
                var verdict = tr.selectFirst("td .label");
                if (score == null || task == null || user == null || verdict == null ||
                        !user.attr("href").equalsIgnoreCase("/users/" + handle)) throw invalid();
                String index = taskPaths.get(task.attr("href"));
                if (index == null) throw invalid();
                String status = verdict.text();
                if (!Set.of("AC","WA","TLE","MLE","RE","OLE","CE","IE").contains(status) || status.equals("IE")) continue;
                long id = Long.parseLong(score.attr("data-id"));
                submissions.put(id, new Submission(id, epoch(tr.selectFirst("time")), index, status, Double.parseDouble(score.text())));
            }
            boolean hasMore = doc.select("ul.pagination a").stream().map(Element::text)
                    .filter(label -> label.matches("[0-9]+")) .mapToInt(Integer::parseInt).max().orElse(page) > page;
            if (!hasMore) break;
        }
        // The VP scoreboard gives relative times. Matching its scored submission ID to the
        // history gives the absolute start and lets us exclude even unsuccessful in-VP attempts.
        List<Long> starts = new ArrayList<>();
        for (var anchors : virtualAnchors) {
            Set<Long> possible = new HashSet<>();
            anchors.forEach((id, elapsed) -> {
                var sub = submissions.get(id);
                if (sub != null) possible.add(sub.epoch() - elapsed);
            });
            // A VP whose window cannot be identified must not be counted as practice.
            if (possible.size() != 1) return null;
            starts.add(possible.iterator().next());
        }
        Map<String,Cell> cells = new LinkedHashMap<>();
        for (Submission sub : submissions.values()) {
            if (sub.epoch() < end) continue;
            boolean timed = false;
            for (long start : starts) {
                if (sub.epoch() >= start && sub.epoch() < start + duration) { timed = true; break; }
            }
            if (timed) continue;
            Cell previous = cells.get(sub.index());
            if (previous != null && previous.solved()) continue;
            boolean ac = sub.verdict().equals("AC");
            int wrong = previous == null ? 0 : previous.wrong();
            double points = Math.max(sub.score(), previous == null ? 0 : previous.score());
            cells.put(sub.index(), new Cell(ac, wrong + (ac ? 0 : 1), points, null));
        }
        if (cells.isEmpty()) return null;
        return new Row("PRACTICE", cells, cells.values().stream().mapToDouble(Cell::score).sum(), 0);
    }

    private static long epoch(Element time) {
        if (time == null) throw invalid();
        return OffsetDateTime.parse(time.text(), DATE).toEpochSecond();
    }
}
