package com.szpu.algohub.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminManagementTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired AppUserMapper users;
    @Autowired ObjectMapper json;
    String token(String role) {
        String name = "mgmt" + Long.toString(System.nanoTime(), 36);
        Long id = db.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x',?,?) returning id", Long.class, name,name,role);
        db.update("insert into verified_login_email(user_id,email) values (?,?)", id, name+"@test.invalid");
        return "Bearer " + auth.issueSession(users.findById(id), "127.0.0.1", "JUnit", "LOGIN_SUCCESS").view().accessToken();
    }
    @Test void studentSchoolClassificationPaginationAndPermissions() throws Exception {
        String admin=token("ADMIN"), student=token("STUDENT");
        Long school=db.queryForObject("insert into school(name) values ('目录测试学校') returning id", Long.class);
        for (int n=0;n<4;n++) {
            String name="directory-fixture-"+n;
            Long id=db.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x',?,'STUDENT') returning id",Long.class,name,name);
            if(n<3) db.update("insert into school_member(school_id,user_id,status,enrollment_year,class_name) values (?,?,?,2026,'测试班')",school,id,List.of("APPROVED","PENDING","REJECTED").get(n));
        }
        mvc.perform(get("/api/admin/students").header("Authorization",student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/students").header("Authorization",admin).param("q","directory-fixture-").param("size","2").param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4)).andExpect(jsonPath("$.data.rows.length()").value(2))
            .andExpect(jsonPath("$.data.summary.approved").value(1)).andExpect(jsonPath("$.data.summary.unaffiliated").value(3));
        mvc.perform(get("/api/admin/students").header("Authorization",admin).param("q","directory-fixture-").param("unaffiliated","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/admin/students").header("Authorization",admin).param("schoolId",school.toString()).param("membership","PENDING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/admin/students").header("Authorization",admin).param("q","directory-fixture-").param("membership","NONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/admin/students").header("Authorization",admin).param("size","101")).andExpect(status().isBadRequest());
    }
    @Test void resourceCardsAreManagedAndUnsafeLinksRejected() throws Exception {
        String admin=token("ADMIN");
        String resource="{\"title\":\"资源闭环测试\",\"content\":\"说明\",\"published\":true,\"kind\":\"LINK\",\"url\":\"https://example.com/\"}";
        var response=mvc.perform(post("/api/admin/announcements").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(resource)).andExpect(status().isOk()).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).path("data").asLong();
        mvc.perform(get("/api/announcements").header("Authorization",admin)).andExpect(jsonPath("$.data[?(@.id == "+id+")].url").value("https://example.com/"));
        mvc.perform(post("/api/admin/announcements/"+id).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(resource.replace("https://example.com/","javascript:alert(1)"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/announcements/"+id).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(resource.replace("true","false"))).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/announcements").header("Authorization",admin)).andReturn().getResponse().getContentAsString()).doesNotContain("资源闭环测试");
    }
    @Test void schoolAndInviteLifecycle() throws Exception {
        String admin = token("ADMIN");
        var response = mvc.perform(post("/api/admin/schools").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"管理闭环测试学校\"}")).andExpect(status().isOk()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).path("data").asLong();
        mvc.perform(post("/api/admin/schools/"+id).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"学校新名称\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/schools/"+id+"/invite-code").header("Authorization",admin)).andExpect(status().isOk());
        assertThat(db.queryForObject("select invite_code from school where id=?",String.class,id)).hasSize(8);
        mvc.perform(delete("/api/admin/schools/"+id+"/invite-code").header("Authorization",admin)).andExpect(status().isOk());
        assertThat(db.queryForObject("select invite_code from school where id=?",String.class,id)).isNull();
        Long userId = db.queryForObject("select id from app_user order by id desc limit 1", Long.class);
        db.update("insert into school_member(school_id,user_id,status) values (?,?,'APPROVED')", id,userId);
        mvc.perform(delete("/api/admin/schools/"+id).header("Authorization",admin)).andExpect(status().isConflict());
        db.update("delete from school_member where school_id=?", id);
        mvc.perform(delete("/api/admin/schools/"+id).header("Authorization",admin)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from school where id=?",Integer.class,id)).isZero();
    }
    @Test void announcementDraftPublishWithdrawDeleteAndRoleBoundary() throws Exception {
        String admin=token("ADMIN"), student=token("STUDENT");
        String draft="{\"title\":\"仅管理员可见测试\",\"content\":\"正文\",\"published\":false}";
        mvc.perform(post("/api/admin/announcements").header("Authorization",student).contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/announcements").header("Authorization",student)).andExpect(status().isForbidden());
        var result=mvc.perform(post("/api/admin/announcements").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isOk()).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).path("data").asLong();
        String endpoint="/api/admin/announcements/"+id;
        assertThat(mvc.perform(get("/api/announcements").header("Authorization",student)).andReturn().getResponse().getContentAsString()).doesNotContain("仅管理员可见测试");
        mvc.perform(post(endpoint).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(draft.replace("false","true"))).andExpect(status().isOk());
        mvc.perform(get("/api/announcements").header("Authorization",student)).andExpect(jsonPath("$.data[?(@.id == "+id+")].title").value("仅管理员可见测试"));
        mvc.perform(post(endpoint).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/announcements").header("Authorization",student)).andReturn().getResponse().getContentAsString()).doesNotContain("仅管理员可见测试");
        mvc.perform(delete(endpoint).header("Authorization",student)).andExpect(status().isForbidden());
        mvc.perform(delete(endpoint).header("Authorization",admin)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from announcement where id=?",Integer.class,id)).isZero();
    }
}
