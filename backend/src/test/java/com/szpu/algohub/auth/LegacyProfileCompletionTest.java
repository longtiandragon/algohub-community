package com.szpu.algohub.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.school.SchoolService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class LegacyProfileCompletionTest {
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired OnboardingService onboarding;
    @Autowired AppUserMapper users;
    @Autowired SchoolService schools;
    @Autowired MockMvc mvc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    Long legacy(String role) {
        Long id = db.queryForObject("insert into app_user(username,display_name,role,status,onboarding_completed,enrollment_year,student_no) values (?,'旧昵称',?,'ACTIVE',true,2024,'240001') returning id", Long.class, "legacy-"+java.util.UUID.randomUUID(), role);
        db.update("insert into verified_login_email(user_id,email) values (?,?)", id, id+"@example.test");
        return id;
    }

    @Test void repairsOnlyMissingFieldsAndPreservesSchoolAndAccounts() throws Exception {
        Long id = legacy("STUDENT");
        var school = schools.defaultSchool();
        Long member = db.queryForObject("insert into school_member(user_id,school_id,status,enrollment_year,student_no) values (?,?,'APPROVED',2025,'250001') returning id", Long.class, id, school.getId());
        Long account = db.queryForObject("insert into platform_account(user_id,platform,handle) values (?,'CODEFORCES','unchanged-handle') returning id", Long.class, id);
        assertThat(auth.me(id).profileCompletionRequired()).isTrue();
        assertThat(auth.me(id).enrollmentYear()).isEqualTo(2025);
        assertThat(auth.me(id).studentNo()).isEqualTo("250001");
        mvc.perform(post("/api/me/complete-academic-profile").with(user(new LoginUser(users.findById(id))))
            .contentType("application/json").content("{\"enrollmentYear\":2026,\"className\":\" \",\"studentNo\":\"changed\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/complete-academic-profile").with(user(new LoginUser(users.findById(id))))
            .contentType("application/json").content("{\"enrollmentYear\":2026,\"className\":\" 计应1班 \",\"studentNo\":\"changed\",\"schoolId\":999999}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileCompletionRequired").value(false))
            .andExpect(jsonPath("$.data.enrollmentYear").value(2025)).andExpect(jsonPath("$.data.studentNo").value("250001"));
        assertThat(schools.myMemberships(id)).hasSize(1);
        assertThat(schools.myMemberships(id).getFirst().getId()).isEqualTo(member);
        assertThat(schools.myMemberships(id).getFirst().getSchoolId()).isEqualTo(school.getId());
        assertThat(schools.myMemberships(id).getFirst().getStatus()).isEqualTo("APPROVED");
        assertThat(db.queryForObject("select handle from platform_account where id=?", String.class, account)).isEqualTo("unchanged-handle");
        assertThat(users.findById(id).getDisplayName()).isEqualTo("旧昵称");
        onboarding.completeAcademic(id, new AuthDtos.AcademicRequest(2026,"试图改班","试图改学号"));
        assertThat(auth.me(id).className()).isEqualTo("计应1班");
    }

    @Test void personalFallbackAndUnboundUsersDoNotNeedSchoolSelection() {
        Long id = legacy("STUDENT");
        onboarding.completeAcademic(id, new AuthDtos.AcademicRequest(2024,"一班","240001"));
        assertThat(auth.me(id).profileCompletionRequired()).isFalse();
        assertThat(schools.myMemberships(id)).isEmpty();
        Long pending = db.queryForObject("insert into school_member(user_id,school_id,status) values (?,?,'PENDING') returning id",Long.class,id,schools.defaultSchool().getId());
        sqlSession.clearCache();
        assertThat(auth.me(id).profileCompletionRequired()).isFalse();
        assertThat(auth.me(id).className()).isEqualTo("一班");
        assertThat(schools.myMemberships(id).getFirst().getId()).isEqualTo(pending);
        assertThat(auth.me(legacy("ADMIN")).profileCompletionRequired()).isFalse();
    }

    @Test void nicknameNoticeOnlyClearsWhenNameActuallyChanges() {
        Long id = legacy("STUDENT");
        db.update("update app_user set nickname_needs_review=true,display_name='新同学_AB12CD34' where id=?", id);
        assertThat(auth.me(id).nicknameNeedsReview()).isTrue();
        auth.updateProfile(id,new AuthDtos.UpdateProfileRequest("新同学_AB12CD34",null,"介绍",null),"test","test");
        assertThat(auth.me(id).nicknameNeedsReview()).isTrue();
        auth.updateProfile(id,new AuthDtos.UpdateProfileRequest("自选昵称",null,"介绍",null),"test","test");
        assertThat(auth.me(id).nicknameNeedsReview()).isFalse();
    }

    @Test void migrationOnlyRenamesExactEmailDefaultsAndIsStable() throws Exception {
        Long old = legacy("STUDENT"), custom = legacy("STUDENT"), unrelated = legacy("STUDENT");
        db.update("update app_user set username=?,display_name='新同学' where id=?", "mail_"+old, old);
        db.update("update app_user set username=?,display_name='新同学自定义' where id=?", "mail_"+custom, custom);
        db.update("update app_user set display_name='新同学' where id=?", unrelated);
        String sql = new String(new org.springframework.core.io.ClassPathResource("db/migration/V52__legacy_default_nicknames.sql").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String update = sql.substring(sql.indexOf("update app_user"));
        db.update(update);
        String generated = users.findById(old).getDisplayName();
        assertThat(generated).matches("新同学_[A-F0-9]{8}");
        assertThat(users.findById(old).isNicknameNeedsReview()).isTrue();
        assertThat(users.findById(custom).getDisplayName()).isEqualTo("新同学自定义");
        assertThat(users.findById(unrelated).getDisplayName()).isEqualTo("新同学");
        db.update(update);
        sqlSession.clearCache();
        assertThat(users.findById(old).getDisplayName()).isEqualTo(generated);
    }
}
