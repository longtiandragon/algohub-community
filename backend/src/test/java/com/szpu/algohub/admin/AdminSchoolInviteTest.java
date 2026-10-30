package com.szpu.algohub.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.school.SchoolMapper;

/** 学校邀请码只给管理员看；换码后旧码立刻查不到学校。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSchoolInviteTest {
    @Autowired MockMvc mvc;
    @Autowired AdminSchoolController controller;
    @Autowired SchoolMapper schools;

    @Test
    void anonymousCannotSeeOrChangeCodes() throws Exception {
        mvc.perform(get("/api/admin/schools")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/schools/1/invite-code")).andExpect(status().isUnauthorized());
    }

    @Test
    void regeneratingInvalidatesTheOldCode() {
        var school = controller.list().data().get(0);
        var updated = controller.regenerate(school.id()).data();
        assertThat(updated.inviteCode()).matches("[A-HJ-NP-Z2-9]{8}").isNotEqualTo(school.inviteCode());
        assertThat(schools.findByInviteCode(updated.inviteCode()).getId()).isEqualTo(school.id());
        if (school.inviteCode() != null) {
            assertThat(schools.findByInviteCode(school.inviteCode())).isNull();
        }
    }
}
