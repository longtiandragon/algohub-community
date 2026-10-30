package com.szpu.algohub.activity;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AccountCoverageTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityMapper mapper;

    @Test void coverageKeepsTwoAccountsOfTheSamePlatformSeparate() {
        Long user=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('coverage-multiple','x','测试','STUDENT','ACTIVE') returning id",Long.class);
        Long first=jdbc.queryForObject("insert into platform_account(user_id,platform,handle) values (?,'CODEFORCES','coverage-one') returning id",Long.class,user);
        Long second=jdbc.queryForObject("insert into platform_account(user_id,platform,handle) values (?,'CODEFORCES','coverage-two') returning id",Long.class,user);
        var rows=mapper.ownCoverage(user);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> ((Number)row.get("accountId")).longValue()).containsExactlyInAnyOrder(first,second);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("platform")).isEqualTo("CODEFORCES");
            assertThat(((Number)row.get("solvedCount")).longValue()).isZero();
        });
    }
}
