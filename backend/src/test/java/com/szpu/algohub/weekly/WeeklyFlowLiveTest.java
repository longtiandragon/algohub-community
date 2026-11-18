package com.szpu.algohub.weekly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.*;

/** Opt-in live source smoke test through the HTTP endpoints, persistent queue and production parser. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
@EnabledIfSystemProperty(named="algohub.live-weekly",matches="true")
class WeeklyFlowLiveTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired WeeklyRefresh refresh;
    @Autowired ObjectMapper json;

    @Test void codeforces() throws Exception {
        verify("https://codeforces.com/contest/2278","CODEFORCES","acmfht","acmfht","VIRTUAL",5);
    }

    @Test void nowcoder() throws Exception {
        verify("https://ac.nowcoder.com/acm/contest/141882","NOWCODER","996283503","996283503","STAR",6);
    }

    private void verify(String url,String platform,String handle,String uid,String kind,int solved) throws Exception {
        String label=UUID.randomUUID().toString();
        long school=db.queryForObject("insert into school(name) values(?) returning id",Long.class,label);
        long actor=db.queryForObject("insert into app_user(username,display_name) values(?,?) returning id",Long.class,label,label);
        db.update("insert into school_member(school_id,user_id,enrollment_year,status) values(?,?,2026,'APPROVED')",school,actor);
        db.update("insert into school_administrator(user_id,school_id) values(?,?)",actor,school);
        db.update("insert into verified_login_email(user_id,email) values(?,?)",actor,label+"@test.invalid");
        db.update("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status) values(?,?,?,?,'VERIFIED')",
            actor,platform,uid,handle);
        var principal=new LoginUser(users.findById(actor));
        String endpoint="/api/weekly/"+school;
        mvc.perform(get(endpoint+"/preview").with(user(principal)).param("url",url)).andExpect(status().isOk());
        String body=json.writeValueAsString(Map.of("url",url,"targets",Map.of("2026",4)));
        var posted=mvc.perform(post(endpoint).with(user(principal)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse();
        long id=json.readTree(posted.getContentAsByteArray()).get("data").longValue();
        refresh.discover();
        assertThat(refresh.next()).isTrue();
        var listing=mvc.perform(get(endpoint).with(user(principal))).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode item=json.readTree(listing.getContentAsByteArray()).at("/data/0");
        assertThat(item.has("rows")).isFalse();
        assertThat(item.at("/students/0/solved").asInt()).isEqualTo(solved);
        assertThat(item.at("/students/0/status").asText()).isEqualTo("MET");
        var details=mvc.perform(get(endpoint+"/"+id).with(user(principal))).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode rows=json.readTree(details.getContentAsByteArray()).at("/data/rows");
        assertThat(rows.get(0).path("kind").asText()).isEqualTo(kind);
        if(kind.equals("VIRTUAL")) assertThat(rows.get(1).path("kind").asText()).isEqualTo("PRACTICE");
    }
}
