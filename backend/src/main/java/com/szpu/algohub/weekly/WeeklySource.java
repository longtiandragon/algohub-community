package com.szpu.algohub.weekly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import com.szpu.algohub.platform.adapter.ContestCategoryRules;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.ConcurrentLruCache;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.*;
import java.util.regex.Pattern;

/** Contest feeds. Cached boards contain source identities only; campus membership is applied by the caller. */
@Component
public class WeeklySource {
    public record Task(String index, String key) {}
    public record Meta(String platform, String ref, String title, String category, String url, List<Task> tasks, String scoring) {
        public Meta(String platform,String ref,String title,String category,String url,List<Task> tasks) {
            this(platform,ref,title,category,url,tasks,null);
        }
    }
    public record Cell(boolean solved, Integer wrong, Double score, Long seconds) {}
    public record Row(String kind, Map<String,Cell> cells, Double score, long penalty, Integer sourceRank) {
        public Row(String kind, Map<String,Cell> cells, Double score, long penalty) {
            this(kind, cells, score, penalty, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CF_API = "https://codeforces.com/api/contest.";
    private static final String NC = "https://ac.nowcoder.com";
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 500;
    private final PlatformHttpClient http;

    private record Contest(Platform platform, String ref, String url) {
        static Contest parse(String input) {
            try {
                URI uri = URI.create(input == null ? "" : input.trim());
                String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                        (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
                Platform platform;
                String path;
                switch (host) {
                    case "codeforces.com" -> { platform = Platform.CODEFORCES; path = "/contest/([1-9][0-9]{0,9})"; }
                    case "atcoder.jp" -> { platform = Platform.ATCODER; path = "/contests/([a-zA-Z0-9_-]{1,64})"; }
                    case "ac.nowcoder.com" -> { platform = Platform.NOWCODER; path = "/acm/contest/([1-9][0-9]{0,9})"; }
                    default -> throw new IllegalArgumentException();
                }
                var match = Pattern.compile(path + "/?").matcher(uri.getRawPath());
                if (match.matches()) return new Contest(platform, match.group(1),
                        "https://" + host + uri.getRawPath().replaceFirst("/$", ""));
            } catch (IllegalArgumentException ignored) {
                // Present the same validation error for malformed and unsupported links.
            }
            throw ApiException.validation("请粘贴 Codeforces、AtCoder 或牛客的比赛主页链接");
        }
    }

    private record CacheKey(Contest contest, long bucket) {
        static CacheKey of(Contest contest) { return new CacheKey(contest, System.currentTimeMillis() / 600_000); }
    }
    private record Board(Meta meta, String scoring, long endMillis, Map<String,Row> entrants,
                         Map<String,Map<String,Cell>> supplements) {}
    private final ConcurrentLruCache<CacheKey,Board> boards =
            new ConcurrentLruCache<>(4, key -> loadBoard(key.contest()));
    private final ConcurrentLruCache<CacheKey,Meta> previews =
            new ConcurrentLruCache<>(32, this::loadPreview);

    private static final String ATCODER_COOKIE_ENV = "ALGOHUB_ATCODER_STANDINGS_COOKIE_FILE";
    private final String atcoderCookiePath;
    private final ConcurrentLruCache<CacheKey,AtCoderBoard> atcoderBoards =
            new ConcurrentLruCache<>(2, key -> atcoderBoard(key.contest()));

    @org.springframework.beans.factory.annotation.Autowired
    public WeeklySource(PlatformHttpClient http) {
        this(http, System.getenv(ATCODER_COOKIE_ENV));
    }

    WeeklySource(PlatformHttpClient http, String atcoderCookiePath) {
        this.http = http;
        this.atcoderCookiePath = atcoderCookiePath;
    }

    public boolean atcoderStandingsEnabled() {
        return atcoderCookiePath != null && !atcoderCookiePath.isBlank();
    }

    public Meta preview(String url) { return previews.get(CacheKey.of(Contest.parse(url))); }

    private Meta loadPreview(CacheKey key) {
        Contest contest = key.contest();
        return switch (contest.platform()) {
            case CODEFORCES -> boards.get(key).meta();
            case NOWCODER -> nowcoderMeta(contest);
            case ATCODER -> atcoderMeta(contest);
            default -> throw new IllegalStateException();
        };
    }

    public List<Row> results(Meta meta, String handle, String uid) {
        Contest contest = Contest.parse(meta.url());
        if (contest.platform() == Platform.ATCODER) {
            if (!atcoderStandingsEnabled()) return List.of();
            AtCoderBoard board = atcoderBoards.get(CacheKey.of(contest));
            String key = handle.toLowerCase(Locale.ROOT);
            var timed = board.rows().getOrDefault(key, List.of());
            var anchors = board.virtualAnchors().getOrDefault(key, List.of());
            var practice = AtCoderPractice.read(http, meta, handle, atcoderCookie(), anchors);
            if (practice == null) return List.copyOf(timed);
            var all = new ArrayList<>(timed);
            all.add(practice);
            return List.copyOf(all);
        }
        Board board = boards.get(CacheKey.of(contest));
        return contest.platform() == Platform.CODEFORCES
                ? codeforcesResults(contest, board, handle)
                : nowcoderResults(contest, board, uid);
    }

    private Board loadBoard(Contest contest) {
        return contest.platform() == Platform.CODEFORCES ? codeforcesBoard(contest) : nowcoderBoard(contest);
    }

    private JsonNode json(Platform platform, String url) {
        String body = http.get(platform, url);
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null) throw invalid();
            if (platform == Platform.CODEFORCES && "OK".equals(root.path("status").asText())
                    && root.hasNonNull("result")) return root.get("result");
            if (platform == Platform.NOWCODER && root.path("code").asInt(-1) == 0
                    && root.hasNonNull("data")) return root.get("data");
        } catch (JsonProcessingException ignored) {
            // Never include response bodies in errors.
        }
        throw invalid();
    }

    static ApiException invalid() {
        return new ApiException(ErrorKind.PARSE_ERROR, "源站尚未公开完整的比赛数据，或返回格式已变更");
    }

    private static JsonNode array(JsonNode root, String field) {
        JsonNode values = root.path(field);
        if (!values.isArray()) throw invalid();
        return values;
    }

    private static String required(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) throw invalid();
        return value;
    }

    private static Meta metadata(Contest contest, String title, String category, List<Task> tasks) {
        return metadata(contest,title,category,tasks,null);
    }

    private static Meta metadata(Contest contest, String title, String category, List<Task> tasks,String scoring) {
        if (title.isBlank() || tasks.isEmpty() || tasks.stream().map(Task::index).distinct().count() != tasks.size()
                || tasks.stream().map(Task::key).distinct().count() != tasks.size()) throw invalid();
        return new Meta(contest.platform().name(), contest.ref(), title, category, contest.url(), List.copyOf(tasks), scoring);
    }

    private Meta atcoderMeta(Contest contest) {
        var page = Jsoup.parse(http.get(Platform.ATCODER, contest.url() + "/tasks?lang=en"));
        Element title = page.selectFirst("a.contest-title");
        if (title == null || !title.attr("href").equals("/contests/" + contest.ref())) throw invalid();
        List<Task> tasks = new ArrayList<>();
        String prefix = "/contests/" + contest.ref() + "/tasks/";
        for (Element link : page.select("table tbody tr td:first-child a")) {
            if (link.attr("href").startsWith(prefix)) {
                tasks.add(new Task(link.text(), link.attr("href").substring(prefix.length())));
            }
        }
        return metadata(contest, title.text(), ContestCategoryRules.classifyAtCoder(contest.ref(), title.text()), tasks);
    }

    private String atcoderCookie() {
        try {
            Path file = Path.of(atcoderCookiePath);
            if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 16_384)
                throw atcoderLoginRequired();
            String value = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!value.matches("[!-~][ -~]*") || value.startsWith("{") || !value.contains("="))
                throw atcoderLoginRequired();
            return value;
        } catch (IOException | InvalidPathException ignored) {
            throw atcoderLoginRequired();
        }
    }

    static ApiException atcoderLoginRequired() {
        return new ApiException(ErrorKind.CREDENTIAL_INVALID, "AtCoder 榜单登录态不可用，请管理员重新授权");
    }

    private record AtCoderBoard(Map<String,List<Row>> rows, Map<String,List<Map<Long,Long>>> virtualAnchors) {}

    private AtCoderBoard atcoderBoard(Contest contest) {
        String cookie = atcoderCookie();
        AtCoderBoard board = new AtCoderBoard(new HashMap<>(), new HashMap<>());
        readAtcoderBoard(contest, cookie, false, board);
        readAtcoderBoard(contest, cookie, true, board);
        return board;
    }

    // Official scorer and ghost convention: img.atcoder.jp/public/16d83a8/js/standings.js
    private void readAtcoderBoard(Contest contest, String cookie, boolean virtual, AtCoderBoard standings) {
        String url = contest.url() + (virtual ? "/standings/virtual/json" : "/standings/json");
        String body = http.get(Platform.ATCODER, url, Map.of(), cookie);
        if (body.stripLeading().startsWith("<")) {
            var page = Jsoup.parse(body);
            if (page.selectFirst("input[type=password],form[action*=/login]") != null) throw atcoderLoginRequired();
            throw invalid();
        }
        JsonNode board;
        try {
            board = JSON.readTree(body);
        } catch (JsonProcessingException ignored) {
            throw invalid();
        }
        if (board == null) throw invalid();
        Map<String,String> taskIndices = new HashMap<>();
        for (JsonNode task : array(board, "TaskInfo")) {
            taskIndices.put(required(task,"TaskScreenName"),required(task,"Assignment"));
        }
        if (taskIndices.isEmpty()) throw invalid();
        for (JsonNode entry : array(board, "StandingsData")) {
            if (entry.path("IsTeam").asBoolean() || entry.path("UserIsDeleted").asBoolean()) continue;
            if (virtual) {
                // Official participants may also appear as ghost rows in the VP table.
                // -2 identifies a ghost; the non-ghost filter also accepts the negative -1 marker.
                JsonNode elapsed = entry.path("Additional").path("standings.virtualElapsed");
                if (!elapsed.isIntegralNumber() || elapsed.asLong() < -1) continue;
            }
            JsonNode total = entry.path("TotalResult");
            if (total.path("Count").asInt() <= 0) continue;
            Map<String,Cell> cells = new LinkedHashMap<>();
            Map<Long,Long> anchors = new HashMap<>();
            JsonNode results = entry.path("TaskResults");
            if (!results.isObject() || !total.path("Score").isNumber() || !total.path("Elapsed").isIntegralNumber()) throw invalid();
            results.fields().forEachRemaining(task -> {
                String index = taskIndices.get(task.getKey());
                if (index == null) throw invalid();
                JsonNode result = task.getValue();
                if (result.path("Count").asInt() <= 0) return;
                if (!result.path("Status").isIntegralNumber() || !result.path("Score").isNumber()) throw invalid();
                double score = result.get("Score").asDouble() / 100;
                JsonNode wrong = result.path(score == 0 ? "Failure" : "Penalty");
                JsonNode time = result.path("Elapsed");
                boolean solved = result.get("Status").asInt() == 1 && score > 0;
                if (virtual && score > 0 && time.isIntegralNumber() && result.path("SubmissionID").asLong() > 0) {
                    anchors.put(result.get("SubmissionID").asLong(), time.asLong() / 1_000_000_000L);
                }
                cells.put(index,new Cell(solved, wrong.isIntegralNumber() ? wrong.intValue() : null, score,
                        score > 0 && time.isIntegralNumber() ? time.asLong() / 1_000_000_000L : null));
            });
            String handle = required(entry, "UserScreenName").toLowerCase(Locale.ROOT);
            if (virtual) standings.virtualAnchors().computeIfAbsent(handle, unused -> new ArrayList<>()).add(anchors);
            standings.rows().computeIfAbsent(handle, unused -> new ArrayList<>()).add(new Row(virtual ? "VIRTUAL" : "OFFICIAL",
                    cells,total.get("Score").asDouble() / 100,total.get("Elapsed").asLong() / 1_000_000_000L,rank(entry,"Rank")));
        }
    }

    private Board codeforcesBoard(Contest contest) {
        // The CF API accepts only contestId on anonymous public standings requests.
        JsonNode data = json(Platform.CODEFORCES, CF_API + "standings?contestId=" + contest.ref());
        JsonNode info = data.path("contest");
        if (!contest.ref().equals(info.path("id").asText())) throw invalid();
        String scoring = required(info, "type");
        String title = required(info, "name");
        List<Task> tasks = new ArrayList<>();
        for (var problem : array(data, "problems")) {
            String index = required(problem, "index");
            tasks.add(new Task(index, contest.ref() + index));
        }
        Map<String,Row> entrants = new HashMap<>();
        for (var row : array(data, "rows")) {
            JsonNode party = row.path("party");
            String participantType = party.path("participantType").asText();
            if (!Set.of("CONTESTANT", "OUT_OF_COMPETITION").contains(participantType) || party.path("members").size() != 1) continue;
            String kind = participantType.equals("CONTESTANT") ? "OFFICIAL" : "STAR";
            Map<String,Cell> cells = new LinkedHashMap<>();
            JsonNode results = array(row, "problemResults");
            if (results.size() != tasks.size()) throw invalid();
            for (int i = 0; i < tasks.size(); i++) {
                JsonNode result = results.get(i);
                double points = result.path("points").asDouble();
                int wrong = result.path("rejectedAttemptCount").asInt();
                if (points > 0 || wrong > 0) {
                    // Partial IOI scores do not prove AC; confirmed verdicts are merged below.
                    cells.put(tasks.get(i).index(), new Cell(points > 0 && !"IOI".equals(scoring), wrong,
                            points, result.has("bestSubmissionTimeSeconds") ? result.get("bestSubmissionTimeSeconds").asLong() : null));
                }
            }
            String name = required(party.get("members").get(0), "handle").toLowerCase(Locale.ROOT);
            entrants.put(name, new Row(kind, cells, row.path("points").asDouble(), row.path("penalty").asLong() * 60,rank(row,"rank")));
        }
        return new Board(metadata(contest,title,ContestCategoryRules.classifyCodeforces(title),tasks,scoring),scoring,0,entrants,Map.of());
    }

    private static Integer rank(JsonNode row, String field) {
        JsonNode rank = row.path(field);
        return rank.isIntegralNumber() && rank.canConvertToInt() && rank.intValue() > 0 ? rank.intValue() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Attempt(long id, String kind, String session, String index, boolean ac, long seconds, Double points) {}

    private List<Row> codeforcesResults(Contest contest, Board board, String handle) {
        List<Attempt> attempts = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) throw invalid();
            JsonNode subs = json(Platform.CODEFORCES, CF_API + "status?contestId=" + contest.ref()
                    + "&handle=" + encode(handle) + "&from=" + (page * PAGE_SIZE + 1) + "&count=" + PAGE_SIZE);
            if (!subs.isArray()) throw invalid();
            for (JsonNode sub : subs) {
                JsonNode party = sub.path("author");
                if (party.path("members").size() != 1
                        || !handle.equalsIgnoreCase(party.get("members").get(0).path("handle").asText())) continue;
                String kind = switch (party.path("participantType").asText()) {
                    case "CONTESTANT" -> "OFFICIAL";
                    case "OUT_OF_COMPETITION" -> "STAR";
                    case "VIRTUAL" -> "VIRTUAL";
                    case "PRACTICE" -> "PRACTICE";
                    default -> "";
                };
                String verdict = sub.path("verdict").asText("");
                if (kind.isEmpty() || Set.of("", "TESTING", "SKIPPED", "COMPILATION_ERROR", "CHALLENGED", "FAILED").contains(verdict)) continue;
                long id = sub.path("id").asLong();
                if (!seen.add(id)) continue;
                String session = kind.equals("VIRTUAL") ? required(party, "startTimeSeconds") : kind;
                attempts.add(new Attempt(id,kind,session,required(sub.path("problem"),"index"),verdict.equals("OK"),
                        sub.path("relativeTimeSeconds").asLong(),sub.has("points") ? sub.get("points").asDouble() : null));
            }
            if (subs.size() < PAGE_SIZE) break;
        }
        Map<String,Row> summaries = summarize(attempts, board.scoring());
        Row official = board.entrants().get(handle.toLowerCase(Locale.ROOT));
        if (official != null) {
            Map<String,Cell> cells = new LinkedHashMap<>(official.cells());
            Row actual = summaries.get(official.kind());
            if (actual != null) actual.cells().forEach((index, cell) -> {
                var source = cells.get(index);
                if (source == null) cells.put(index, cell);
                else if (cell.solved() && !source.solved()) cells.put(index, new Cell(true, source.wrong(), source.score(), source.seconds()));
            });
            summaries.put(official.kind(), new Row(official.kind(), cells, official.score(), official.penalty(), official.sourceRank()));
        }
        return List.copyOf(summaries.values());
    }

    // Each VP start is a distinct performance. A later practice solve never changes a contest cell.
    private static Map<String,Row> summarize(List<Attempt> attempts, String scoring) {
        Map<String,Map<String,Cell>> sessions = new LinkedHashMap<>();
        Map<String,String> kinds = new HashMap<>();
        attempts.sort(Comparator.comparingLong(Attempt::id));
        for (var attempt : attempts) {
            Map<String,Cell> cells = sessions.computeIfAbsent(attempt.session(), unused -> new LinkedHashMap<>());
            kinds.put(attempt.session(), attempt.kind());
            Cell prior = cells.get(attempt.index());
            if (prior != null && prior.solved()) continue;
            int wrong = prior == null ? 0 : prior.wrong();
            Long elapsed = attempt.ac() && !attempt.kind().equals("PRACTICE") ? attempt.seconds() : null;
            cells.put(attempt.index(), new Cell(attempt.ac(), wrong + (attempt.ac() ? 0 : 1), attempt.points(), elapsed));
        }
        Map<String,Row> rows = new LinkedHashMap<>();
        sessions.forEach((session,cells) -> {
            long penalty = 0;
            double sum = 0;
            boolean known = true;
            for (Cell cell : cells.values()) {
                if (!cell.solved()) continue;
                if ("ICPC".equals(scoring)) {
                    sum++;
                    // CF ICPC-format rounds charge ten minutes per rejected pre-AC submission.
                    if (cell.seconds() != null) penalty += (cell.seconds() / 60 + 10L * cell.wrong()) * 60;
                } else if (cell.score() != null) sum += cell.score();
                else known = false;
            }
            rows.put(session,new Row(kinds.get(session),cells,known ? sum : null,penalty));
        });
        return rows;
    }

    private Meta nowcoderMeta(Contest contest) {
        var page = Jsoup.parse(http.get(Platform.NOWCODER, contest.url()));
        String title = page.title().split("_ACM/|_牛客竞赛OJ", 2)[0].trim();
        if (title.isEmpty() || !page.title().contains("牛客")) throw invalid();
        Element desc = page.selectFirst("meta[name=description]");
        String category = nowcoderCategory(title, desc == null ? "" : desc.attr("content"));
        JsonNode data = json(Platform.NOWCODER, NC + "/acm/contest/problem-list?id=" + contest.ref());
        List<Task> tasks = new ArrayList<>();
        for (var problem : array(data,"data")) tasks.add(new Task(required(problem,"index"),required(problem,"problemId")));
        return metadata(contest,title,category,tasks);
    }

    private static String nowcoderCategory(String title, String description) {
        List<String> series = List.of("小白月赛","基础集训营","多校训练营","练习赛","挑战赛","周赛","ICPC","CCPC");
        for (String text : List.of(title, description)) {
            for (String name : series) if (text.toUpperCase(Locale.ROOT).contains(name)) return name;
        }
        return title;
    }

    private Board nowcoderBoard(Contest contest) {
        Meta meta = previews.get(CacheKey.of(contest));
        Map<String,String> indices = new HashMap<>();
        meta.tasks().forEach(task -> indices.put(task.key(), task.index()));
        Map<String,Row> entrants = new HashMap<>();
        Map<String,Map<String,Cell>> supplements = new HashMap<>();
        long end;
        String scoring;
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES) throw invalid();
            JsonNode data = json(Platform.NOWCODER, NC + "/acm-heavy/acm/contest/real-time-rank-data?id=" + contest.ref()
                    + "&rankScope=ALL&page=" + page + "&pageSize=" + PAGE_SIZE + "&limit=" + (PAGE_SIZE * MAX_PAGES));
            JsonNode info = data.path("basicInfo");
            if (!contest.ref().equals(info.path("contestId").asText()) || info.path("pageCurrent").asInt() != page) throw invalid();
            end = info.path("contestEndTime").asLong();
            long begin = info.path("contestBeginTime").asLong();
            if (end <= begin || begin <= 0) throw invalid();
            scoring = required(info, "rankType");
            for (var entry : array(data, "rankData")) {
                if (entry.path("team").asBoolean() || entry.path("violation").asBoolean()) continue;
                String kind = required(entry, "participationType");
                if (!Set.of("OFFICIAL","STAR","VIRTUAL").contains(kind)) continue;
                Map<String,Cell> cells = new LinkedHashMap<>();
                Map<String,Cell> postContest = new LinkedHashMap<>();
                for (var problem : array(entry,"scoreList")) {
                    String index = indices.get(required(problem, "problemId"));
                    if (index == null) throw invalid();
                    double postScore = problem.path("postContestScore").asDouble();
                    boolean postAccepted = problem.path("postContestAccepted").asBoolean() ||
                            (problem.path("fullScore").asDouble() > 0 && postScore >= problem.get("fullScore").asDouble());
                    if (postAccepted || postScore > 0) {
                        postContest.put(index, new Cell(postAccepted, null, postScore > 0 ? postScore : null, null));
                    }
                    if (!problem.path("submit").asBoolean()) continue;
                    boolean ac = problem.path("accepted").asBoolean();
                    long at = problem.path("acceptedTime").asLong();
                    cells.put(index,new Cell(ac,problem.path("failedCount").asInt(),
                            scoring.equals("ICPC") ? null : problem.path("score").asDouble(),
                            ac && at >= begin && !kind.equals("VIRTUAL") ? (at - begin) / 1000 : null));
                }
                double score = scoring.equals("ICPC") ? entry.path("acceptedCount").asInt() : entry.path("totalScore").asDouble();
                String uid = required(entry, "uid");
                entrants.put(uid,new Row(kind,cells,score,entry.path("penaltyTime").asLong()/1000,rank(entry,"ranking")));
                if (!postContest.isEmpty()) supplements.put(uid, postContest);
            }
            if (!info.has("pageCount")) throw invalid();
            if (page >= info.get("pageCount").asInt()) break;
        }
        return new Board(meta,scoring,end,entrants,supplements);
    }

    private List<Row> nowcoderResults(Contest contest, Board board, String uid) {
        if (uid == null || !uid.matches("[0-9]+")) throw ApiException.validation("牛客账号缺少有效 UID");
        Row official = board.entrants().get(uid);
        List<Row> rows = new ArrayList<>();
        if (official != null) rows.add(official);
        if (System.currentTimeMillis() < board.endMillis()) return rows;
        var postContest = board.supplements().getOrDefault(uid,Map.of());
        // A completed official row already accounts for every task in the assignment.
        if (official != null && postContest.isEmpty()
                && official.cells().values().stream().filter(Cell::solved).count() == board.meta().tasks().size()) return rows;
        List<Attempt> after = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES) throw invalid();
            JsonNode data = json(Platform.NOWCODER, NC + "/acm/contest/status-list?id=" + contest.ref()
                    + "&searchUserName=" + uid + "&page=" + page + "&pageSize=50");
            JsonNode info = data.path("basicInfo");
            if (!contest.ref().equals(info.path("contestId").asText()) || !info.has("pageCount")) throw invalid();
            boolean beforeEnd = false;
            for (var sub : array(data,"data")) {
                if (!uid.equals(sub.path("userId").asText()) || sub.path("isTeam").asBoolean()) continue;
                long at = sub.path("submitTime").asLong();
                if (at <= 0) throw invalid();
                if (at < board.endMillis()) { beforeEnd = true; continue; }
                if (sub.path("isVirtual").asBoolean() || "VIRTUAL".equals(sub.path("participationType").asText())) continue;
                long id = sub.path("submissionId").asLong();
                String verdict = required(sub,"statusMessage");
                if (!seen.add(id) || !NOWCODER_RESULTS.contains(verdict)) continue;
                boolean accepted = verdict.equals("答案正确") || verdict.equals("Accepted");
                after.add(new Attempt(id,"PRACTICE","PRACTICE",required(sub,"index"),accepted,0,
                        sub.has("score") ? sub.get("score").asDouble() : null));
            }
            if (beforeEnd || page >= info.get("pageCount").asInt()) break;
        }
        Row practice = summarize(after, board.scoring()).get("PRACTICE");
        if (practice != null || !postContest.isEmpty()) {
            Map<String,Cell> cells = new LinkedHashMap<>(postContest);
            if (practice != null) practice.cells().forEach((index,fromFeed) -> cells.merge(index,fromFeed,(fromBoard,reported) -> {
                // Some status feeds expose only recent submissions. Keep proven AC/points from the board.
                Integer wrong = fromBoard.solved() && !reported.solved() ? null : reported.wrong();
                return new Cell(fromBoard.solved() || reported.solved(), wrong,
                        fromBoard.score() == null ? reported.score() : fromBoard.score(), null);
            }));
            Double total = null;
            if ("ICPC".equals(board.scoring())) total = (double) cells.values().stream().filter(Cell::solved).count();
            else if (cells.values().stream().allMatch(c -> c.score() != null))
                total = cells.values().stream().mapToDouble(Cell::score).sum();
            rows.add(new Row("PRACTICE",cells,total,0));
        }
        return rows;
    }

    private static final Set<String> NOWCODER_RESULTS = Set.of("答案正确","答案错误","运行错误","运行超时","超时",
            "超过时间限制","内存超限","超过内存限制","段错误","格式错误","编译错误","部分正确",
            "Accepted","Wrong Answer","Time Limit Exceeded","Memory Limit Exceeded","Runtime Error");
}
