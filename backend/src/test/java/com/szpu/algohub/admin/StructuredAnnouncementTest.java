package com.szpu.algohub.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class StructuredAnnouncementTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired AppUserMapper users;
    @Autowired ObjectMapper json;
    LoginUser admin, student, schoolAdmin, restricted;
    long school;
    LoginUser actor(String role) {
        String name="announcement-"+UUID.randomUUID();
        Long id=db.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values (?,'x',? ,?,'ACTIVE') returning id",Long.class,name,name,role);
        db.update("insert into verified_login_email(user_id,email) values (?,?)",id,name+"@example.test");
        return new LoginUser(users.findById(id));
    }
    @BeforeEach void setup() {
        admin=actor("ADMIN"); student=actor("STUDENT"); schoolAdmin=actor("STUDENT"); restricted=actor("ADMIN");
        db.update("update app_user set admin_permissions=array['SCHOOLS']::varchar[] where id=?",restricted.getId());
        school=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"notice-school-"+UUID.randomUUID());
        db.update("insert into school_administrator(school_id,user_id,granted_by) values (?,?,?)",school,schoolAdmin.getId(),admin.getId());
    }
    String body(boolean published,List<String> items) throws Exception {
        return json.writeValueAsString(Map.of("title","平台功能更新测试","kind","UPDATE","published",published,"items",items));
    }
    long create(boolean published) throws Exception {
        String result=mvc.perform(post("/api/admin/announcements").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body(published,List.of("新增功能","修复问题"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").asLong();
    }
    @Test void orderedItemsRoundTripAndFirstPublicationDateSurvivesEdits() throws Exception {
        long id=create(false);
        assertThat(db.queryForObject("select published_at from announcement where id=?",java.sql.Timestamp.class,id)).isNull();
        String drafts=mvc.perform(get("/api/announcements").with(user(student))).andReturn().getResponse().getContentAsString();
        assertThat(drafts).doesNotContain("平台功能更新测试");
        mvc.perform(post("/api/admin/announcements/"+id).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body(true,List.of("修复问题","新增功能")))).andExpect(status().isOk());
        assertThat(db.queryForObject("select published_at from announcement where id=?",java.sql.Timestamp.class,id)).isNotNull();
        db.update("update announcement set published_at='2026-01-02T03:04:05Z' where id=?",id);
        for (boolean published:List.of(false,true)) {
            mvc.perform(post("/api/admin/announcements/"+id).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body(published,List.of("修复问题","<script>不是HTML</script>")))).andExpect(status().isOk());
        }
        var response=json.readTree(mvc.perform(get("/api/announcements").with(user(student))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var notice=java.util.stream.StreamSupport.stream(response.spliterator(),false).filter(n->n.path("id").asLong()==id).findFirst().orElseThrow();
        assertThat(notice.path("items").get(0).asText()).isEqualTo("修复问题");
        assertThat(notice.path("items").get(1).asText()).isEqualTo("<script>不是HTML</script>");
        assertThat(notice.path("publishedAt").asText()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(notice.path("kind").asText()).isEqualTo("UPDATE");
    }
    @Test void onlyPlatformContentAdminsCanWriteThroughEitherRoute() throws Exception {
        long id=create(true);
        String schoolBody="{\"title\":\"校内通知\",\"content\":\"内容\",\"published\":true}";
        for (var actor:List.of(student,schoolAdmin,restricted)) {
            mvc.perform(post("/api/admin/announcements").with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(body(true,List.of("更新")))).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/announcements/"+id).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(body(false,List.of("修改")))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/admin/announcements/"+id).with(user(actor))).andExpect(status().isForbidden());
            String path="/api/school-admin/"+school+"/announcements";
            mvc.perform(post(path).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(schoolBody)).andExpect(status().isForbidden());
            mvc.perform(post(path+"/"+id).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(schoolBody)).andExpect(status().isForbidden());
            mvc.perform(delete(path+"/"+id).with(user(actor))).andExpect(status().isForbidden());
        }
        assertThat(db.queryForObject("select title from announcement where id=?",String.class,id)).isEqualTo("平台功能更新测试");
    }
    @Test void rejectsEmptyOversizedAndMalformedUpdateItems() throws Exception {
        for (List<String> items:List.of(List.<String>of(),List.of(" "),List.of("x".repeat(1001)),Collections.nCopies(31,"x"),Collections.nCopies(11,"x".repeat(1000)))) {
            mvc.perform(post("/api/admin/announcements").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body(true,items))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/admin/announcements").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"更新\",\"kind\":\"UPDATE\",\"items\":[null]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void versionRoundTripsAndCanBeClearedWithoutChangingPublicationDate() throws Exception {
        var input=json.createObjectNode().put("title","版本测试").put("kind","TEXT").put("content","正文").put("published",true).put("version"," v2026.10.30 ");
        String response=mvc.perform(post("/api/admin/announcements").with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).path("data").asLong();
        var published=db.queryForObject("select published_at from announcement where id=?",java.sql.Timestamp.class,id);
        for(String path:List.of("/api/admin/announcements","/api/announcements")) {
            var rows=json.readTree(mvc.perform(get(path).with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            var row=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(n->n.path("id").asLong()==id).findFirst().orElseThrow();
            assertThat(row.path("version").asText()).isEqualTo("v2026.10.30");
        }
        for(String invalid:List.of("v".repeat(33),"<script>","v1 2")) {
            input.put("version",invalid);
            mvc.perform(post("/api/admin/announcements/"+id).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isBadRequest());
        }
        input.put("version","");
        mvc.perform(post("/api/admin/announcements/"+id).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isOk());
        assertThat(db.queryForObject("select version from announcement where id=?",String.class,id)).isNull();
        assertThat(db.queryForObject("select published_at from announcement where id=?",java.sql.Timestamp.class,id)).isEqualTo(published);
    }
}
