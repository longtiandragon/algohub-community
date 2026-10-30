package com.szpu.algohub.school;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class RequiredGradeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AppUserMapper users;
    @Autowired SchoolService schools;

    @Test void schoolSelectionRequiresGradeAndSavesItTogether() throws Exception {
        String name="grade-"+System.nanoTime();
        Long id=jdbc.queryForObject("insert into app_user(username,display_name,role,status) values(?,'测试','STUDENT','ACTIVE') returning id",Long.class,name);
        jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",id,name+"@example.test");
        Long school=jdbc.queryForObject("select id from school order by id limit 1",Long.class);
        String token=auth.issueSession(users.findById(id),"test","test","TEST").view().accessToken();
        for(String suffix:new String[]{"",",\"enrollmentYear\":null"}) {
            mvc.perform(post("/api/schools/select").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"schoolId\":"+school+suffix+"}")).andExpect(status().isBadRequest());
            assertThat(schools.myMemberships(id)).isEmpty();
        }
        mvc.perform(post("/api/schools/select").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"schoolId\":"+school+",\"enrollmentYear\":2025,\"className\":\"一班\",\"studentNo\":\"20250001\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enrollmentYear").value(2025));
        var member=schools.myMemberships(id).getFirst();
        assertThat(member.getEnrollmentYear()).isEqualTo(2025);
        mvc.perform(post("/api/schools/memberships/"+member.getId()).header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"enrollmentYear\":null}" )).andExpect(status().isBadRequest());
        assertThat(schools.myMemberships(id).getFirst().getEnrollmentYear()).isEqualTo(2025);
    }
    @Test void inviteJoinWithoutGradeIsRejectedBeforeCreatingMembership() {
        assertThatThrownBy(()->schools.applyByInviteCode(-1L,"test",null,null,null)).hasMessageContaining("请选择入学年级");
    }
}
