package com.szpu.algohub.auth;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.school.SchoolService;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Transactional
class OnboardingTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Test void apiValidatesRequiredFieldsAndCompletesWithoutSchool() throws Exception {
        var u=fresh(true); String token=jwt.issueAccessToken(users.findById(u.getId())).token();
        var call=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/onboarding");
        mvc.perform(call.header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"displayName\":\"访客\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for (String fields : new String[] {"", ",\"className\":\"一班\"", ",\"studentNo\":\"1234\"", ",\"className\":\" \",\"studentNo\":\"1234\""}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/onboarding")
                .header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"displayName\":\"访客\",\"enrollmentYear\":2024" + fields + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/onboarding")
            .header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"displayName\":\"访客\",\"enrollmentYear\":2024,\"className\":\"计应1班\",\"studentNo\":\"20240001\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.onboardingCompleted").value(true))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.memberships").isEmpty());
    }
    @Autowired AppUserMapper users;
    @Autowired OnboardingService onboarding;
    @Autowired AuthService auth;
    @Autowired SchoolService schools;
    @Autowired JdbcTemplate jdbc;
    AppUser fresh(boolean verified) {
        var user=new AppUser(); user.setUsername("onboard_"+UUID.randomUUID());
        user.setDisplayName("新同学"); user.setEmail(UUID.randomUUID()+"@example.test");
        user.setRole("STUDENT"); user.setStatus("ACTIVE"); users.insert(user);
        if(verified) jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",user.getId(),user.getEmail());
        return user;
    }
    AuthDtos.OnboardingRequest request(Long school, String code) {
        return new AuthDtos.OnboardingRequest(school,code," 同名昵称 ",2024,"如24计应2班","1234");
    }
    @Test void schoolIsOptionalAndContactAddressIsPreserved() {
        var u=fresh(true); assertThat(users.findById(u.getId()).isOnboardingCompleted()).isFalse();
        onboarding.complete(u.getId(),request(null,null));
        var me=auth.me(u.getId());
        assertThat(me.onboardingCompleted()).isTrue(); assertThat(me.displayName()).isEqualTo("同名昵称");
        assertThat(me.enrollmentYear()).isEqualTo(2024); assertThat(me.email()).isEqualTo(u.getEmail());
        assertThat(me.memberships()).isEmpty(); assertThat(me.studentNo()).isEqualTo("1234");
        var profile=auth.publicProfile(u.getId(),u.getId(),false);
        assertThat(profile.enrollmentYear()).isEqualTo(2024); assertThat(profile.className()).isEqualTo("如24计应2班");
        onboarding.complete(u.getId(),request(999L,"wrong"));
        assertThat(schools.myMemberships(u.getId())).isEmpty();
    }
    @Test void academicFieldsAreRequired() {
        for(int i=0;i<2;i++) {
            var u=fresh(true);
            assertThatThrownBy(() -> onboarding.complete(u.getId(),new AuthDtos.OnboardingRequest(null,null,"同名昵称",2024," ",null))).isInstanceOf(ApiException.class);
            assertThat(auth.me(u.getId()).className()).isNull();
            assertThat(auth.me(u.getId()).studentNo()).isNull();
        }
    }
    @Test void invitationApprovesAndNoInvitationWaitsForReview() {
        var school=schools.defaultSchool();
        var a=fresh(true); onboarding.complete(a.getId(),request(school.getId(),school.getInviteCode()));
        assertThat(schools.myMemberships(a.getId()).getFirst().getStatus()).isEqualTo("APPROVED");
        assertThat(schools.myMemberships(a.getId()).getFirst().getStudentNo()).isEqualTo("1234");
        var b=fresh(true); onboarding.complete(b.getId(),request(school.getId(),null));
        assertThat(schools.myMemberships(b.getId()).getFirst().getStatus()).isEqualTo("PENDING");
        assertThat(schools.approvedSchoolId(b.getId())).isNull();
    }
    @Test void rejectedInputDoesNotCompleteOrJoin() {
        var u=fresh(true);
        assertThatThrownBy(()->onboarding.complete(u.getId(),request(schools.defaultSchool().getId(),"wrong"))).isInstanceOf(ApiException.class);
        assertThat(schools.myMemberships(u.getId())).isEmpty();
        assertThat(users.findById(u.getId()).isOnboardingCompleted()).isFalse();
        assertThatThrownBy(()->onboarding.complete(u.getId(),new AuthDtos.OnboardingRequest(null,null," ",2024,null,null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->onboarding.complete(u.getId(),new AuthDtos.OnboardingRequest(null,null,"名字",null,null,null))).isInstanceOf(ApiException.class);
    }
    @Test void requiresVerifiedEmailAndSkipsAdministrators() {
        var u=fresh(false);
        assertThatThrownBy(()->onboarding.complete(u.getId(),request(null,null))).isInstanceOf(ApiException.class);
        users.updateRole(u.getId(),"ADMIN");
        assertThatThrownBy(()->onboarding.complete(u.getId(),request(null,null))).isInstanceOf(ApiException.class);
    }
}
