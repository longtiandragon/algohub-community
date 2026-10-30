package com.szpu.algohub.admin;

import java.util.List;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.szpu.algohub.admin.console.AdminAccess;
import com.szpu.algohub.admin.console.AdminPermission;
import com.szpu.algohub.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.common.ApiException;

@RestController
public class AnnouncementController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AdminAccess access;
    public AnnouncementController(JdbcTemplate db, ObjectMapper json, AdminAccess access) {
        this.db = db; this.json = json; this.access = access;
    }
    public record Notice(Long id, String title, String content, boolean published, Instant updatedAt,
                         String kind, String url, List<String> items, Instant publishedAt, String version) {}
    public record Input(@NotBlank @Size(max=120) String title,
                        @Size(max=10000) String content, boolean published,
                        @Size(max=16) String kind, @Size(max=512) String url,
                        @Size(max=30) List<@NotBlank @Size(max=1000) String> items,
                        @Size(max=32) String version) {}
    private void requirePublisher() {
        var grant = access.of(CurrentUser.requireId());
        if (!grant.active() || !grant.has(AdminPermission.CONTENT))
            throw ApiException.forbidden("只有具备内容管理权限的平台管理员可以管理公告");
    }
    private String version(Input input) {
        if (input.version()==null || input.version().isBlank()) return null;
        String value=input.version().trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,31}")) throw ApiException.validation("版本号限 32 位字母、数字、点、横线、下划线或加号");
        return value;
    }
    private String kind(Input input) {
        String kind = input.kind()==null ? "TEXT" : input.kind();
        if (!List.of("TEXT","LINK","UPDATE").contains(kind)) throw ApiException.validation("内容类型不正确");
        if (kind.equals("UPDATE")) {
            if (input.items()==null || input.items().isEmpty()) throw ApiException.validation("请至少添加一条更新内容");
            if (input.items().stream().mapToInt(String::length).sum()>10000) throw ApiException.validation("更新内容总长度不能超过 10000 字");
        } else if (input.content()==null || input.content().isBlank()) throw ApiException.validation("请填写正文或资源说明");
        if (kind.equals("LINK")) AdminController.requireWebUrl(input.url());
        return kind;
    }
    private String content(Input input, String kind) {
        return kind.equals("UPDATE") ? String.join("\n",input.items().stream().map(String::trim).toList()) : input.content().trim();
    }
    private String items(Input input, String kind) {
        try { return json.writeValueAsString(kind.equals("UPDATE") ? input.items().stream().map(String::trim).toList() : List.of()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private List<String> readItems(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("读取公告条目失败",e); }
    }

    private List<Notice> list(boolean all) {
        String scope = all ? "school_id is null" : """
                published = true and (school_id is null
                or school_id in (select school_id from school_member where user_id=? and status='APPROVED')
                or school_id in (select school_id from school_administrator where user_id=?))
                """;
        Object[] params = all ? new Object[0] : new Object[] {
                com.szpu.algohub.auth.CurrentUser.requireId(), com.szpu.algohub.auth.CurrentUser.requireId() };
        return db.query("select * from announcement where " + scope + " order by coalesce(published_at, updated_at) desc, id desc",
            (rs, n) -> new Notice(rs.getLong("id"), rs.getString("title"), rs.getString("content"),
                rs.getBoolean("published"), rs.getTimestamp("updated_at").toInstant(), rs.getString("kind"), rs.getString("url"), readItems(rs.getString("items")),
                rs.getTimestamp("published_at")==null ? null : rs.getTimestamp("published_at").toInstant(), rs.getString("version")), params);
    }

    @GetMapping("/api/announcements")
    public ApiResult<List<Notice>> published() {
        Long userId = com.szpu.algohub.auth.CurrentUser.requireId();
        List<String> schools = db.queryForList("select s.name from school_member m join school s on s.id=m.school_id where m.user_id=? and m.status='APPROVED' order by m.id limit 1", String.class, userId);
        String school = schools.isEmpty() ? null : schools.getFirst();
        List<Notice> result = new java.util.ArrayList<>();
        for (Notice notice : list(false)) {
            boolean lanqiao = "LINK".equals(notice.kind()) && notice.url() != null && notice.url().startsWith("https://c16h22o4.github.io/");
            boolean xcpc = "LINK".equals(notice.kind()) && notice.url() != null && notice.url().startsWith("https://hei-maom.github.io/xcpcrating/");
            if (!lanqiao && !xcpc) { result.add(notice); continue; }
            if (school == null) continue;
            String query = lanqiao && school.codePointCount(0, school.length()) >= 8
                    ? school.substring(0, school.offsetByCodePoints(0, 6)) : school;
            String url = lanqiao ? "https://c16h22o4.github.io/?mode=person&school=" + encode(query)
                    : "https://hei-maom.github.io/xcpcrating/#/school/" + encode(school);
            result.add(new Notice(notice.id(), lanqiao ? "本校蓝桥杯获奖情况" : "本校XCPC竞赛情况",
                    lanqiao ? "查看本校蓝桥杯获奖记录。" : "查看本校 XCPC 参赛表现与成绩。", true, notice.updatedAt(), "LINK", url, List.of(), notice.publishedAt(), notice.version()));

        }
        return ApiResult.ok(result);
    }
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
    @GetMapping("/api/admin/announcements")
    public ApiResult<List<Notice>> all() { requirePublisher(); return ApiResult.ok(list(true)); }
    @PostMapping("/api/admin/announcements")
    public ApiResult<Long> create(@Valid @RequestBody Input input) {
        requirePublisher();
        String kind=kind(input);
        return ApiResult.ok(db.queryForObject("""
            insert into announcement(title,content,published,kind,url,items,published_at,version)
            values (?,?,?,?,?,?::jsonb,case when ? then now() else null end,?) returning id
            """, Long.class, input.title().trim(), content(input,kind), input.published(),kind,
            kind.equals("LINK")?input.url().trim():null,items(input,kind),input.published(),version(input)));
    }
    @PostMapping("/api/admin/announcements/{id}")
    public ApiResult<Void> update(@PathVariable Long id, @Valid @RequestBody Input input) {
        requirePublisher();
        String kind=kind(input);
        if (db.update("""
            update announcement set title=?,content=?,published=?,kind=?,url=?,items=?::jsonb,updated_at=now(),
            published_at=case when ? then coalesce(published_at,now()) else published_at end,version=?
            where id=? and school_id is null
            """, input.title().trim(), content(input,kind), input.published(),kind,
            kind.equals("LINK")?input.url().trim():null,items(input,kind),input.published(),version(input),id)==0)
            throw ApiException.notFound("公告不存在");
        return ApiResult.ok(null);
    }
    @DeleteMapping("/api/admin/announcements/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        requirePublisher();
        if (db.update("delete from announcement where id=? and school_id is null", id)==0) throw ApiException.notFound("公告不存在");
        return ApiResult.ok(null);
    }
}
