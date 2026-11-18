package com.szpu.algohub.weekly;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeeklyPublishingTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired ObjectMapper json;
    @Autowired WeeklyRefresh refresh;
    @MockitoBean WeeklySource source;

    private long school,foreign,manager,leader,student;
    private static final String URL="https://codeforces.com/contest/2278";
    private static final String BODY="""
        {"url":"https://codeforces.com/contest/2278","targets":{"2025":2,"2026":1},"weekStart":"2026-11-18"}
        """;
    private final WeeklySource.Meta meta=new WeeklySource.Meta("CODEFORCES","2278","Round","Div.3",URL,List.of(
        new WeeklySource.Task("A","2278A"),new WeeklySource.Task("B","2278B")));

    private long school() {
        return db.queryForObject("insert into school(name) values (?) returning id",Long.class,UUID.randomUUID().toString());
    }

    private long member(long schoolId,int year) {
        String name=UUID.randomUUID().toString();
        long id=db.queryForObject("insert into app_user(username,display_name) values (?,?) returning id",Long.class,name,name);
        db.update("insert into school_member(school_id,user_id,enrollment_year,status) values (?,?,?,'APPROVED')",schoolId,id,year);
        db.update("insert into verified_login_email(user_id,email) values (?,?)",id,name+"@test.invalid");
        return id;
    }

    private RequestPostProcessor as(long actor) { return user(new LoginUser(users.findById(actor))); }
    private String endpoint(long id) { return "/api/weekly/"+id; }

    @BeforeEach void setup() {
        school=school();foreign=school();
        manager=member(school,2026);leader=member(school,2026);student=member(school,2025);
        db.update("insert into school_administrator(user_id,school_id) values (?,?)",manager,school);
        db.update("""
            insert into school_cohort_leader(school_id,user_id,member_id,enrollment_year,granted_by)
            select school_id,user_id,id,enrollment_year,? from school_member where user_id=?
            """,manager,leader);
        when(source.preview(URL)).thenReturn(meta);
    }

    private long publish(long actor,String body) throws Exception {
        var response=mvc.perform(post(endpoint(school)).with(as(actor)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).path("data").longValue();
    }

    @Test void bothRolesPublishAcrossGradesAndTemplatesStayWithinSchool() throws Exception {
        long id=publish(leader,BODY);
        mvc.perform(get(endpoint(school)+"/preview").param("url",URL).with(as(manager)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.targets['2025']").value(2));
        publish(manager,BODY.replace("11-18","11-23"));
        mvc.perform(post(endpoint(school)).with(as(manager)).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict());
        mvc.perform(get(endpoint(school)+"/"+id).with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.weekStart").value("2026-11-16"));
        long outsider=member(foreign,2026);
        mvc.perform(get(endpoint(school)).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(endpoint(foreign)+"/"+id).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(post(endpoint(school)).with(as(student)).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
        db.update("update school_cohort_leader set revoked_at=now(),revoke_reason='MANUAL',revoked_by=? where user_id=?",manager,leader);
        mvc.perform(get(endpoint(school)+"/preview").param("url",URL).with(as(leader))).andExpect(status().isForbidden());
    }

    @Test void atcoderBoardJobsRespectServiceAuthorization() throws Exception {
        String url="https://atcoder.jp/contests/abc480";
        var at=new WeeklySource.Meta("ATCODER","abc480","AtCoder Beginner Contest 480","ABC",url,meta.tasks());
        when(source.preview(url)).thenReturn(at);
        long id=publish(manager,BODY.replace(URL,url));
        db.update("""
            insert into platform_account(user_id,platform,handle,platform_uid,ownership_status)
            values (?,'ATCODER','tester','tester','VERIFIED')
            """,student);
        refresh.discover();
        assertThat(refresh.next()).isFalse();
        when(source.atcoderStandingsEnabled()).thenReturn(true);
        refresh.discover();
        when(source.results(at,"tester","tester")).thenReturn(List.of(new WeeklySource.Row("VIRTUAL",
            Map.of("A",new WeeklySource.Cell(true,0,100d,120L)),100d,120L)));
        assertThat(refresh.next()).isTrue();
        mvc.perform(get(endpoint(school)+"/"+id).with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[0].kind").value("VIRTUAL"));
        when(source.atcoderStandingsEnabled()).thenReturn(false);
        db.update("update weekly_account_result set next_attempt=now() where training_id=?",id);
        assertThat(refresh.next()).isFalse();
    }

    @Test void refreshRetriesKeepLastValidBoardAndNeverDoubleCount() throws Exception {
        long id=publish(manager,BODY);
        db.update("""
            insert into platform_account(user_id,platform,handle,platform_uid,ownership_status)
            values (?,'CODEFORCES','tester','tester','VERIFIED')
            """,student);
        WeeklySource.Cell ac=new WeeklySource.Cell(true,0,null,60L);
        when(source.results(meta,"tester","tester")).thenReturn(List.of(
            new WeeklySource.Row("OFFICIAL",Map.of("A",ac),1d,60),
            new WeeklySource.Row("PRACTICE",Map.of("B",new WeeklySource.Cell(true,1,null,null)),1d,0)));
        refresh.discover();
        refresh.discover();
        assertThat(refresh.next()).isTrue();
        assertThat(refresh.next()).isFalse();
        mvc.perform(get(endpoint(school)).with(as(manager))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].rows").doesNotExist())
            .andExpect(jsonPath("$.data[0].students[?(@.status=='MET')]").value(org.hamcrest.Matchers.hasSize(1)));
        db.update("update weekly_account_result set next_attempt=now() where training_id=?",id);
        when(source.results(meta,"tester","tester")).thenThrow(new IllegalStateException("temporary outage"));
        assertThat(refresh.next()).isTrue();
        mvc.perform(get(endpoint(school)+"/"+id).with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows.length()").value(2))
            .andExpect(jsonPath("$.data.rows[0].rank").value(1))
            .andExpect(jsonPath("$.data.problemStats.B.solved").value(1));
    }
}
