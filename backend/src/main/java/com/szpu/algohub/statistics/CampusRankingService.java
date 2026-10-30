package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.school.SchoolService;

@Service
public class CampusRankingService {
    private final CampusRankingMapper mapper;
    private final SchoolService schools;
    private final PlatformRegistry registry;

    public CampusRankingService(CampusRankingMapper mapper, SchoolService schools, PlatformRegistry registry) {
        this.mapper = mapper;
        this.schools = schools;
        this.registry = registry;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> ranking(Long userId, String type, String metric, String platform,
            Integer year, String className, int limit) {
        return ranking(userId, "SCHOOL", type, metric, platform, year, className, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> ranking(Long userId, String scope, String type, String metric, String platform,
            Integer year, String className, int limit) {
        if (!Set.of("TOTAL", "WEEK", "MONTH", "YEAR").contains(type)
                || !Set.of("FIRST_AC", "UNIQUE_AC", "AC_SUBMISSIONS", "SUBMISSIONS").contains(metric)
                || !Set.of("SCHOOL", "GLOBAL").contains(scope)
                || ("GLOBAL".equals(scope) && className != null)
                || limit < 1 || limit > 200 || (year != null && (year < 2000 || year > 2100))) {
            throw ApiException.validation("榜单筛选参数无效");
        }
        LocalDate to = TimeSupport.today();
        // 近一年：含今天共 365 天
        LocalDate from = to.minusDays("YEAR".equals(type) ? 364 : "MONTH".equals(type) ? 29 : 6);
        return query(userId, scope, type, metric, platform, year, className, limit, from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> monthly(Long userId, YearMonth month, Integer year) {
        LocalDate end = month.atEndOfMonth();
        if (end.isAfter(TimeSupport.today())) end = TimeSupport.today();
        return query(userId, "SCHOOL", "CALENDAR_MONTH", "FIRST_AC", null, year, null, 1, month.atDay(1), end);
    }

    private Map<String, Object> query(Long userId, String scope, String type, String metric, String platform,
            Integer year, String className, int limit, LocalDate from, LocalDate to) {
        // 学校只按服务端成员记录确定，不接受前端传入
        Long schoolId = schools.approvedSchoolId(userId);
        var selected = mapper.rows(userId, schoolId, scope, type, metric, from, to,
                platform, year, className, limit);
        var mine = selected.stream().filter(r -> number(r, "userId") == userId).findFirst().orElse(null);
        var result = new LinkedHashMap<String, Object>();
        result.put("scope", scope);
        result.put("type", type);
        result.put("metric", "TOTAL".equals(type) ? "SOLVED_TOTAL" : metric);
        result.put("description", "TOTAL".equals(type) ? "累计通过题数"
                : "CALENDAR_MONTH".equals(type) ? "自然月（" + from + " 至 " + to + "，北京时间）"
                : "YEAR".equals(type) ? "近一年（" + from + " 至 " + to + "，北京时间）"
                : "近 " + ("MONTH".equals(type) ? 30 : 7) + " 天（" + from + " 至 " + to + "，北京时间）");
        if (!"TOTAL".equals(type)) { result.put("from", from); result.put("to", to); }
        result.put("rows", selected.stream().filter(r -> number(r, "position") <= limit).toList());
        result.put("participantCount", selected.isEmpty() ? 0 : number(selected.getFirst(), "participantCount"));
        result.put("myRank", mine);
        result.put("neighbors", mine == null ? List.of() : selected.stream()
                .filter(r -> Math.abs(number(r, "position") - number(mine, "position")) <= 2).toList());
        NotRanked why = "SCHOOL".equals(scope) && schoolId == null
                ? new NotRanked("NO_SCHOOL", "请先加入学校后查看校内排行")
                : mine == null ? notRankedReason(userId, schoolId, scope, type, metric, platform, year, className) : null;
        result.put("myRankNote", why == null ? "同分并列；名次基于当前筛选范围内全部参与者计算" : why.text());
        // 没上榜的原因代码，页面按它给一个动作（去验证、去设置、回到自己的范围……）；上榜时为空
        result.put("myRankReason", why == null ? null : why.code());
        result.put("coverage", mapper.coverage(schoolId, scope, type, metric, platform, year, className));
        if ("SCHOOL".equals(scope) && schoolId != null && !"CALENDAR_MONTH".equals(type)) {
            // 年级、班级筛选的选项：只列参与排行的同学所在的，按所选年级给班级
            result.put("yearOptions", mapper.years(schoolId));
            result.put("classOptions", mapper.classes(schoolId, year));
        }
        result.put("note", ("GLOBAL".equals(scope)
                ? "总榜统计全站用户（包含未加入学校者），展示昵称、学校和成绩；"
                : "校内榜仅统计本人所在学校已通过的成员；")
                + "仅计入已验证归属的账号，排除关闭排行展示或档案仅自己可见的用户。"
                + "区间榜只使用提交记录，不将快照增量当作新增通过；无法提供此指标的账号不以零分参榜。"
                + "累计榜统计已同步的通过数；逐题明细去重仅合并已确认的跨平台映射，总量快照无法逐题去重。"
                + "同分并列，后续名次跳位。");
        return result;
    }

    /** 没上榜的原因：code 给页面决定动作，text 直接显示 */
    private record NotRanked(String code, String text) {}

    /**
     * 没上榜时按参与资格的判断顺序找出挡住的那一条（隐私 → 归属验证 → 年级 / 班级 / 平台筛选 → 指标数据），
     * 直接说原因，而不是列一串可能性让人自己排查。
     * code：NO_SCHOOL 没选学校、HIDDEN 关了参与排行、PRIVATE_PROFILE 档案仅自己可见、NO_VERIFIED 没有验证过归属的账号、
     * YEAR / CLASS / PLATFORM 不在当前筛选范围、NO_DATA 账号有但这段时间没有可计入的数据。
     */
    private NotRanked notRankedReason(Long userId, Long schoolId, String scope, String type, String metric,
            String platform, Integer year, String className) {
        Map<String, Object> me = "GLOBAL".equals(scope) ? mapper.viewerPrivacy(userId)
                : mapper.viewerMembership(userId, schoolId);
        if (me == null) return new NotRanked("NO_SCHOOL", "请先加入学校后查看校内排行");
        if (!Boolean.TRUE.equals(me.get("showOnRanking"))) return new NotRanked("HIDDEN", "你已关闭参与排行，所以不在榜上");
        if ("PRIVATE".equals(me.get("profileVisibility"))) return new NotRanked("PRIVATE_PROFILE", "你的档案设为仅自己可见，不参与排行");
        List<String> verified = new ArrayList<>();
        List<String> claimable = new ArrayList<>();
        for (Map<String, Object> account : mapper.viewerAccounts(userId)) {
            String code = (String) account.get("platform");
            String ownership = (String) account.get("ownershipStatus");
            if ("VERIFIED".equals(ownership)) verified.add(code);
            else if (registry.proofOf(Platform.parse(code)) != null) claimable.add(code);
        }
        String claimHint = claimable.isEmpty() ? "" : "；" + spaced(names(claimable)).stripLeading() + "账号还是待认领，验证归属后才计入";
        if (verified.isEmpty()) return new NotRanked("NO_VERIFIED", "你还没有验证过归属的账号，待认领和个人关注的账号不计入排行" + claimHint);
        Object myYear = me.get("enrollmentYear");
        if (year != null && (myYear == null || ((Number) myYear).intValue() != year)) {
            return new NotRanked("YEAR", myYear == null ? "你没有填写入学年级，不在年级筛选范围内" : "你是 " + myYear + " 级，不在当前年级筛选范围内");
        }
        if (className != null && !className.equals(me.get("className"))) return new NotRanked("CLASS", "你不在当前班级筛选范围内");
        if (platform != null && !verified.contains(platform)) {
            return new NotRanked("PLATFORM", "你在" + spaced(Platform.parse(platform).getDisplayName()) + "没有验证过归属的账号" + claimHint);
        }
        String sourceScope = "你验证过归属的" + spaced(names(platform == null ? verified : List.of(platform))) + "账号";
        String why = "TOTAL".equals(type) ? "MATIJI".equals(platform) ? "还没有同步到累计通过总数" : "还没有同步到逐题明细"
                : "FIRST_AC".equals(metric) ? "这段时间没有能确定首次通过时间的记录"
                : "这段时间没有可计入的提交记录";
        return new NotRanked("NO_DATA", sourceScope + why + claimHint);
    }

    private static String names(List<String> platforms) {
        return String.join("、", platforms.stream().distinct().map(p -> Platform.parse(p).getDisplayName()).toList());
    }

    /** 中文里夹英文平台名时两侧留空格：「你在 AtCoder 没有」「验证过归属的力扣（中国站）账号」 */
    private static String spaced(String text) {
        String out = text;
        if (!out.isEmpty() && Character.isLetterOrDigit(out.charAt(0)) && out.charAt(0) < 128) out = " " + out;
        if (!out.isEmpty() && Character.isLetterOrDigit(out.charAt(out.length() - 1)) && out.charAt(out.length() - 1) < 128) out = out + " ";
        return out;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
