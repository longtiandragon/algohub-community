package com.szpu.algohub.admin.console;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.TimeSupport;

/**
 * 数据概览：用户数量、今天的访问与活跃、待办、近 N 天趋势、常用页面、平台账号和各学校成员。
 * 「今天」按北京时间；活跃用户只算学生（管理员的访问不记，见 VisitService）。蓝桥云课已下线，不计入平台账号。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final NamedParameterJdbcTemplate db;

    public AdminDashboardController(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> dashboard(@RequestParam(defaultValue = "30") int days) {
        if (days < 7 || days > 90) {
            throw ApiException.validation("趋势天数只能是 7 到 90 天");
        }
        LocalDate today = TimeSupport.today();
        LocalDate from = today.minusDays(days - 1L);
        Map<String, Object> p = new HashMap<>();
        p.put("today", today);
        p.put("from", from);
        p.put("week", today.minusDays(6));
        p.put("month", today.minusDays(29));
        p.put("todayStart", at(TimeSupport.campusDayStart(today)));
        p.put("weekStart", at(TimeSupport.campusDayStart(today.minusDays(6))));
        p.put("fromStart", at(TimeSupport.campusDayStart(from)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now());
        result.put("users", db.queryForMap("""
                select count(*) as total,
                       count(*) filter (where role = 'STUDENT') as students,
                       count(*) filter (where role = 'ADMIN') as admins,
                       count(*) filter (where status <> 'ACTIVE') as disabled,
                       count(*) filter (where created_at >= :todayStart) as "newToday",
                       count(*) filter (where created_at >= :weekStart) as "new7d",
                       count(*) filter (where role = 'STUDENT'
                           and exists (select 1 from verified_login_email e where e.user_id = u.id)) as "emailVerified",
                       count(*) filter (where role = 'STUDENT'
                           and exists (select 1 from school_member m where m.user_id = u.id and m.status = 'APPROVED')) as "withSchool",
                       count(*) filter (where role = 'STUDENT'
                           and exists (select 1 from platform_account a where a.user_id = u.id
                                          and a.ownership_status = 'VERIFIED' and a.platform <> 'LANQIAO')) as "withVerifiedAccount"
                  from app_user u
                """, p));
        result.put("activity", db.queryForMap("""
                select count(distinct user_id) filter (where day = :today) as "activeToday",
                       count(distinct user_id) filter (where day >= :week) as "active7d",
                       count(distinct user_id) as "active30d",
                       coalesce(sum(views) filter (where day = :today), 0) as "visitsToday",
                       count(*) filter (where day = :today) as "visitorsToday",
                       count(*) filter (where day = :today and visitor_key like 'a:%') as "guestVisitorsToday"
                  from site_visit_daily
                 where day >= :month
                """, p));
        result.put("pending", db.queryForMap("""
                select (select count(*) from school_member where status = 'PENDING') as members,
                       (select count(*) from ownership_appeal where status = 'PENDING') as appeals,
                       (select count(*) from platform_account where sync_status = 'FAILED' and platform <> 'LANQIAO') as "failedSync"
                """, p));
        result.put("trend", db.queryForList("""
                select to_char(d, 'YYYY-MM-DD') as day,
                       coalesce(v.visits, 0) as visits,
                       coalesce(v.visitors, 0) as visitors,
                       coalesce(v.active, 0) as "activeUsers",
                       coalesce(n.users, 0) as "newUsers"
                  from generate_series(cast(:from as date), cast(:today as date), interval '1 day') d
                  left join (select day, sum(views) as visits, count(*) as visitors, count(user_id) as active
                               from site_visit_daily where day >= :from group by day) v on v.day = d::date
                  left join (select (created_at at time zone 'Asia/Shanghai')::date as day, count(*) as users
                               from app_user where created_at >= :fromStart group by 1) n on n.day = d::date
                 order by d
                """, p));
        result.put("topPages", db.queryForList("""
                select route, views from site_page_daily where day = :today order by views desc, route limit 8
                """, p));
        result.put("platforms", db.queryForList("""
                select platform, count(*) as accounts, count(*) filter (where ownership_status = 'VERIFIED') as verified
                  from platform_account
                 where platform <> 'LANQIAO'
                 group by platform
                 order by accounts desc, platform
                """, p));
        result.put("schools", db.queryForList("""
                select s.id, s.name,
                       count(m.id) filter (where m.status = 'APPROVED') as members,
                       count(m.id) filter (where m.status = 'PENDING') as pending
                  from school s
                  left join school_member m on m.school_id = s.id
                 group by s.id, s.name
                 order by members desc, s.id
                """, p));
        return ApiResult.ok(result);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
