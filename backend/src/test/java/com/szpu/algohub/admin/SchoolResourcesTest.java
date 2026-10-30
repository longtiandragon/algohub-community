package com.szpu.algohub.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SchoolResourcesTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void resourcesFollowApprovedSchoolAndKeepAdminSourceUnchanged() throws Exception {
        Long id = db.queryForObject("insert into app_user(username,display_name,role,status,onboarding_completed) values (?,'资源测试','STUDENT','ACTIVE',true) returning id", Long.class, "resources-" + System.nanoTime());
        db.update("insert into verified_login_email(user_id,email) values (?,?)", id, id + "@example.test");
        AppUser app = new AppUser(); app.setId(id); app.setRole("STUDENT"); app.setStatus("ACTIVE");
        LoginUser login = new LoginUser(app);
        db.update("delete from announcement where kind='LINK' and (url like 'https://c16h22o4.github.io/%' or url like 'https://hei-maom.github.io/xcpcrating/%')");
        db.update("insert into announcement(title,content,published,kind,url) values ('旧校蓝桥杯','旧学校说明',true,'LINK','https://c16h22o4.github.io/?school=old'),('旧校XCPC','旧学校说明',true,'LINK','https://hei-maom.github.io/xcpcrating/#/school/old')");
        assertThat(resources(login).toString()).contains("https://xcpc.link/", "算法竞赛资源导航").doesNotContain("c16h22o4", "xcpcrating");
        Long school = db.queryForObject("insert into school(name) values ('深圳职业技术大学') returning id", Long.class);
        db.update("insert into school_member(user_id,school_id,status) values (?,?,'PENDING')", id, school);
        assertThat(resources(login).toString()).contains("https://xcpc.link/", "算法竞赛资源导航").doesNotContain("c16h22o4", "xcpcrating");
        db.update("update school_member set status='APPROVED' where user_id=?", id);
        String bound = URLDecoder.decode(resources(login).toString(), StandardCharsets.UTF_8);
        assertThat(bound).contains("本校XCPC竞赛情况", "本校蓝桥杯获奖情况", "school=深圳职业技术", "#/school/深圳职业技术大学").doesNotContain("旧学校说明", "深圳职业技术学院");
        db.update("update school set name='中山大学' where id=?", school);
        String shortName = URLDecoder.decode(resources(login).toString(), StandardCharsets.UTF_8);
        assertThat(shortName).contains("school=中山大学", "#/school/中山大学").doesNotContain("深圳职业技术学院");
        assertThat(db.queryForObject("select count(*) from announcement where title in ('旧校蓝桥杯','旧校XCPC')", Integer.class)).isEqualTo(2);
    }

    private JsonNode resources(LoginUser login) throws Exception {
        return json.readTree(mvc.perform(get("/api/announcements").with(user(login))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
