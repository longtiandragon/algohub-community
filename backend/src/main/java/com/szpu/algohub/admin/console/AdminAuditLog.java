package com.szpu.algohub.admin.console;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.szpu.algohub.auth.LoginUser;

/** 管理操作日志：写入（AdminConsoleConfig 里的拦截器调用）和分页查询。 */
@Service
public class AdminAuditLog {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLog.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate db;

    public AdminAuditLog(JdbcTemplate jdbc, NamedParameterJdbcTemplate db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** 记一条；写失败只记日志，不影响原来的请求 */
    public void record(LoginUser actor, String method, String pattern, String target, int status, String ip) {
        try {
            jdbc.update("""
                    insert into admin_audit_log (actor_id, actor_name, method, pattern, target, status, ip)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, actor.getId(), cut(actor.getDisplayName() == null ? actor.getUsername() : actor.getDisplayName(), 64),
                    cut(method, 8), cut(pattern, 160), cut(target, 160), status, cut(ip, 64));
        } catch (RuntimeException e) {
            log.warn("管理操作日志写入失败（{} {}）：{}", method, pattern, e.getMessage());
        }
    }

    /** 按时间倒序分页；q 匹配接口模板和对象 */
    public Map<String, Object> page(long actorId, String q, int page, int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("actorId", actorId);
        p.put("q", q);
        String where = """
                 where (:actorId = 0 or actor_id = :actorId)
                   and (:q = '' or position(lower(:q) in lower(method || ' ' || pattern || ' ' || coalesce(target, ''))) > 0)
                """;
        Long total = db.queryForObject("select count(*) from admin_audit_log" + where, p, Long.class);
        int actualPage = Math.min(page, Math.max(1, (int) ((total + size - 1) / size)));
        p.put("size", size);
        p.put("offset", (actualPage - 1) * size);
        List<Map<String, Object>> rows = db.queryForList("""
                select id, actor_id as "actorId", actor_name as "actorName", method, pattern, target, status, ip,
                       created_at as "createdAt"
                  from admin_audit_log
                """ + where + " order by created_at desc, id desc limit :size offset :offset", p);
        return Map.of("rows", rows, "total", total, "page", actualPage, "size", size);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
