package com.szpu.algohub.statistics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthService;

/**
 * 别人的刷题统计不能绕过档案可见性。
 *
 * 背景：/api/stats/profile/{userId} 与 /api/stats/heatmap?userId= 原先把路径里的
 * userId 直接透传给查询，任何登录用户都能读到对方的各平台通过数、平台账号 handle、
 * 最近通过题目与一年的每日活跃分布——即使对方把档案设为「仅自己可见」。
 * 「个人档案」接口做了可见性判断，这两个接口没有，本测试把两边钉在一起。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatsVisibilityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long owner;
    private String ownerToken;
    private String peerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from school_member");
        jdbcTemplate.update("delete from app_user where username like 'vis-test%'");

        owner = newUser("vis-test-owner-" + System.nanoTime());
        Long peer = newUser("vis-test-peer-" + System.nanoTime());
        ownerToken = token(owner);
        peerToken = token(peer);
    }

    private Long newUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(username);
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        jdbcTemplate.update("insert into user_privacy (user_id) values (?) on conflict do nothing",
                user.getId());
        jdbcTemplate.update("insert into verified_login_email (user_id, email) values (?, ?) "
                + "on conflict do nothing", user.getId(), user.getId() + "@vis.invalid");
        return user.getId();
    }

    private String token(Long userId) {
        AppUser user = userMapper.findById(userId);
        return authService.issueSession(user, "127.0.0.1", "JUnit", "LOGIN_SUCCESS").view().accessToken();
    }

    private void setVisibility(Long userId, String visibility) {
        authService.updatePrivacy(userId, new PrivacyRequest(visibility, null, null, null, null),
                "127.0.0.1", "JUnit");
    }

    @Test
    void privateProfileStatsAreNotReadableByOtherStudents() throws Exception {
        setVisibility(owner, "PRIVATE");

        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKind").value("NOT_FOUND"));
        // 档案接口同样 404，且不透露昵称
        mockMvc.perform(get("/api/me/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist());
        // 不存在的用户与不可见的用户看起来一样
        mockMvc.perform(get("/api/stats/profile/" + (owner + 1_000_000)).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/stats/heatmap").param("userId", String.valueOf(owner))
                        .header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/history/user/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stats/rating-history/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/activity/achievements/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/nowcoder-tracker/cache/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void schoolOnlyProfileIsNotReadableByNonMembers() throws Exception {
        // 默认就是 SCHOOL：查看者没有通过审核的本校成员身份，就不该看到
        setVisibility(owner, "SCHOOL");

        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicProfileStatsStayReadable() throws Exception {
        setVisibility(owner, "PUBLIC");
        account(owner, "MATIJI", "vis-matiji", "VERIFIED", "PUBLIC");
        account(owner, "QOJ", "vis-qoj", "VERIFIED", "PUBLIC");
        account(owner, "HDU", "vis-hdu", "VERIFIED", "PUBLIC");

        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSolvedAcrossPlatforms").value(0))
                .andExpect(jsonPath("$.data.totalPassed").value(21))
                .andExpect(jsonPath("$.data.platformTotals.length()").value(3));
        mockMvc.perform(get("/api/history/user/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private Long account(Long userId, String platform, String handle, String ownership, String authMode) {
        Long id = jdbcTemplate.queryForObject("""
                insert into platform_account (user_id, platform, platform_uid, handle, ownership_status, auth_mode)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, userId, platform, "vis-" + System.nanoTime(), handle, ownership, authMode);
        jdbcTemplate.update("insert into total_snapshot (account_id, solved_total, raw_extra) values (?, 7, ?)",
                id, "{\"uid\":\"" + handle + "\"}");
        return id;
    }

    @Test
    void othersSeeOnlyVerifiedAccountsAndNoHandlesWhenHidden() throws Exception {
        setVisibility(owner, "PUBLIC");
        account(owner, "CODEFORCES", "vis-verified", "VERIFIED", "COOKIE");
        account(owner, "ATCODER", "vis-watching", "RESOLVED", "PUBLIC");

        // 本人：全部账号，含托管方式
        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.snapshotOverview.length()").value(2))
                .andExpect(jsonPath("$.data.snapshotOverview[0].authMode").exists());
        // 别人：只有已验证的，不带托管方式与归属状态
        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotOverview.length()").value(1))
                .andExpect(jsonPath("$.data.snapshotOverview[0].platform").value("CODEFORCES"))
                .andExpect(jsonPath("$.data.snapshotOverview[0].handle").doesNotExist())
                .andExpect(jsonPath("$.data.snapshotOverview[0].authMode").doesNotExist())
                .andExpect(jsonPath("$.data.snapshotOverview[0].ownershipStatus").doesNotExist())
                .andExpect(jsonPath("$.data.accountsHidden").value(true));

        authService.updatePrivacy(owner, new PrivacyRequest(null, null, false, null, null), "127.0.0.1", "JUnit");
        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + peerToken))
                .andExpect(jsonPath("$.data.snapshotOverview.length()").value(1))
                .andExpect(jsonPath("$.data.snapshotOverview[0].solvedTotal").value(7))
                .andExpect(jsonPath("$.data.snapshotOverview[0].handle").doesNotExist())
                .andExpect(jsonPath("$.data.snapshotOverview[0].rawExtra").doesNotExist())
                .andExpect(jsonPath("$.data.accountsHidden").value(true));
        // 本人不受影响
        mockMvc.perform(get("/api/stats/profile").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.snapshotOverview[0].handle").exists());
    }

    @Test
    void ownStatsAreAlwaysReadable() throws Exception {
        setVisibility(owner, "PRIVATE");

        mockMvc.perform(get("/api/stats/profile/" + owner).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stats/profile").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stats/heatmap").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }
}
