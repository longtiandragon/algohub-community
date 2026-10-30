package com.szpu.algohub.demo;

import static com.szpu.algohub.demo.DemoCatalog.ATCODER_TITLES;
import static com.szpu.algohub.demo.DemoCatalog.CF_TAGS_EASY;
import static com.szpu.algohub.demo.DemoCatalog.CF_TAGS_HARD;
import static com.szpu.algohub.demo.DemoCatalog.CF_TAGS_MID;
import static com.szpu.algohub.demo.DemoCatalog.CF_TAGS_UNMAPPED;
import static com.szpu.algohub.demo.DemoCatalog.CF_TITLES;
import static com.szpu.algohub.demo.DemoCatalog.CLASSES_2026;
import static com.szpu.algohub.demo.DemoCatalog.CLASSES_OLDER;
import static com.szpu.algohub.demo.DemoCatalog.HIDDEN_2026;
import static com.szpu.algohub.demo.DemoCatalog.LEETCODE;
import static com.szpu.algohub.demo.DemoCatalog.LUOGU_B_TITLES;
import static com.szpu.algohub.demo.DemoCatalog.LUOGU_P;
import static com.szpu.algohub.demo.DemoCatalog.ME;
import static com.szpu.algohub.demo.DemoCatalog.NAMES_2024;
import static com.szpu.algohub.demo.DemoCatalog.NAMES_2025;
import static com.szpu.algohub.demo.DemoCatalog.NOWCODER_TITLES;
import static com.szpu.algohub.demo.DemoCatalog.OTHER_2026;
import static com.szpu.algohub.demo.DemoCatalog.TOP_2026;
import static com.szpu.algohub.platform.Platform.ATCODER;
import static com.szpu.algohub.platform.Platform.CODEFORCES;
import static com.szpu.algohub.platform.Platform.LEETCODE_CN;
import static com.szpu.algohub.platform.Platform.LUOGU;
import static com.szpu.algohub.platform.Platform.NOWCODER;
import static com.szpu.algohub.platform.Platform.QOJ;

import java.sql.Types;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.platform.adapter.ContestCategoryRules;
import com.szpu.algohub.problem.Problem;
import com.szpu.algohub.problem.ProblemMapper;
import com.szpu.algohub.problem.ProblemService;
import com.szpu.algohub.statistics.ContestParticipationMapper;
import com.szpu.algohub.statistics.DailyStatMapper;
import com.szpu.algohub.statistics.SolvedMapper;
import com.szpu.algohub.sync.AccountDataStatusMapper;
import com.szpu.algohub.sync.SyncCursorMapper;
import com.szpu.algohub.sync.SyncService;

/**
 * 示例数据库（只在 demo 配置下运行）：给页面改版截图和前端开发用的一套完整数据，人物和数字与设计画布一致。
 *
 * 只写源站会给的原始数据：账号、题目、提交记录、总量快照、比赛与 Rating 等。
 * 通过关系与每日统计照真实同步的规则生成——每个账号每道题最早一次通过记为通过时间，
 * 历史回填完整的账号再把可信度提升为 EXACT（SolvedMapper.promoteConfidence），
 * 每日统计调用 SyncService.rebuildSubmissionStats，只有总数的平台调用 DailyStatMapper.rebuildSnapshotDelta。
 *
 * 时间都相对「现在」生成，每次启动重建（清库见 DemoDatabaseReset）。
 * 在 Spring 开始接收请求之前写完，页面不会看到写了一半的数据。
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements SmartInitializingSingleton {

    /** 示例同学统一的登录密码；管理员沿用 algohub.bootstrap 的账号密码。 */
    public static final String PASSWORD = "demo12345";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId ZONE = TimeSupport.CAMPUS_ZONE;

    /** 陈一鸣最近 27 天每天新通过的 Codeforces 题数，最后一个是今天：近 7 天 12 题、6 天有记录、连续 5 天。 */
    private static final int[] ME_RECENT = {2, 1, 0, 3, 2, 1, 1, 2, 0, 3, 2, 1, 2, 2, 1, 1, 0, 3, 2, 1, 2, 0, 3, 1, 3, 2, 1};
    private static final int ME_CF_SOLVED = 186;
    private static final int ME_ATCODER_SOLVED = 34;
    private static final int ME_LEETCODE_SOLVED = 21;
    private static final int ME_LUOGU_SOLVED = 98;

    private static final String LUOGU_NOTE = "公开练习页已通过题单；无提交时间；不是完整提交历史；公开数据匿名同步，无需维持 Cookie；"
            + "每日平台活动已读取，仅覆盖源站公开日期，不是首次 AC";
    private static final String LEETCODE_PARTIAL = "力扣只公开最近 15 道通过的题（每题一条，时间是最近一次通过），更早的通过记录取不到";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final AppProperties properties;
    private final PlatformRegistry registry;
    private final ProblemService problemService;
    private final ProblemMapper problemMapper;
    private final SolvedMapper solvedMapper;
    private final DailyStatMapper dailyStatMapper;
    private final ContestParticipationMapper participationMapper;
    private final SyncCursorMapper cursorMapper;
    private final SyncService syncService;
    private final StringRedisTemplate redis;
    private final int redisDatabase;

    public DemoDataSeeder(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder encoder, AppProperties properties,
                          PlatformRegistry registry, ProblemService problemService, ProblemMapper problemMapper,
                          SolvedMapper solvedMapper,
                          DailyStatMapper dailyStatMapper, ContestParticipationMapper participationMapper,
                          SyncCursorMapper cursorMapper, SyncService syncService, StringRedisTemplate redis,
                          @Value("${spring.data.redis.database:0}") int redisDatabase) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.properties = properties;
        this.registry = registry;
        this.problemService = problemService;
        this.problemMapper = problemMapper;
        this.solvedMapper = solvedMapper;
        this.dailyStatMapper = dailyStatMapper;
        this.participationMapper = participationMapper;
        this.cursorMapper = cursorMapper;
        this.syncService = syncService;
        this.redis = redis;
        this.redisDatabase = redisDatabase;
    }

    @Override
    public void afterSingletonsInstantiated() {
        long started = System.currentTimeMillis();
        clearSessions();
        String summary = tx.execute(status -> new Seeding().run());
        log.warn("示例数据已写入：{}，用时 {} ms。示例同学用邮箱验证码登录（验证码写在这份日志里），截图脚本走 /api/auth/demo-login；管理员 {} 用密码登录。",
                summary, System.currentTimeMillis() - started, properties.getBootstrap().getAdminUsername());
    }

    /** 数据库重建后，上次留下的登录状态和限流计数一起清掉（只清示例专用的 Redis 库）。 */
    private void clearSessions() {
        if (redisDatabase == 0) {
            log.warn("示例配置没有指定单独的 Redis 库，跳过清空登录状态");
            return;
        }
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    /** 一个平台账号和它要写入的提交。 */
    private static final class Acct {
        final long id;
        final long userId;
        final Platform platform;
        final boolean complete;
        final String language;
        final Instant lastSuccess;
        final Map<String, Long> solvedIds = new LinkedHashMap<>();
        final List<Sub> subs = new ArrayList<>();
        /** 洛谷公开题单：只有通过关系，没有通过时间 */
        final List<Long> untimed = new ArrayList<>();
        boolean resubmits = true;
        boolean snapshots;

        Acct(long id, long userId, Platform platform, boolean complete, String language, Instant lastSuccess) {
            this.id = id;
            this.userId = userId;
            this.platform = platform;
            this.complete = complete;
            this.language = language;
            this.lastSuccess = lastSuccess;
        }
    }

    private record Sub(long problemId, String verdict, boolean accepted, Instant at, String contestRef) {
    }

    private record Pick(long id, String key, String contestRef) {
    }

    private record Me(long userId, Acct cf, Acct luogu, Acct leetcode, Acct atcoder) {
    }

    private final class Seeding {
        private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        private final LocalDate today = TimeSupport.today();
        private final Random rnd = new Random(20260927L);
        private final Map<String, Long> problemIds = new HashMap<>();
        private final List<Acct> accounts = new ArrayList<>();
        private final Map<String, Long> userIds = new LinkedHashMap<>();
        private final Map<Long, Acct> leetcodeOf = new HashMap<>();
        private String passwordHash;
        private long schoolId;
        private long adminId;
        private int studentSeq;
        private int submissionCount;

        String run() {
            schoolId = jdbc.queryForObject("select id from school order by id limit 1", Long.class);
            passwordHash = encoder.encode(PASSWORD);
            adminId = admin();
            catalog();
            Me me = me();
            classmates();
            specialUsers();
            appeal();
            writeSubmissions();
            deriveStatistics(me);
            Map<String, Long> contests = contests();
            meExtras(me, contests);
            problemLists(me);
            failuresAndTasks(me);
            adminConsole();
            onboarding();
            return "用户 " + userIds.size() + " 人、平台账号 " + accounts.size() + " 个、提交 " + submissionCount
                    + " 条、题目 " + problemIds.size() + " 道、比赛 " + contests.size() + " 场";
        }

        // ---------- 用户 ----------

        private long admin() {
            AppProperties.Bootstrap bootstrap = properties.getBootstrap();
            Instant created = now.minus(Duration.ofDays(400));
            String email = "admin@example.com";
            long id = jdbc.queryForObject("""
                    insert into app_user (username, password_hash, display_name, email, role, status, created_at, updated_at)
                    values (?, ?, ?, ?, 'ADMIN', 'ACTIVE', ?, ?) returning id
                    """, Long.class, bootstrap.getAdminUsername(), encoder.encode(bootstrap.getAdminPassword()),
                    bootstrap.getAdminDisplayName(), email, time(created), time(created));
            userIds.put(bootstrap.getAdminDisplayName(), id);
            verifiedEmail(id, email, created);
            privacy(id, true, "SCHOOL");
            member(id, null, null, null, "APPROVED", created);
            return id;
        }

        private long user(String username, String name, Instant created) {
            long id = jdbc.queryForObject("""
                    insert into app_user (username, password_hash, display_name, email, role, status, created_at, updated_at)
                    values (?, ?, ?, ?, 'STUDENT', 'ACTIVE', ?, ?) returning id
                    """, Long.class, username, passwordHash, name, username + "@example.com", time(created), time(created));
            userIds.put(name, id);
            return id;
        }

        /** 选好学校的同学：验证过登录邮箱、默认隐私、本校已通过（自己选校即通过）。 */
        private long student(String username, String name, int year, String className, String studentNo,
                             Instant created, boolean showOnRanking, String visibility) {
            long id = user(username, name, created);
            verifiedEmail(id, username + "@example.com", created.plusSeconds(120));
            privacy(id, showOnRanking, visibility);
            member(id, year, className, studentNo, "APPROVED", created.plusSeconds(300));
            return id;
        }

        private void verifiedEmail(long userId, String email, Instant at) {
            jdbc.update("insert into verified_login_email (user_id, email, verified_at) values (?, ?, ?)",
                    userId, email, time(at));
        }

        private void privacy(long userId, boolean showOnRanking, String visibility) {
            jdbc.update("insert into user_privacy (user_id, profile_visibility, show_on_ranking) values (?, ?, ?)",
                    userId, visibility, showOnRanking);
        }

        private void member(long userId, Integer year, String className, String studentNo, String status, Instant applied) {
            boolean approved = "APPROVED".equals(status);
            jdbc.update("""
                    insert into school_member (school_id, user_id, student_no, enrollment_year, class_name, member_role,
                                               status, applied_at, reviewed_at)
                    values (?, ?, ?, ?, ?, 'MEMBER', ?, ?, ?)
                    """, schoolId, userId, text(studentNo), integer(year), text(className), status, time(applied),
                    time(approved ? applied : null));
        }

        private String studentNo(int year, String className) {
            int cls = Arrays.asList(CLASSES_2026).indexOf(className) + 1;
            return String.format("%d%02d%03d", year, cls, ++studentSeq);
        }

        // ---------- 题库 ----------

        /**
         * 题库：和题库同步写的字段一样（难度数值、通过人数、catalog_synced_at，Codeforces 带官方标签）。
         * 只有 Codeforces 与 AtCoder 有公开题库；题库页和「练得较少的方向」都靠它。
         */
        private void catalog() {
            int[] popular = {26000, 13000, 5200, 1600, 350};
            for (int contest = 1900; contest < 2160; contest++) {
                for (char index = 'A'; index <= 'E'; index++) {
                    FetchedProblem fetched = cf(contest, index);
                    Random r = new Random(contest * 16L + index);
                    int solved = (int) (popular[index - 'A'] * (1.0 + r.nextDouble() * 0.8) * (1.0 + (2160 - contest) / 400.0));
                    catalogProblem(CODEFORCES, fetched, Integer.parseInt(fetched.difficultyRaw()), solved);
                }
            }
            for (int contest = 300; contest <= 473; contest++) {
                for (char index = 'a'; index <= 'e'; index++) {
                    FetchedProblem fetched = atcoder(contest, index);
                    catalogProblem(ATCODER, fetched, Integer.parseInt(fetched.difficultyRaw()), null);
                }
            }
            Instant synced = now.minus(Duration.ofDays(2)).minus(Duration.ofHours(3));
            jdbc.update("update problem set catalog_synced_at = ?, updated_at = ? where catalog_synced_at is not null",
                    time(synced), time(synced));
            for (Platform platform : List.of(CODEFORCES, ATCODER)) {
                jdbc.update("""
                        insert into sync_task (task_type, platform, status, priority, attempt, max_attempt, next_run_at,
                                               duration_ms, created_at, updated_at, finished_at)
                        values ('PROBLEM_CATALOG', ?, 'SUCCESS', 150, 1, 3, ?, ?, ?, ?, ?)
                        """, platform.name(), time(synced.minusSeconds(60)), platform == CODEFORCES ? 21480 : 9310,
                        time(synced.minusSeconds(60)), time(synced), time(synced));
            }
        }

        private void catalogProblem(Platform platform, FetchedProblem fetched, Integer difficulty, Integer solvedCount) {
            Problem problem = new Problem();
            problem.setPlatform(platform.name());
            problem.setProblemKey(fetched.problemKey());
            problem.setTitle(fetched.title());
            problem.setUrl(fetched.url());
            problem.setDifficultyRaw(fetched.difficultyRaw());
            problem.setDifficultyLabel(fetched.difficultyLabel());
            problem.setDifficultySource(fetched.difficultySource());
            problem.setDifficultyValue(difficulty);
            problem.setSolvedCount(solvedCount);
            problem.setTagsComplete(fetched.tagsComplete());
            problemMapper.upsertCatalog(problem);
            String source = "THIRD_PARTY".equals(fetched.difficultySource()) ? "THIRD_PARTY" : "OFFICIAL";
            for (String tag : fetched.tags()) {
                problemMapper.insertTag(problem.getId(), tag, source);
            }
            problemIds.put(platform.name() + "/" + fetched.problemKey(), problem.getId());
        }

        // ---------- 陈一鸣 ----------

        private Me me() {
            Instant created = now.minus(Duration.ofDays(24)).minus(Duration.ofHours(5));
            long id = student("chenyiming", ME, 2026, "计应 1 班", studentNo(2026, "计应 1 班"), created, true, "SCHOOL");
            jdbc.update("update app_user set last_login_at = ?, bio = ? where id = ?",
                    time(now.minus(Duration.ofHours(20))), "在练 Codeforces Div. 3，目标是 11 月前上 1400", id);

            Acct cf = account(id, CODEFORCES, "3102581", "chen_ym", "VERIFIED", true, "GNU G++17 7.3.0",
                    created.plusSeconds(3600), now.minus(Duration.ofMinutes(12)));
            Acct luogu = account(id, LUOGU, "1234567", "chenym2026", "VERIFIED", false, null,
                    created.plusSeconds(4200), now.minus(Duration.ofMinutes(25)));
            Acct leetcode = account(id, LEETCODE_CN, "chen-ym", "chen-ym", "VERIFIED", false, null,
                    created.plus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
            Acct atcoder = account(id, ATCODER, "chenym", "chenym", "RESOLVED", true, "C++ 20 (gcc 12.2)",
                    now.minus(Duration.ofDays(3)), now.minus(Duration.ofHours(1)));
            for (Acct acct : List.of(cf, leetcode, atcoder)) {
                acct.resubmits = false;
            }

            // Codeforces：最近 27 天按画布的数字，更早的 144 题分散在大半年里
            double skill = 0.45;
            Set<LocalDate> active = new TreeSet<>();
            for (int i = 0; i < ME_RECENT.length; i++) {
                LocalDate day = today.minusDays(ME_RECENT.length - 1 - i);
                for (int k = 0; k < ME_RECENT[i]; k++) {
                    solveNew(cf, skill, timeOn(day));
                }
                if (ME_RECENT[i] > 0) {
                    active.add(day);
                }
            }
            Map<LocalDate, Integer> older = meOlderDays(ME_CF_SOLVED - Arrays.stream(ME_RECENT).sum());
            older.forEach((day, count) -> {
                for (int k = 0; k < count; k++) {
                    solveNew(cf, skill, timeOn(day));
                }
            });
            active.addAll(older.keySet());
            cfRating(cf);
            historyDone(cf, 3);

            meAtcoder(atcoder, older.keySet());
            historyDone(atcoder, 0);

            // 力扣：7～40 天前、和 Codeforces 同一天的 21 道——不改变连续天数，本周目标仍是画布上的 12 / 15
            List<LocalDate> lcDays = active.stream()
                    .filter(d -> !d.isBefore(today.minusDays(40)) && d.isBefore(today.minusDays(6))).toList();
            List<String[]> lcRows = new ArrayList<>(Arrays.asList(LEETCODE));
            Collections.shuffle(lcRows, rnd);
            for (int i = 0; i < ME_LEETCODE_SOLVED; i++) {
                solveLeetcode(leetcode, lcRows.get(i), timeOn(lcDays.get(rnd.nextInt(lcDays.size()))));
            }
            snapshot(leetcode, now.minus(Duration.ofDays(30)), 44, null, "{EASY=27, MEDIUM=15, HARD=2}");
            snapshot(leetcode, now.minus(Duration.ofDays(20)), 47, null, "{EASY=28, MEDIUM=17, HARD=2}");
            snapshot(leetcode, now.minus(Duration.ofDays(9)), 49, null, "{EASY=29, MEDIUM=18, HARD=2}");
            snapshot(leetcode, leetcode.lastSuccess, 52, null, "{EASY=31, MEDIUM=18, HARD=3}");
            // 这一轮同步失败：上次成功的数据保留，账号标为失败，等待自动重试
            Instant failedAt = now.minus(Duration.ofMinutes(10));
            String error = "力扣接口暂时不可用（HTTP 503），稍后自动重试";
            dataStatus(leetcode, AccountDataStatusMapper.HISTORY, "FAILED", failedAt, leetcode.lastSuccess,
                    "UPSTREAM_ERROR", error, 15, LEETCODE_PARTIAL);
            dataStatus(leetcode, AccountDataStatusMapper.TOTALS, "SUCCESS", leetcode.lastSuccess, leetcode.lastSuccess,
                    null, null, 52, null);
            cursorMapper.save(leetcode.id, SyncService.CURSOR_FORWARD, "1289304", leetcode.lastSuccess, false);
            jdbc.update("""
                    update platform_account set sync_status = 'FAILED', last_attempt_at = ?, last_error_kind = 'UPSTREAM_ERROR',
                           last_error_msg = ? where id = ?
                    """, time(failedAt), error, leetcode.id);

            // 洛谷：公开题单 98 题（没有通过时间）+ 每日活动
            List<FetchedProblem> luoguPool = luoguPool();
            for (int i = 0; i < ME_LUOGU_SOLVED; i++) {
                luogu.untimed.add(problem(LUOGU, luoguPool.get(i)));
            }
            int[][] luoguSnapshots = {{28, 86}, {20, 90}, {12, 93}, {5, 95}};
            for (int[] s : luoguSnapshots) {
                snapshot(luogu, now.minus(Duration.ofDays(s[0])).minusSeconds(rnd.nextInt(7200)), s[1], null, LUOGU_NOTE);
            }
            snapshot(luogu, luogu.lastSuccess, ME_LUOGU_SOLVED, null, LUOGU_NOTE);
            luoguDone(luogu, ME_LUOGU_SOLVED);
            for (int ago = 0; ago < 150; ago++) {
                if (rnd.nextDouble() < 0.35) {
                    dailyStatMapper.upsertActivity(luogu.id, today.minusDays(ago), 1 + rnd.nextInt(5));
                }
            }
            return new Me(id, cf, luogu, leetcode, atcoder);
        }

        /** 陈一鸣 27 天以前的记录：大多数连续不超过 6 天，75～83 天前连续 9 天（「最长连续」），27 天前那天休息。 */
        private Map<LocalDate, Integer> meOlderDays(int total) {
            List<Integer> active = new ArrayList<>();
            int run = 0;
            for (int ago = 330; ago >= 27; ago--) {
                boolean on;
                if (ago >= 75 && ago <= 83) {
                    on = true;
                } else if (ago == 27 || ago == 74 || ago == 84) {
                    on = false;
                } else {
                    on = run < 6 && rnd.nextDouble() < 0.34;
                }
                run = on ? run + 1 : 0;
                if (on) {
                    active.add(ago);
                }
            }
            List<Integer> trimmable = new ArrayList<>(active.stream().filter(a -> a < 75 || a > 83).toList());
            Collections.shuffle(trimmable, rnd);
            while (active.size() > total && !trimmable.isEmpty()) {
                active.remove(trimmable.remove(trimmable.size() - 1));
            }
            Map<LocalDate, Integer> counts = new TreeMap<>();
            for (int ago : active) {
                counts.put(today.minusDays(ago), 1);
            }
            List<LocalDate> days = new ArrayList<>(counts.keySet());
            for (int i = active.size(); i < total; i++) {
                counts.merge(days.get(rnd.nextInt(days.size())), 1, Integer::sum);
            }
            return counts;
        }

        /** 9 场 Codeforces：当前 1204，最高 1260。 */
        private void cfRating(Acct cf) {
            String[][] rounds = {
                    {"2107", "Codeforces Round 1024 (Div. 2)", "9871", "131"},
                    {"2114", "Codeforces Round 1029 (Div. 3)", "5210", "115"},
                    {"2120", "Educational Codeforces Round 180 (Rated for Div. 2)", "7732", "99"},
                    {"2126", "Codeforces Round 1034 (Div. 3)", "4388", "83"},
                    {"2131", "Codeforces Round 1037 (Div. 3)", "3920", "67"},
                    {"2137", "Codeforces Round 1042 (Div. 3)", "4105", "51"},
                    {"2143", "Codeforces Round 1047 (Div. 3)", "2871", "35"},
                    {"2150", "Codeforces Round 1051 (Div. 2)", "8016", "19"},
                    {"2156", "Educational Codeforces Round 183 (Rated for Div. 2)", "6254", "3"}};
            int[] ratings = {362, 701, 856, 1003, 1109, 1190, 1260, 1181, 1204};
            int old = 0;
            for (int i = 0; i < rounds.length; i++) {
                Instant end = at(today.minusDays(Integer.parseInt(rounds[i][3])), 22, 35).plus(Duration.ofHours(2));
                participationMapper.upsert(cf.id, rounds[i][0], rounds[i][1], Integer.parseInt(rounds[i][2]), old,
                        ratings[i], null, true, end);
                old = ratings[i];
            }
            dataStatus(cf, AccountDataStatusMapper.RATING, "SUCCESS", cf.lastSuccess, cf.lastSuccess, null, null,
                    rounds.length, "参加 9 场，当前 1204，最高 1260");
        }

        /** AtCoder 34 题：4 场 ABC（在当天的比赛时间里通过）+ 平时练习，都在 35 天以前，不影响本周数字。 */
        private void meAtcoder(Acct at, Set<LocalDate> cfDays) {
            List<LocalDate> practice = cfDays.stream().filter(d -> d.isBefore(today.minusDays(34))).toList();
            List<LocalDate> saturdays = practice.stream().filter(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY).toList();
            List<LocalDate> contestDays = new ArrayList<>();
            int step = Math.max(1, saturdays.size() / 4);
            for (int i = 0; i < 4 && i * step < saturdays.size(); i++) {
                contestDays.add(saturdays.get(Math.min(saturdays.size() - 1, i * step + step / 2)));
            }
            int[] perf = {689, 812, 745, 901};
            int[] ratings = {211, 388, 452, 497};
            int[] places = {6123, 5541, 6012, 4870};
            int solved = 0;
            int old = 0;
            for (int i = 0; i < contestDays.size(); i++) {
                LocalDate day = contestDays.get(i);
                int number = abcNumber(day);
                Instant start = at(day, 20, 0);
                char[] tasks = i % 2 == 0 ? new char[] {'a', 'b', 'c'} : new char[] {'a', 'b'};
                for (int k = 0; k < tasks.length; k++) {
                    FetchedProblem p = atcoder(number, tasks[k]);
                    if (at.solvedIds.containsKey(p.problemKey())) {
                        continue;
                    }
                    long pid = problem(ATCODER, p);
                    at.solvedIds.put(p.problemKey(), pid);
                    solve(at, new Pick(pid, p.problemKey(), "abc" + number), start.plusSeconds(60L * (6 + k * 22 + rnd.nextInt(10))));
                    solved++;
                }
                participationMapper.upsert(at.id, "abc" + number, "AtCoder Beginner Contest " + number, places[i], old,
                        ratings[i], perf[i], true, start.plus(Duration.ofMinutes(100)));
                old = ratings[i];
            }
            for (; solved < ME_ATCODER_SOLVED; solved++) {
                solveNew(at, 0.3, timeOn(practice.get(rnd.nextInt(practice.size()))));
            }
            dataStatus(at, AccountDataStatusMapper.RATING, "SUCCESS", at.lastSuccess, at.lastSuccess, null, null,
                    contestDays.size(), "参加 " + contestDays.size() + " 场，当前 " + old + "，最高 " + old);
        }

        /** 以下周六的 ABC 474 为准往前数，示例里的场次编号前后一致。 */
        private int abcNumber(LocalDate saturday) {
            LocalDate next = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
            return 474 - (int) ChronoUnit.WEEKS.between(saturday, next);
        }

        // ---------- 同学 ----------

        private void classmates() {
            int index = 0;
            for (DemoCatalog.Ranked ranked : TOP_2026) {
                if (!ranked.name().equals(ME)) {
                    classmate(ranked.name(), 2026, ranked.className(), ranked.weekScore(), index++, true, "SCHOOL");
                }
            }
            for (int i = 0; i < OTHER_2026.length; i++) {
                classmate(OTHER_2026[i], 2026, CLASSES_2026[(i * 5 + 1) % CLASSES_2026.length], 7 - i / 6, index++,
                        true, "SCHOOL");
            }
            // 同届但不上榜：一个关了「参与校内排行」，一个档案仅自己可见
            classmate(HIDDEN_2026[0], 2026, "软件 1 班", 10, index++, false, "SCHOOL");
            classmate(HIDDEN_2026[1], 2026, "计应 2 班", 6, index++, true, "PRIVATE");
            for (int i = 0; i < NAMES_2025.length; i++) {
                classmate(NAMES_2025[i], 2025, CLASSES_OLDER[i % CLASSES_OLDER.length], rnd.nextInt(19), index++,
                        true, "SCHOOL");
            }
            for (int i = 0; i < NAMES_2024.length; i++) {
                classmate(NAMES_2024[i], 2024, CLASSES_OLDER[(i + 2) % CLASSES_OLDER.length], rnd.nextInt(13), index++,
                        true, "SCHOOL");
            }
        }

        /**
         * 一位同学：至少一个能确定首次通过时间的账号（Codeforces / AtCoder / 牛客，历史完整），
         * 近 7 天正好 weekScore 题；另有部分同学绑了力扣或洛谷。
         */
        private void classmate(String name, int year, String className, int weekScore, int index,
                               boolean showOnRanking, String visibility) {
            String no = studentNo(year, className);
            Instant created = year == 2026
                    ? now.minus(Duration.ofDays(17 + rnd.nextInt(9))).minusSeconds(rnd.nextInt(36000))
                    : now.minus(Duration.ofDays(200 + rnd.nextInt(500)));
            long userId = student("s" + no, name, year, className, no, created, showOnRanking, visibility);
            double skill = Math.min(1.0, 0.1 + weekScore / 35.0 + (2026 - year) * 0.1);
            String handle = "szpu" + no.substring(2);
            boolean hasCf = index % 10 < 7;
            boolean hasAtcoder = !hasCf || index % 3 == 0;
            boolean hasNowcoder = index % 5 == 2;
            // 孙浩然的力扣账号在申诉里单独建（见 appeal）
            boolean hasLeetcode = index % 5 < 2 && !"孙浩然".equals(name);
            boolean hasLuogu = index % 3 == 1;
            Instant bound = created.plus(Duration.ofHours(1 + rnd.nextInt(48)));
            if (bound.isAfter(now)) {
                bound = now.minus(Duration.ofHours(2));
            }

            List<Acct> timed = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            if (hasCf) {
                String language = index % 4 == 3 ? "Python 3" : index % 4 == 2 ? "GNU G++20 13.2 (64 bit, winlibs)"
                        : "GNU G++17 7.3.0";
                timed.add(account(userId, CODEFORCES, String.valueOf(3_000_000 + userId * 37), handle, "VERIFIED", true,
                        language, bound, recently()));
                weights.add(7);
            }
            if (hasAtcoder) {
                timed.add(account(userId, ATCODER, handle, handle, "VERIFIED", true,
                        index % 4 == 3 ? "Python (CPython 3.11.4)" : "C++ 20 (gcc 12.2)", bound, recently()));
                weights.add(2);
            }
            if (hasNowcoder) {
                String uid = String.valueOf(338_000_000L + userId * 101);
                timed.add(account(userId, NOWCODER, uid, uid, "VERIFIED", true, index % 4 == 3 ? "Python3" : "C++",
                        bound, recently()));
                weights.add(1);
            }

            Map<LocalDate, Integer> days = new TreeMap<>();
            spread(days, weekScore, 0, 6, Math.min(0.9, 0.25 + weekScore / 20.0));
            int month = weekScore == 0 ? rnd.nextInt(4) : (int) Math.round(weekScore * (1.2 + rnd.nextDouble()));
            spread(days, month, 7, 29, 0.25 + skill * 0.4);
            spread(days, (int) (12 + skill * 170 + rnd.nextInt(40)), 30, 330, 0.10 + skill * 0.25);
            days.forEach((day, count) -> {
                for (int k = 0; k < count; k++) {
                    solveNew(pickWeighted(timed, weights), skill, timeOn(day));
                }
            });
            for (Acct acct : timed) {
                historyDone(acct, rnd.nextInt(5));
                if (acct.platform != NOWCODER && rnd.nextDouble() < skill) {
                    rating(acct, 2 + (int) (skill * 5));
                }
                if (acct.platform == NOWCODER) {
                    int total = acct.solvedIds.size() + rnd.nextInt(6);
                    snapshot(acct, acct.lastSuccess, total, total * 2 + rnd.nextInt(20),
                            "牛客竞赛主页：" + total + " 题；Tracker 本次未获取（不表示 0 题）；两者范围可能重叠，不相加");
                    dataStatus(acct, AccountDataStatusMapper.TOTALS, "SUCCESS", acct.lastSuccess, acct.lastSuccess,
                            null, null, total, null);
                }
            }
            if (hasLeetcode) {
                leetcodeAccount(userId, "szpu-" + no.substring(2), "VERIFIED", bound, skill);
            }
            if (hasLuogu) {
                luoguAccount(userId, String.valueOf(1_300_000 + userId * 13), handle, bound,
                        Math.min(120, 15 + (int) (skill * 90) + rnd.nextInt(20)));
            }
        }

        private Instant recently() {
            return now.minus(Duration.ofMinutes(4 + rnd.nextInt(170)));
        }

        private Acct pickWeighted(List<Acct> accts, List<Integer> weights) {
            int total = weights.stream().mapToInt(Integer::intValue).sum();
            int r = rnd.nextInt(total);
            for (int i = 0; i < accts.size(); i++) {
                r -= weights.get(i);
                if (r < 0) {
                    return accts.get(i);
                }
            }
            return accts.getLast();
        }

        /** 把 total 道题分到 newest～oldest 天前里的部分日子，每个有记录的日子至少一道。 */
        private void spread(Map<LocalDate, Integer> into, int total, int newest, int oldest, double ratio) {
            if (total <= 0) {
                return;
            }
            List<LocalDate> active = new ArrayList<>();
            for (int ago = newest; ago <= oldest; ago++) {
                if (rnd.nextDouble() < ratio) {
                    active.add(today.minusDays(ago));
                }
            }
            if (active.isEmpty()) {
                active.add(today.minusDays(newest + rnd.nextInt(oldest - newest + 1)));
            }
            Collections.shuffle(active, rnd);
            if (total < active.size()) {
                active = active.subList(0, total);
            }
            for (LocalDate day : active) {
                into.merge(day, 1, Integer::sum);
            }
            for (int i = active.size(); i < total; i++) {
                into.merge(active.get(rnd.nextInt(active.size())), 1, Integer::sum);
            }
        }

        private void rating(Acct acct, int contests) {
            int rating = 0;
            int max = 0;
            for (int i = 0; i < contests; i++) {
                int old = rating;
                rating = Math.max(0, rating + (i == 0 ? 350 + rnd.nextInt(150) : rnd.nextInt(260) - 70));
                max = Math.max(max, rating);
                Instant end = at(today.minusDays(7L + (contests - i) * 12L + rnd.nextInt(5)), 22, 35).plus(Duration.ofHours(2));
                String key = acct.platform == CODEFORCES ? String.valueOf(2100 + i * 7) : "abc" + (430 + i * 4);
                String title = acct.platform == CODEFORCES ? "Codeforces Round " + (1020 + i * 4) + " (Div. 3)"
                        : "AtCoder Beginner Contest " + (430 + i * 4);
                participationMapper.upsert(acct.id, key, title, 3000 + rnd.nextInt(9000), old, rating,
                        acct.platform == ATCODER ? rating + rnd.nextInt(300) : null, true, end);
            }
            dataStatus(acct, AccountDataStatusMapper.RATING, "SUCCESS", acct.lastSuccess, acct.lastSuccess, null, null,
                    contests, "参加 " + contests + " 场，当前 " + rating + "，最高 " + max);
        }

        private Acct leetcodeAccount(long userId, String uid, String ownership, Instant bound, double skill) {
            Acct lc = account(userId, LEETCODE_CN, uid, uid, ownership, false, null, bound, recently());
            int count = 5 + rnd.nextInt(11);
            List<String[]> rows = new ArrayList<>(Arrays.asList(LEETCODE));
            Collections.shuffle(rows, rnd);
            for (int i = 0; i < count; i++) {
                solveLeetcode(lc, rows.get(i), timeOn(today.minusDays(rnd.nextInt(45))));
            }
            int total = count + 8 + (int) (skill * 90) + rnd.nextInt(20);
            int earlier = total - 2 - rnd.nextInt(6);
            snapshot(lc, now.minus(Duration.ofDays(20)), earlier, null, leetcodeBreakdown(earlier));
            snapshot(lc, lc.lastSuccess, total, null, leetcodeBreakdown(total));
            dataStatus(lc, AccountDataStatusMapper.HISTORY, "SUCCESS", lc.lastSuccess, lc.lastSuccess, null, null,
                    count, LEETCODE_PARTIAL);
            dataStatus(lc, AccountDataStatusMapper.TOTALS, "SUCCESS", lc.lastSuccess, lc.lastSuccess, null, null, total, null);
            cursorMapper.save(lc.id, SyncService.CURSOR_FORWARD, String.valueOf(900_000 + lc.id), lc.lastSuccess, false);
            leetcodeOf.put(userId, lc);
            return lc;
        }

        private String leetcodeBreakdown(int total) {
            int easy = (int) Math.round(total * 0.56);
            int hard = total / 14;
            return "{EASY=" + easy + ", MEDIUM=" + (total - easy - hard) + ", HARD=" + hard + "}";
        }

        private void luoguAccount(long userId, String uid, String handle, Instant bound, int solved) {
            Acct lg = account(userId, LUOGU, uid, handle, "VERIFIED", false, null, bound, recently());
            List<FetchedProblem> pool = new ArrayList<>(luoguPool());
            Collections.shuffle(pool, rnd);
            for (int i = 0; i < solved; i++) {
                lg.untimed.add(problem(LUOGU, pool.get(i)));
            }
            snapshot(lg, now.minus(Duration.ofDays(15)), Math.max(0, solved - 1 - rnd.nextInt(6)), null, LUOGU_NOTE);
            snapshot(lg, lg.lastSuccess, solved, null, LUOGU_NOTE);
            luoguDone(lg, solved);
        }

        /** 新同学、邮箱未验证、没选学校、等审批：各有一个能登录的账号。 */
        private void specialUsers() {
            student("newbie", "林悦", 2026, "计应 1 班", studentNo(2026, "计应 1 班"), now.minus(Duration.ofMinutes(40)),
                    true, "SCHOOL");

            long unverified = user("unverified", "张晓", now.minus(Duration.ofMinutes(15)));
            privacy(unverified, true, "SCHOOL");

            long noSchool = user("noschool", "许诺", now.minus(Duration.ofMinutes(30)));
            verifiedEmail(noSchool, "noschool@example.com", now.minus(Duration.ofMinutes(28)));
            privacy(noSchool, true, "SCHOOL");

            String[][] pending = {
                    {"pending", "何佳", "软件 1 班", "3"},
                    {"s2026pending2", "罗一帆", "人工智能 1 班", "26"},
                    {"s2026pending3", "陶可欣", "大数据 1 班", "50"}};
            for (String[] p : pending) {
                Instant applied = now.minus(Duration.ofHours(Long.parseLong(p[3])));
                long id = user(p[0], p[1], applied.minusSeconds(600));
                verifiedEmail(id, p[0] + "@example.com", applied.minusSeconds(500));
                privacy(id, true, "SCHOOL");
                member(id, 2026, p[2], studentNo(2026, p[2]), "PENDING", applied);
            }
        }

        /** 归属申诉（给管理后台）：孙浩然说另一位同学验证过的力扣账号其实是他的，已经完成公开资料验证，等管理员审核。 */
        private void appeal() {
            long appellant = userIds.get("孙浩然");
            long owner = userIds.entrySet().stream()
                    .filter(e -> Arrays.asList(NAMES_2024).contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .filter(id -> !leetcodeOf.containsKey(id))
                    .findFirst().orElseThrow();
            String uid = "sun-haoran";
            Acct owned = leetcodeAccount(owner, uid, "VERIFIED", now.minus(Duration.ofDays(60)), 0.6);
            Acct claimed = account(appellant, LEETCODE_CN, uid, uid, "RESOLVED", false, null,
                    now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(4)));
            Instant proved = now.minus(Duration.ofHours(3));
            long request = jdbc.queryForObject("""
                    insert into binding_request (user_id, platform, platform_uid, handle, account_id, token, expires_at,
                                                 attempts, verified_at, created_at)
                    values (?, 'LEETCODE_CN', ?, ?, ?, ?, ?, 1, ?, ?) returning id
                    """, Long.class, appellant, uid, uid, claimed.id, token(),
                    time(proved.plus(Duration.ofMinutes(40))), time(proved), time(proved.minus(Duration.ofMinutes(20))));
            jdbc.update("""
                    insert into ownership_appeal (platform, platform_uid, handle, appellant_id, owner_account_id, owner_user_id,
                                                  request_id, proved_at, reason, status, created_at)
                    values ('LEETCODE_CN', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, uid, uid, appellant, owned.id, owner, request, time(proved),
                    "这是我高中注册的力扣账号，之前借给表哥刷过题，他顺手验证了归属。现在个人简介里已经换成我的验证串。",
                    time(proved.plusSeconds(90)));
        }

        // ---------- 账号、题目与提交 ----------

        private Acct account(long userId, Platform platform, String uid, String handle, String ownership,
                             boolean complete, String language, Instant created, Instant lastSuccess) {
            boolean history = registry.require(platform).capability().submissionHistory();
            long id = jdbc.queryForObject("""
                    insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, auth_mode,
                                                  sync_status, supports_history, history_complete, last_success_at,
                                                  last_attempt_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'PUBLIC', 'OK', ?, ?, ?, ?, ?, ?) returning id
                    """, Long.class, userId, platform.name(), uid, handle, ownership, history, complete,
                    time(lastSuccess), time(lastSuccess), time(created), time(lastSuccess == null ? created : lastSuccess));
            Acct acct = new Acct(id, userId, platform, complete, language, lastSuccess);
            accounts.add(acct);
            return acct;
        }

        /** 历史回填完成：续传位置标记完成，逐条历史一项成功（本轮写入的条数通常只有几条）。 */
        private void historyDone(Acct acct, int storedThisRound) {
            cursorMapper.save(acct.id, SyncService.CURSOR_FORWARD, null, acct.lastSuccess, true);
            dataStatus(acct, AccountDataStatusMapper.HISTORY, "SUCCESS", acct.lastSuccess, acct.lastSuccess, null, null,
                    storedThisRound, "历史已完整");
        }

        private void luoguDone(Acct acct, int solved) {
            dataStatus(acct, AccountDataStatusMapper.TOTALS, "SUCCESS", acct.lastSuccess, acct.lastSuccess, null, null,
                    solved, null);
            dataStatus(acct, AccountDataStatusMapper.SOLVED_LIST, "SUCCESS", acct.lastSuccess, acct.lastSuccess, null, null,
                    solved, "只有通过关系，没有通过时间");
        }

        private void dataStatus(Acct acct, String item, String status, Instant attempt, Instant success,
                                String errorKind, String errorMsg, Integer count, String detail) {
            jdbc.update("""
                    insert into account_data_status (account_id, item, status, last_attempt_at, last_success_at,
                                                     error_kind, error_msg, observed_count, detail)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (account_id, item) do update
                       set status = excluded.status, last_attempt_at = excluded.last_attempt_at,
                           last_success_at = excluded.last_success_at, error_kind = excluded.error_kind,
                           error_msg = excluded.error_msg, observed_count = excluded.observed_count,
                           detail = excluded.detail
                    """, acct.id, item, status, time(attempt), time(success), text(errorKind), text(errorMsg),
                    integer(count), text(detail));
        }

        private void snapshot(Acct acct, Instant at, int solved, Integer submissions, String extra) {
            jdbc.update("""
                    insert into total_snapshot (account_id, captured_at, solved_total, submission_total, source, raw_extra)
                    values (?, ?, ?, ?, 'SYNC', ?)
                    """, acct.id, time(at), solved, integer(submissions), text(extra));
            acct.snapshots = true;
        }

        private long problem(Platform platform, FetchedProblem fetched) {
            return problemIds.computeIfAbsent(platform.name() + "/" + fetched.problemKey(),
                    key -> problemService.upsert(platform, fetched));
        }

        private Pick newProblem(Acct acct, double skill) {
            while (true) {
                int tier = tier(skill);
                FetchedProblem fetched;
                String contestRef;
                switch (acct.platform) {
                    case CODEFORCES -> {
                        int contest = 1900 + rnd.nextInt(260);
                        fetched = cf(contest, (char) ('A' + tier));
                        contestRef = String.valueOf(contest);
                    }
                    case ATCODER -> {
                        int contest = 300 + rnd.nextInt(172);
                        fetched = atcoder(contest, (char) ('a' + tier));
                        contestRef = "abc" + contest;
                    }
                    case NOWCODER -> {
                        fetched = nowcoder(210_000 + rnd.nextInt(9000));
                        contestRef = null;
                    }
                    default -> throw new IllegalArgumentException(acct.platform + " 没有示例题目池");
                }
                if (!acct.solvedIds.containsKey(fetched.problemKey())) {
                    long id = problem(acct.platform, fetched);
                    acct.solvedIds.put(fetched.problemKey(), id);
                    return new Pick(id, fetched.problemKey(), contestRef);
                }
            }
        }

        /** 题目档次：0 最简单。水平越高，越常做后面的题。 */
        private int tier(double skill) {
            double r = rnd.nextDouble();
            double a = 0.55 - 0.35 * skill;
            double b = a + 0.30;
            double c = b + 0.10 + 0.20 * skill;
            double d = c + 0.03 + 0.12 * skill;
            return r < a ? 0 : r < b ? 1 : r < c ? 2 : r < d ? 3 : 4;
        }

        private void solveNew(Acct acct, double skill, Instant at) {
            solve(acct, newProblem(acct, skill), at);
            // 偶尔先交了一道没做出来的题
            if (acct.resubmits && rnd.nextInt(14) == 0) {
                Pick failed = newProblem(acct, skill);
                acct.solvedIds.remove(failed.key());
                acct.subs.add(new Sub(failed.id(), failVerdict(acct.platform), false, at.minusSeconds(1800), failed.contestRef()));
            }
        }

        /** 一道题：先有 0～2 次没通过，再通过；偶尔过些天又交一次（当天的通过题数与首次通过口径不同）。 */
        private void solve(Acct acct, Pick pick, Instant acAt) {
            int fails = rnd.nextInt(10) < 6 ? 0 : rnd.nextInt(10) < 7 ? 1 : 2;
            for (int i = fails; i >= 1; i--) {
                acct.subs.add(new Sub(pick.id(), failVerdict(acct.platform), false,
                        acAt.minusSeconds(60L * (3 + rnd.nextInt(10)) * i), pick.contestRef()));
            }
            acct.subs.add(new Sub(pick.id(), acVerdict(acct.platform), true, acAt, pick.contestRef()));
            if (acct.resubmits && rnd.nextInt(25) == 0) {
                Instant again = acAt.plus(Duration.ofDays(1 + rnd.nextInt(20))).plusSeconds(rnd.nextInt(3600));
                if (again.isBefore(now.minusSeconds(600))) {
                    acct.subs.add(new Sub(pick.id(), acVerdict(acct.platform), true, again, pick.contestRef()));
                }
            }
        }

        /** 力扣只取得到通过记录（适配器只读最近通过的题），没有失败提交。 */
        private void solveLeetcode(Acct lc, String[] row, Instant at) {
            FetchedProblem fetched = new FetchedProblem(row[0], row[1], "https://leetcode.cn/problems/" + row[0] + "/",
                    null, null, "NONE", List.of(), false);
            if (!lc.solvedIds.containsKey(row[0])) {
                long id = problem(LEETCODE_CN, fetched);
                lc.solvedIds.put(row[0], id);
                lc.subs.add(new Sub(id, "AC", true, at, null));
            }
        }

        private String acVerdict(Platform platform) {
            return switch (platform) {
                case CODEFORCES -> "OK";
                case NOWCODER -> "答案正确";
                default -> "AC";
            };
        }

        private String failVerdict(Platform platform) {
            int r = rnd.nextInt(10);
            return switch (platform) {
                case CODEFORCES -> r < 6 ? "WRONG_ANSWER" : r < 8 ? "TIME_LIMIT_EXCEEDED" : r < 9 ? "RUNTIME_ERROR"
                        : "COMPILATION_ERROR";
                case NOWCODER -> r < 6 ? "答案错误" : r < 8 ? "运行超时" : r < 9 ? "段错误" : "编译错误";
                default -> r < 6 ? "WA" : r < 8 ? "TLE" : r < 9 ? "RE" : "CE";
            };
        }

        private FetchedProblem cf(int contest, char index) {
            int tier = index - 'A';
            int rating = switch (tier) {
                case 0 -> 800;
                case 1 -> 800 + 100 * (contest % 5);
                case 2 -> 1200 + 100 * (contest % 5);
                case 3 -> 1600 + 100 * (contest % 5);
                default -> 2000 + 100 * (contest % 5);
            };
            Random r = new Random(contest * 16L + tier);
            String[] pool = tier <= 1 ? CF_TAGS_EASY : tier == 2 ? CF_TAGS_MID : CF_TAGS_HARD;
            Set<String> tags = new LinkedHashSet<>();
            int count = 1 + r.nextInt(tier <= 1 ? 2 : 3);
            while (tags.size() < count) {
                tags.add(pool[r.nextInt(pool.length)]);
            }
            if (r.nextInt(25) == 0) {
                tags.add(CF_TAGS_UNMAPPED[r.nextInt(CF_TAGS_UNMAPPED.length)]);
            }
            return new FetchedProblem(contest + String.valueOf(index), pick(CF_TITLES, contest * 7 + tier * 13),
                    "https://codeforces.com/contest/" + contest + "/problem/" + index,
                    String.valueOf(rating), "CF " + rating, "OFFICIAL", List.copyOf(tags), true);
        }

        private FetchedProblem atcoder(int contest, char index) {
            int tier = index - 'a';
            Random r = new Random(contest * 8L + tier);
            int estimate = switch (tier) {
                case 0 -> -1100 + r.nextInt(900);
                case 1 -> -300 + r.nextInt(600);
                case 2 -> 200 + r.nextInt(600);
                case 3 -> 700 + r.nextInt(600);
                default -> 1200 + r.nextInt(700);
            };
            String contestId = "abc" + contest;
            String key = contestId + "_" + index;
            return new FetchedProblem(key, Character.toUpperCase(index) + ". " + pick(ATCODER_TITLES, contest * 5 + tier * 11),
                    "https://atcoder.jp/contests/" + contestId + "/tasks/" + key, String.valueOf(estimate),
                    "AtCoder Problems 估计 " + estimate, "THIRD_PARTY", List.of(), false);
        }

        private FetchedProblem nowcoder(int pid) {
            String key = String.valueOf(pid);
            return new FetchedProblem(key, pick(NOWCODER_TITLES, pid), "https://ac.nowcoder.com/acm/problem/" + key,
                    null, null, "NONE", List.of(), false);
        }

        /** 洛谷题：先是入门的 B 题，再按难度排的 P 题。标签已补全（后台补全在示例里不运行）。 */
        private List<FetchedProblem> luoguPool() {
            List<FetchedProblem> pool = new ArrayList<>();
            for (int i = 0; i < LUOGU_B_TITLES.length; i++) {
                String key = "B" + (2001 + i);
                String tag = i < 17 ? "顺序结构" : i < 34 ? "分支结构" : i < 52 ? "循环结构" : "字符串";
                pool.add(new FetchedProblem(key, LUOGU_B_TITLES[i], "https://www.luogu.com.cn/problem/" + key, "1",
                        "入门", "OFFICIAL", List.of(tag), true));
            }
            List<String[]> rows = new ArrayList<>(Arrays.asList(LUOGU_P));
            rows.sort(Comparator.comparing((String[] row) -> row[2]).thenComparing(row -> row[0]));
            for (String[] row : rows) {
                pool.add(new FetchedProblem(row[0], row[1], "https://www.luogu.com.cn/problem/" + row[0], row[2],
                        luoguLabel(row[2]), "OFFICIAL", List.of(row[3]), true));
            }
            return pool;
        }

        /** 与 LuoguPracticeParser.difficultyLabel 相同：洛谷现行的评级名，减号是全角「−」。 */
        private String luoguLabel(String raw) {
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
                default -> raw;
            };
        }

        /** 以前的日子落在 8 点到 24 点之间（偏晚上）；今天不晚于几分钟前，今天还没有空档时挪到昨天。 */
        private Instant timeOn(LocalDate day) {
            Instant dayStart = day.atStartOfDay(ZONE).toInstant();
            if (day.equals(today)) {
                long span = Duration.between(dayStart, now.minusSeconds(180)).getSeconds();
                if (span < 60) {
                    return timeOn(today.minusDays(1));
                }
                long from = Math.max(0, span - 16 * 3600);
                return dayStart.plusSeconds(from + (long) (rnd.nextDouble() * (span - from)));
            }
            int minute = 8 * 60 + (int) (Math.sqrt(rnd.nextDouble()) * (16 * 60 - 10));
            return dayStart.plusSeconds(minute * 60L + rnd.nextInt(60));
        }

        private Instant at(LocalDate day, int hour, int minute) {
            return day.atTime(hour, minute).atZone(ZONE).toInstant();
        }

        // ---------- 写入提交，再照同步的规则生成通过关系与每日统计 ----------

        private void writeSubmissions() {
            record Row(Acct acct, Sub sub) {
            }
            List<Row> rows = new ArrayList<>();
            for (Acct acct : accounts) {
                for (Sub sub : acct.subs) {
                    rows.add(new Row(acct, sub));
                }
            }
            rows.sort(Comparator.comparing(row -> row.sub().at()));
            // 同一平台的提交编号随时间递增，和源站一样
            Map<Platform, Long> ids = new EnumMap<>(Platform.class);
            ids.put(CODEFORCES, 283_400_000L);
            ids.put(ATCODER, 57_100_000L);
            ids.put(NOWCODER, 71_200_000L);
            ids.put(LEETCODE_CN, 612_000_000L);
            List<Object[]> args = new ArrayList<>(rows.size());
            for (Row row : rows) {
                long subId = ids.merge(row.acct().platform, 1L + rnd.nextInt(400), Long::sum);
                Instant collected = row.sub().at().plusSeconds(600 + rnd.nextInt(3000));
                if (collected.isAfter(now)) {
                    collected = now;
                }
                args.add(new Object[] {row.acct().id, row.sub().problemId(), row.acct().platform.name(),
                        String.valueOf(subId), row.sub().verdict(), row.sub().accepted(), offset(row.sub().at()),
                        row.acct().language, row.sub().contestRef(), offset(collected)});
            }
            jdbc.batchUpdate("""
                    insert into submission (account_id, problem_id, platform, platform_sub_id, verdict, accepted,
                                            submitted_at, time_precision, language, contest_ref, collected_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'EXACT', ?, ?, ?)
                    """, args, new int[] {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                    Types.BOOLEAN, Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR, Types.VARCHAR,
                    Types.TIMESTAMP_WITH_TIMEZONE});
            submissionCount = args.size();
        }

        private void deriveStatistics(Me me) {
            // 与同步相同：每个账号每道题取最早一次通过，先记为「已知最早」
            jdbc.update("""
                    insert into user_solved_problem (user_id, account_id, problem_id, platform, earliest_known_ac_at,
                                                     first_ac_confidence, source, created_at)
                    select a.user_id, s.account_id, s.problem_id, s.platform, min(s.submitted_at), 'EARLIEST_KNOWN',
                           'SYNC', min(s.collected_at)
                      from submission s
                      join platform_account a on a.id = s.account_id
                     where s.accepted and s.problem_id is not null
                     group by a.user_id, s.account_id, s.problem_id, s.platform
                    """);
            // 洛谷公开题单：通过过，但没有时间
            List<Object[]> untimed = new ArrayList<>();
            for (Acct acct : accounts) {
                for (long problemId : acct.untimed) {
                    untimed.add(new Object[] {acct.userId, acct.id, problemId, acct.platform.name(), offset(acct.lastSuccess)});
                }
            }
            jdbc.batchUpdate("""
                    insert into user_solved_problem (user_id, account_id, problem_id, platform, earliest_known_ac_at,
                                                     first_ac_confidence, source, created_at)
                    values (?, ?, ?, ?, null, 'UNKNOWN', 'SYNC', ?)
                    on conflict (account_id, problem_id) do nothing
                    """, untimed, new int[] {Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.VARCHAR,
                    Types.TIMESTAMP_WITH_TIMEZONE});
            for (Acct acct : accounts) {
                // 历史完整才能把最早通过当作首次通过；力扣永远补不全
                if (acct.complete) {
                    solvedMapper.promoteConfidence(acct.id);
                } else {
                    solvedMapper.downgradeConfidence(acct.id);
                }
                if (!acct.subs.isEmpty()) {
                    syncService.rebuildSubmissionStats(acct.id);
                }
                if (acct.snapshots) {
                    dailyStatMapper.deleteSnapshotBased(acct.id);
                    if (!acct.complete) {
                        dailyStatMapper.rebuildSnapshotDelta(acct.id);
                    }
                }
            }
            if (me.cf().solvedIds.size() != ME_CF_SOLVED) {
                throw new IllegalStateException("陈一鸣的 Codeforces 通过数不是 " + ME_CF_SOLVED);
            }
        }

        // ---------- 比赛 ----------

        private Map<String, Long> contests() {
            LocalDate saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
            LocalDate sunday = today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY));
            Instant div4 = at(today, 22, 35);
            if (div4.isBefore(now.plus(Duration.ofMinutes(45)))) {
                div4 = at(today.plusDays(1), 22, 35);
            }
            Map<String, Long> ids = new LinkedHashMap<>();
            contestCf(ids, 2168, "Codeforces Round 1058 (Div. 4)", div4, 135);
            contestCf(ids, 2169, "Educational Codeforces Round 184 (Rated for Div. 2)", at(today.plusDays(2), 22, 35), 120);
            contestCf(ids, 2170, "Codeforces Round 1059 (Div. 2)", at(today.plusDays(4), 22, 35), 120);
            contestAtcoder(ids, "abc474", "AtCoder Beginner Contest 474", at(saturday, 20, 0), 100);
            contestLeetcode(ids, "biweekly-contest-167", "第 167 场双周赛", at(saturday, 22, 30));
            contestLeetcode(ids, "weekly-contest-521", "第 521 场周赛", at(sunday, 10, 30));
            contestAtcoder(ids, "arc210", "AtCoder Regular Contest 210", at(sunday, 20, 0), 120);
            contestCf(ids, 2171, "Codeforces Round 1060 (Div. 3)", at(today.plusDays(8), 22, 35), 135);
            contestCf(ids, 2172, "Codeforces Round 1061 (Div. 1 + Div. 2)", at(today.plusDays(10), 22, 35), 150);
            contestAtcoder(ids, "abc475", "AtCoder Beginner Contest 475", at(saturday.plusWeeks(1), 20, 0), 100);
            contestLeetcode(ids, "weekly-contest-522", "第 522 场周赛", at(sunday.plusWeeks(1), 10, 30));
            contestCf(ids, 2173, "Codeforces Global Round 31", at(today.plusDays(13), 22, 35), 180);
            contestAtcoder(ids, "ahc058", "AtCoder Heuristic Contest 058", at(saturday.plusWeeks(2), 11, 0), 240);
            contestAtcoder(ids, "abc476", "AtCoder Beginner Contest 476", at(saturday.plusWeeks(2), 20, 0), 100);
            contestLeetcode(ids, "biweekly-contest-168", "第 168 场双周赛", at(saturday.plusWeeks(2), 22, 30));
            contestCf(ids, 2174, "Codeforces Round 1062 (Div. 4)", at(today.plusDays(17), 22, 35), 135);
            contestLeetcode(ids, "weekly-contest-523", "第 523 场周赛", at(sunday.plusWeeks(2), 10, 30));
            contestCf(ids, 2175, "Educational Codeforces Round 185 (Rated for Div. 2)", at(today.plusDays(20), 22, 35), 120);
            contestAtcoder(ids, "abc477", "AtCoder Beginner Contest 477", at(saturday.plusWeeks(3), 20, 0), 100);
            return ids;
        }

        private void contestCf(Map<String, Long> ids, int id, String title, Instant start, int minutes) {
            contest(ids, CODEFORCES, String.valueOf(id), title, start, minutes,
                    ContestCategoryRules.classifyCodeforces(title), "https://codeforces.com/contest/" + id, "CF_API");
        }

        private void contestAtcoder(Map<String, Long> ids, String id, String title, Instant start, int minutes) {
            contest(ids, ATCODER, id, title, start, minutes, ContestCategoryRules.classifyAtCoder(id, title),
                    "https://atcoder.jp/contests/" + id, "ATCODER_PROBLEMS");
        }

        private void contestLeetcode(Map<String, Long> ids, String slug, String title, Instant start) {
            contest(ids, LEETCODE_CN, slug, title, start, 90, ContestCategoryRules.classifyLeetCode(title),
                    "https://leetcode.cn/contest/" + slug, "LEETCODE_GRAPHQL");
        }

        private void contest(Map<String, Long> ids, Platform platform, String key, String title, Instant start,
                             int minutes, String category, String url, String source) {
            long id = jdbc.queryForObject("""
                    insert into contest (platform, contest_key, title, start_at, end_at, duration_sec, status, category,
                                         url, source, updated_at)
                    values (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?) returning id
                    """, Long.class, platform.name(), key, title, time(start), time(start.plus(Duration.ofMinutes(minutes))),
                    minutes * 60, category, url, source, time(now.minus(Duration.ofMinutes(20))));
            ids.put(key, id);
        }

        // ---------- 陈一鸣的收藏、周目标、待验证的绑定申请 ----------

        private void meExtras(Me me, Map<String, Long> contests) {
            for (String key : List.of("2168", "abc474", "weekly-contest-521")) {
                jdbc.update("insert into contest_bookmark (user_id, contest_id, created_at) values (?, ?, ?)",
                        me.userId(), contests.get(key), time(now.minus(Duration.ofDays(1 + rnd.nextInt(3)))));
            }

            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyGoal(me.userId(), monday, 15);
            weeklyGoal(me.userId(), monday.minusWeeks(1), 10);

            List<Long> solvedB = me.cf().solvedIds.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("B")).map(Map.Entry::getValue).toList();
            long unsolvedC = problem(CODEFORCES, cf(2161, 'C'));
            long unsolvedD = problem(ATCODER, atcoder(468, 'd'));
            bookmark(me.userId(), unsolvedC, "LATER", "二分答案，想了半小时没思路，周末再看", today);
            bookmark(me.userId(), solvedB.getFirst(), "REVIEW", "贪心的证明没想清楚，过几天重写一遍", today.plusDays(2));
            bookmark(me.userId(), problem(LUOGU, luoguPool().stream()
                    .filter(p -> p.problemKey().equals("P1216")).findFirst().orElseThrow()), "DONE",
                    "记忆化搜索和递推都写过了", null);
            bookmark(me.userId(), problem(LEETCODE_CN, new FetchedProblem("coin-change", "322. 零钱兑换",
                    "https://leetcode.cn/problems/coin-change/", null, null, "NONE", List.of(), false)), "REVIEW",
                    "完全背包，初始化写错过一次", today.minusDays(1));
            bookmark(me.userId(), unsolvedD, "LATER", null, null);

            // 牛客：拿到验证串，还没去个人简介里粘贴
            jdbc.update("""
                    insert into binding_request (user_id, platform, platform_uid, handle, token, expires_at, attempts, created_at)
                    values (?, 'NOWCODER', '339812345', '339812345', ?, ?, 0, ?)
                    """, me.userId(), token(), time(now.plus(Duration.ofMinutes(22))), time(now.minus(Duration.ofMinutes(8))));

            String chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/140.0.0.0 Safari/537.36";
            String iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/19.0 Mobile/15E148 Safari/604.1";
            authEvent(me.userId(), "REGISTER", chrome, null, now.minus(Duration.ofDays(24)).minus(Duration.ofHours(5)));
            authEvent(me.userId(), "PRIVACY_UPDATED", chrome, "可见范围=SCHOOL", now.minus(Duration.ofDays(5)));
            authEvent(me.userId(), "LOGIN_FAILED", iphone, null, now.minus(Duration.ofDays(3)));
            authEvent(me.userId(), "EMAIL_LOGIN_SUCCESS", iphone, null, now.minus(Duration.ofDays(3)).plusSeconds(90));
            authEvent(me.userId(), "LOGIN_SUCCESS", chrome, null, now.minus(Duration.ofHours(20)));
        }

        private void weeklyGoal(long userId, LocalDate weekStart, int target) {
            Instant created = weekStart.atStartOfDay(ZONE).toInstant().plus(Duration.ofHours(9));
            jdbc.update("""
                    insert into weekly_goal (user_id, week_start, target, created_at, updated_at) values (?, ?, ?, ?, ?)
                    """, userId, weekStart, target, time(created), time(created));
        }

        private void bookmark(long userId, long problemId, String status, String note, LocalDate remindOn) {
            Instant created = now.minus(Duration.ofDays(2 + rnd.nextInt(12)));
            jdbc.update("""
                    insert into problem_bookmark (user_id, problem_id, status, note, remind_on, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, userId, problemId, status, text(note), new SqlParameterValue(Types.DATE, remindOn),
                    time(created), time(created.plus(Duration.ofHours(rnd.nextInt(30)))));
        }

        private void authEvent(long userId, String event, String userAgent, String detail, Instant at) {
            jdbc.update("""
                    insert into auth_event (user_id, username, event, ip, user_agent, detail, created_at)
                    values (?, 'chenyiming', ?, '127.0.0.1', ?, ?, ?)
                    """, userId, event, userAgent, text(detail), time(at));
        }

        // ---------- 管理端：访问统计、第二个管理员、停用的同学、操作日志 ----------

        /**
         * 近 30 天的访问记录（每个同学按自己的活跃程度，确定性随机）、页面打开次数、最近活跃时间；
         * 受限管理员「李助教」（assistant / 和 admin 同密码，只有学校与成员、公告与内容）；一个被停用的同学；十几条操作日志。
         * 用单独的随机数，放在最后写，不改变前面示例数据的样子。
         */
        /**
         * 入门引导（V47）：和迁移对已有账号的处理一样，示例同学都算走完了引导，年级、班级、学号从入校记录抄过来；
         * 只留「还没验证邮箱」和「还没选学校」两位停在引导里，用来看引导页。
         */
        private void onboarding() {
            jdbc.update("update app_user set onboarding_completed = true where username not in ('unverified', 'noschool')");
            jdbc.update("""
                    update app_user u set enrollment_year = m.enrollment_year, class_name = m.class_name, student_no = m.student_no
                      from (select distinct on (user_id) user_id, enrollment_year, class_name, student_no
                              from school_member where status in ('APPROVED', 'PENDING')
                             order by user_id, case when status = 'APPROVED' then 0 else 1 end, id) m
                     where u.id = m.user_id
                    """);
        }

        private void adminConsole() {
            Random r = new Random(20260930L);
            String[] routes = {"home", "ranking", "profile", "accounts", "activity", "contests", "reminders", "announcements", "settings", "me"};
            int[] weights = {30, 22, 16, 9, 8, 5, 4, 3, 2, 1};
            Map<String, Integer> pages = new HashMap<>();
            List<Long> students = jdbc.queryForList("select id from app_user where role = 'STUDENT' order by id", Long.class);
            for (long userId : students) {
                double activeness = 0.15 + r.nextDouble() * 0.75;
                Instant lastSeen = null;
                for (int back = 29; back >= 0; back--) {
                    if (r.nextDouble() > activeness) {
                        continue;
                    }
                    LocalDate day = today.minusDays(back);
                    int views = 1 + r.nextInt(12);
                    Instant first = at(day, 8 + r.nextInt(12), r.nextInt(60));
                    Instant last = back == 0 ? now.minus(Duration.ofMinutes(r.nextInt(180))) : first.plus(Duration.ofMinutes(5 + r.nextInt(180)));
                    if (first.isAfter(now)) {
                        first = now.minus(Duration.ofMinutes(200));
                    }
                    if (last.isBefore(first)) {
                        last = first;
                    }
                    jdbc.update("""
                            insert into site_visit_daily (day, visitor_key, user_id, views, first_at, last_at) values (?, ?, ?, ?, ?, ?)
                            """, day, "u:" + userId, userId, views, time(first), time(last));
                    for (int i = 0; i < views; i++) {
                        String route = routes[weighted(r, weights)];
                        pages.merge(day + "|" + route, 1, Integer::sum);
                    }
                    lastSeen = last;
                }
                if (lastSeen != null) {
                    jdbc.update("update app_user set last_seen_at = ? where id = ?", time(lastSeen), userId);
                }
            }
            // 没登录的访客（登录页、公告页）
            for (int back = 29; back >= 0; back--) {
                LocalDate day = today.minusDays(back);
                int guests = 3 + r.nextInt(10);
                for (int g = 0; g < guests; g++) {
                    int views = 1 + r.nextInt(3);
                    Instant first = at(day, 7 + r.nextInt(15), r.nextInt(60));
                    jdbc.update("""
                            insert into site_visit_daily (day, visitor_key, views, first_at, last_at) values (?, ?, ?, ?, ?)
                            """, day, String.format("a:demo%02d%02d", back, g), views, time(first), time(first));
                    pages.merge(day + "|login", views, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> page : pages.entrySet()) {
                String[] key = page.getKey().split("\\|");
                jdbc.update("insert into site_page_daily (day, route, views) values (?, ?, ?)",
                        LocalDate.parse(key[0]), key[1], page.getValue());
            }

            Instant created = now.minus(Duration.ofDays(120));
            AppProperties.Bootstrap bootstrap = properties.getBootstrap();
            long assistant = jdbc.queryForObject("""
                    insert into app_user (username, password_hash, display_name, email, role, status, admin_permissions,
                                          created_at, updated_at, last_login_at)
                    values ('assistant', ?, '李助教', 'assistant@example.com', 'ADMIN', 'ACTIVE', array['SCHOOLS', 'CONTENT']::varchar(32)[], ?, ?, ?)
                    returning id
                    """, Long.class, encoder.encode(bootstrap.getAdminPassword()), time(created), time(created),
                    time(now.minus(Duration.ofHours(5))));
            userIds.put("李助教", assistant);
            verifiedEmail(assistant, "assistant@example.com", created);
            privacy(assistant, false, "SCHOOL");

            long disabled = student("s2026disabled", "唐一鸣", 2026, "软件 2 班", studentNo(2026, "软件 2 班"),
                    now.minus(Duration.ofDays(20)), true, "SCHOOL");
            jdbc.update("update app_user set status = 'DISABLED' where id = ?", disabled);
            jdbc.update("""
                    insert into auth_event (user_id, username, event, ip, detail, created_at)
                    values (?, 's2026disabled', 'ACCOUNT_DISABLED', '127.0.0.1', ?, ?)
                    """, disabled, "管理员 " + bootstrap.getAdminDisplayName() + "：绑定他人账号刷榜，已核实",
                    time(now.minus(Duration.ofDays(2))));

            String adminName = bootstrap.getAdminDisplayName();
            Object[][] logs = {
                    {adminId, adminName, "POST", "/api/admin/schools/{id}/invite-code", "id=" + schoolId, 200, 27 * 24},
                    {adminId, adminName, "POST", "/api/admin/announcements", null, 200, 20 * 24},
                    {assistant, "李助教", "POST", "/api/admin/members/{id}/review", "id=12", 200, 9 * 24},
                    {assistant, "李助教", "POST", "/api/admin/members/{id}/review", "id=15", 200, 9 * 24 - 1},
                    {assistant, "李助教", "POST", "/api/admin/users/{id}/status", "id=" + disabled, 403, 3 * 24},
                    {adminId, adminName, "POST", "/api/admin/admins/{userId}", "userId=" + assistant, 200, 5 * 24},
                    {adminId, adminName, "POST", "/api/admin/users/{id}/status", "id=" + disabled, 200, 2 * 24},
                    {adminId, adminName, "POST", "/api/admin/sync/accounts/{accountId}/retry", "accountId=3", 200, 30},
                    {adminId, adminName, "POST", "/api/admin/sync/contests/refresh", null, 200, 26},
                    {assistant, "李助教", "POST", "/api/admin/announcements/{id}", "id=1", 200, 20},
                    {adminId, adminName, "DELETE", "/api/admin/nav/{id}", "id=2", 200, 6},
                    {adminId, adminName, "POST", "/api/admin/users/{id}/logout", "id=5", 200, 3},
                    {assistant, "李助教", "POST", "/api/admin/enrollment-years", null, 400, 2}};
            for (Object[] l : logs) {
                jdbc.update("""
                        insert into admin_audit_log (actor_id, actor_name, method, pattern, target, status, ip, created_at)
                        values (?, ?, ?, ?, ?, ?, '127.0.0.1', ?)
                        """, l[0], l[1], l[2], l[3], l[4], l[5], time(now.minus(Duration.ofHours((Integer) l[6]))));
            }
        }

        private int weighted(Random r, int[] weights) {
            int total = Arrays.stream(weights).sum();
            int pick = r.nextInt(total);
            for (int i = 0; i < weights.length; i++) {
                pick -= weights[i];
                if (pick < 0) {
                    return i;
                }
            }
            return weights.length - 1;
        }

        // ---------- 题单与训练活动（练习页暂不在导航里，数据先备好） ----------

        private void problemLists(Me me) {
            // Codeforces 新手 30 题：陈一鸣做过其中 18 道
            List<Long> starter = new ArrayList<>(me.cf().solvedIds.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("A")).map(Map.Entry::getValue).limit(18).toList());
            for (int contest = 2150; starter.size() < 30; contest--) {
                String key = contest + "A";
                if (!me.cf().solvedIds.containsKey(key)) {
                    starter.add(problem(CODEFORCES, cf(contest, 'A')));
                }
            }
            long starterId = problemList("CF-STARTER-30", "Codeforces 新手 30 题",
                    "全部是 800 分的 A 题：读懂题意、写对输入输出、处理好边界，适合刚开始用 Codeforces 的同学。",
                    "入门", CODEFORCES, null, "PUBLIC", 10, starter);

            List<FetchedProblem> luogu = luoguPool();
            List<Long> newbie = luogu.stream()
                    .filter(p -> p.problemKey().startsWith("P57") || Set.of("P1000", "P1001", "P1047").contains(p.problemKey()))
                    .map(p -> problem(LUOGU, p)).toList();
            long newbieId = problemList("LUOGU-NEWBIE", "洛谷新手村 · 分支与循环",
                    "洛谷入门题单里最常用的一组：分支结构、循环结构和简单模拟。", "入门", LUOGU, null, "PUBLIC", 20, newbie);

            List<Long> hot = Arrays.stream(LEETCODE).limit(20)
                    .map(row -> problem(LEETCODE_CN, new FetchedProblem(row[0], row[1],
                            "https://leetcode.cn/problems/" + row[0] + "/", null, null, "NONE", List.of(), false)))
                    .toList();
            problemList("LC-HOT-20", "力扣热题入门 20", "面试高频的基础题：数组、链表、栈、二叉树和简单动态规划。",
                    "专题", LEETCODE_CN, null, "PUBLIC", 30, hot);

            List<Long> dp = new ArrayList<>();
            for (String key : List.of("P1002", "P1048", "P1115", "P1216", "P1464")) {
                dp.add(problem(LUOGU, luogu.stream().filter(p -> p.problemKey().equals(key)).findFirst().orElseThrow()));
            }
            for (String[] row : LEETCODE) {
                if (Set.of("climbing-stairs", "maximum-subarray", "coin-change", "longest-increasing-subsequence").contains(row[0])) {
                    dp.add(problem(LEETCODE_CN, new FetchedProblem(row[0], row[1],
                            "https://leetcode.cn/problems/" + row[0] + "/", null, null, "NONE", List.of(), false)));
                }
            }
            for (int contest : new int[] {2031, 2044, 2059}) {
                dp.add(problem(CODEFORCES, cf(contest, 'C')));
            }
            long dpId = problemList("DP-BASIC", "动态规划入门", "从爬楼梯、数字三角形到背包：先会写状态和转移，再看优化。",
                    "专题", null, null, "PUBLIC", 40, dp);

            // 同学自己的题单：一个仅自己可见，一个分享到校内
            List<Long> wrong = me.cf().solvedIds.values().stream().skip(40).limit(6).toList();
            problemList("U-" + me.userId() + "-1", "我的错题本", "交了好几次才过的题，隔一周再做一遍。", null, CODEFORCES,
                    me.userId(), "PRIVATE", 100, wrong);
            long zhou = userIds.get("周子航");
            List<Long> divThree = new ArrayList<>();
            for (int contest = 2120; divThree.size() < 12; contest += 3) {
                divThree.add(problem(CODEFORCES, cf(contest, 'C')));
            }
            long sharedId = problemList("U-" + zhou + "-1", "国庆刷题计划（Div. 3 C 题）",
                    "每天两道 Div. 3 的 C 题，卡住超过 40 分钟就看题解。", null, CODEFORCES, zhou, "SCHOOL", 100, divThree);
            for (long listId : List.of(starterId, sharedId)) {
                jdbc.update("insert into problem_list_favorite (user_id, list_id, created_at) values (?, ?, ?)",
                        me.userId(), listId, time(now.minus(Duration.ofDays(2))));
            }

            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            activity("国庆集训 · 第一周", "每天至少做两道新手题，活动期间通过的才算进度。", starterId,
                    monday, monday.plusDays(14), true);
            activity("开学热身周", "用洛谷新手村把输入输出和分支循环练熟。", newbieId,
                    monday.minusDays(14), monday.minusDays(7), true);
            activity("十月周赛冲刺", "动态规划入门题单，配合每周的 Div. 3 / ABC。", dpId,
                    monday.plusDays(14), monday.plusDays(21), true);
            activity("期中复习（草稿）", "还在整理题目，暂不发布。", dpId, monday.plusDays(28), monday.plusDays(35), false);
        }

        private long problemList(String code, String title, String description, String category, Platform platform,
                                 Long ownerId, String scope, int sortOrder, List<Long> problems) {
            Instant created = now.minus(Duration.ofDays(20 + rnd.nextInt(30)));
            long id = jdbc.queryForObject("""
                    insert into problem_list (code, title, description, category, platform, source, visible, sort_order,
                                              created_by, owner_id, share_scope, school_id, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'MANUAL', true, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, Long.class, code, title, description, text(category), text(platform == null ? null : platform.name()),
                    sortOrder, ownerId == null ? adminId : ownerId, bigint(ownerId), scope,
                    bigint("SCHOOL".equals(scope) ? schoolId : null), time(created), time(created));
            List<Object[]> items = new ArrayList<>();
            for (int i = 0; i < problems.size(); i++) {
                items.add(new Object[] {id, problems.get(i), i + 1});
            }
            jdbc.batchUpdate("insert into problem_list_item (list_id, problem_id, position) values (?, ?, ?)", items,
                    new int[] {Types.BIGINT, Types.BIGINT, Types.INTEGER});
            return id;
        }

        private void activity(String title, String description, long listId, LocalDate start, LocalDate end, boolean published) {
            jdbc.update("""
                    insert into training_activity (title, description, list_id, start_at, end_at, published, created_by,
                                                   scope, school_id)
                    values (?, ?, ?, ?, ?, ?, ?, 'SCHOOL', ?)
                    """, title, description, listId, time(start.atStartOfDay(ZONE).toInstant()),
                    time(end.atStartOfDay(ZONE).toInstant()), published, adminId, schoolId);
        }

        // ---------- 同步任务、几种典型失败、平台限流 ----------

        private void failuresAndTasks(Me me) {
            jdbc.update("""
                    insert into sync_task (task_type, status, priority, attempt, next_run_at, duration_ms, created_at,
                                           updated_at, finished_at)
                    values ('CONTEST_REFRESH', 'SUCCESS', 50, 1, ?, 4210, ?, ?, ?)
                    """, time(now.minus(Duration.ofMinutes(21))), time(now.minus(Duration.ofMinutes(21))),
                    time(now.minus(Duration.ofMinutes(20))), time(now.minus(Duration.ofMinutes(20))));
            for (Acct acct : accounts) {
                if (acct != me.leetcode()) {
                    task(acct, "SUCCESS", 1, acct.lastSuccess, null, null);
                }
            }
            // 陈一鸣：前两天也同步过；力扣这次失败，等自动重试
            task(me.cf(), "SUCCESS", 1, me.cf().lastSuccess.minus(Duration.ofHours(24)), null, null);
            task(me.cf(), "SUCCESS", 1, me.cf().lastSuccess.minus(Duration.ofHours(48)), null, null);
            task(me.leetcode(), "SUCCESS", 1, me.leetcode().lastSuccess, null, null);
            jdbc.update("""
                    insert into sync_task (task_type, platform, account_id, status, priority, attempt, max_attempt,
                                           next_run_at, error_kind, error_msg, duration_ms, created_at, updated_at)
                    values ('ACCOUNT_SYNC', 'LEETCODE_CN', ?, 'PENDING', 100, 2, 5, ?, 'UPSTREAM_ERROR', ?, 1830, ?, ?)
                    """, me.leetcode().id, time(now.plus(Duration.ofMinutes(4))), "力扣接口暂时不可用（HTTP 503），稍后自动重试",
                    time(now.minus(Duration.ofMinutes(14))), time(now.minus(Duration.ofMinutes(10))));

            // 管理后台「同步状态」要看到的几种情况
            List<Acct> others = accounts.stream().filter(a -> a.userId != me.userId()).toList();
            Acct network = others.stream().filter(a -> a.platform == CODEFORCES).skip(20).findFirst().orElseThrow();
            fail(network, "FAILED", AccountDataStatusMapper.HISTORY, "FAILED", "NETWORK",
                    "连接 Codeforces 超时（30 秒），稍后自动重试", "PENDING");
            Acct parse = others.stream().filter(a -> a.platform == LUOGU).skip(3).findFirst().orElseThrow();
            fail(parse, "FAILED", AccountDataStatusMapper.TOTALS, "FAILED", "PARSE_ERROR",
                    "洛谷题单数量与账号总量不一致，未更新不完整数据", "FAILED");
            Acct partial = others.stream().filter(a -> a.platform == NOWCODER).findFirst().orElseThrow();
            fail(partial, "PARTIAL", AccountDataStatusMapper.TOTALS, "FAILED", "PARSE_ERROR",
                    "牛客练习主页结构与预期不符，需要重新核对解析规则", null);
            long gu = userIds.get(NAMES_2024[4]);
            Acct qoj = account(gu, QOJ, "szpu_gyh", "szpu_gyh", "RESOLVED", false, null,
                    now.minus(Duration.ofDays(6)), null);
            fail(qoj, "PAUSED", AccountDataStatusMapper.HISTORY, "NEEDS_CREDENTIAL", "CREDENTIAL_INVALID",
                    "QOJ 需要本人登录态，请先在账号页面提供", "FAILED");

            jdbc.update("insert into platform_throttle (platform, blocked_until, reason, updated_at) values ('LEETCODE_CN', ?, ?, ?)",
                    time(now.plus(Duration.ofMinutes(12))), "源站限流（HTTP 429），力扣全平台暂停 15 分钟",
                    time(now.minus(Duration.ofMinutes(3))));
        }

        private void task(Acct acct, String status, int attempt, Instant finished, String errorKind, String errorMsg) {
            Instant created = finished.minusSeconds(40 + rnd.nextInt(200));
            jdbc.update("""
                    insert into sync_task (task_type, platform, account_id, status, priority, attempt, max_attempt,
                                           next_run_at, error_kind, error_msg, duration_ms, created_at, updated_at, finished_at)
                    values ('ACCOUNT_SYNC', ?, ?, ?, 100, ?, 5, ?, ?, ?, ?, ?, ?, ?)
                    """, acct.platform.name(), acct.id, status, attempt, time(created), text(errorKind), text(errorMsg),
                    800 + rnd.nextInt(6000), time(created), time(finished), time(finished));
        }

        /** 照 SyncService.fail 的写法：数据项记失败原因，账号标为失败 / 部分成功 / 暂停，任务重试或结束。 */
        private void fail(Acct acct, String accountStatus, String item, String itemStatus, String kind, String message,
                          String taskStatus) {
            Instant failedAt = now.minus(Duration.ofMinutes(6 + rnd.nextInt(40)));
            jdbc.update("""
                    update platform_account set sync_status = ?, last_attempt_at = ?, last_error_kind = ?, last_error_msg = ?
                     where id = ?
                    """, accountStatus, time(failedAt), kind, message, acct.id);
            jdbc.update("""
                    insert into account_data_status (account_id, item, status, last_attempt_at, error_kind, error_msg)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (account_id, item) do update
                       set status = excluded.status, last_attempt_at = excluded.last_attempt_at,
                           error_kind = excluded.error_kind, error_msg = excluded.error_msg
                    """, acct.id, item, itemStatus, time(failedAt), kind, message);
            if (taskStatus != null) {
                boolean retry = "PENDING".equals(taskStatus);
                jdbc.update("""
                        insert into sync_task (task_type, platform, account_id, status, priority, attempt, max_attempt,
                                               next_run_at, error_kind, error_msg, duration_ms, created_at, updated_at,
                                               finished_at)
                        values ('ACCOUNT_SYNC', ?, ?, ?, 100, ?, 5, ?, ?, ?, ?, ?, ?, ?)
                        """, acct.platform.name(), acct.id, taskStatus, retry ? 1 : 5,
                        time(retry ? now.plus(Duration.ofMinutes(2)) : failedAt), kind, message, 1500 + rnd.nextInt(28000),
                        time(failedAt.minusSeconds(30)), time(failedAt), time(retry ? null : failedAt));
            }
        }
    }

    private static String pick(String[] values, int seed) {
        return values[Math.floorMod(seed, values.length)];
    }

    private static String token() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder token = new StringBuilder("AlgoHub-");
        Random random = new Random();
        for (int i = 0; i < 16; i++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    private static java.time.OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static SqlParameterValue time(Instant instant) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, offset(instant));
    }

    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    private static SqlParameterValue integer(Integer value) {
        return new SqlParameterValue(Types.INTEGER, value);
    }

    private static SqlParameterValue bigint(Long value) {
        return new SqlParameterValue(Types.BIGINT, value);
    }
}
