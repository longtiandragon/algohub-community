package com.szpu.algohub.admin.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 每个 /api/admin 接口都要配权限：以后新加管理接口忘了在 AdminPermission 里登记，这里会失败。 */
@SpringBootTest
class AdminPermissionCoverageTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyAdminEndpointHasAPermission() {
        List<String> admin = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/admin")) {
                    continue;
                }
                admin.add(pattern);
                if (!pattern.equals(AdminPermission.ME_PATH) && AdminPermission.forPath(pattern).isEmpty()) {
                    missing.add(pattern);
                }
            }
        });
        assertThat(admin).hasSizeGreaterThan(30);
        assertThat(missing).as("这些管理接口没有配置权限").isEmpty();
    }

    @Test
    void longestPrefixWinsAndPrefixNeedsBoundary() {
        assertThat(AdminPermission.forPath("/api/admin/users/12/status")).contains(AdminPermission.USERS);
        assertThat(AdminPermission.forPath("/api/admin/users")).contains(AdminPermission.USERS);
        assertThat(AdminPermission.forPath("/api/admin/usersx")).isEmpty();
        assertThat(AdminPermission.forPath("/api/admin/sync/overview")).contains(AdminPermission.SYNC);
        assertThat(AdminPermission.forPath("/api/admin/unknown")).isEmpty();
    }
}
