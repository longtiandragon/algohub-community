package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.sync.SyncService;
import com.szpu.algohub.statistics.CampusRankingService;
import com.szpu.algohub.activity.ActivityMapper;

@SpringBootTest
@Transactional
class QojBrowserBindingTest {
    @Autowired AccountService accounts;
    @Autowired PlatformAccountMapper mapper;
    @Autowired QojBrowserImportService browser;
    @Autowired SyncService sync;
    @Autowired JdbcTemplate db;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired CampusRankingService ranking;
    @Autowired ActivityMapper activity;
    long user;

    @BeforeEach void setup() {
        user = db.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x','Example','STUDENT') returning id",
                Long.class, "qoj-" + UUID.randomUUID());
    }

    @Test void usernameAssociationNeedsNoCookieOrRemoteLookupAndNeverQueuesServerSync() {
        var account = accounts.bind(user,"QOJ","example_user",null);
        assertThat(account.getAuthMode()).isEqualTo("BROWSER");
        assertThat(account.getOwnershipStatus()).isEqualTo("UNVERIFIED");
        assertThat(accounts.bind(user,"QOJ","example_user",null).getId()).isEqualTo(account.getId());
        assertThat(db.queryForObject("select count(*) from platform_credential where account_id=?",Integer.class,account.getId())).isZero();
        assertThat(sync.enqueueAccountSync(account.getId(),"QOJ",10)).isFalse();
        assertThat(accounts.triggerAllSync(user).total()).isZero();
        assertThat(mapper.findDueForSync(Instant.now().plusSeconds(60))).noneMatch(a -> a.getId().equals(account.getId()));
        assertThatThrownBy(() -> accounts.triggerSync(account.getId(),user,false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> accounts.saveCredential(account.getId(),user,false,"unused",null,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> accounts.bind(user,"QOJ","https://qoj.ac/user/profile/example",null)).isInstanceOf(ApiException.class);
    }

    @Test void browserImportKeepsUnverifiedRecordsOutOfPublicRankingAndActivity() {
        var account = accounts.bind(user,"QOJ","example_user",null);
        long school = db.queryForObject("insert into school(name) values (?) returning id",Long.class,"QOJ test "+UUID.randomUUID());
        db.update("insert into school_member(school_id,user_id,status) values (?,?,'APPROVED')",school,user);
        String ticket = browser.begin(user,account.getId(),true).get("ticket");
        var row = new QojBrowserImportService.BrowserRecord("990000001","example_user","1001","Example",null,Instant.now().minusSeconds(60).getEpochSecond(),"C++");
        assertThat(browser.pageWithTicket(ticket,new QojBrowserImportService.Page(1,1,100,List.of(row))).complete()).isTrue();
        assertThat(mapper.findById(account.getId()).getOwnershipStatus()).isEqualTo("UNVERIFIED");
        assertThat(db.queryForObject("select count(*) from user_solved_problem where account_id=?",Integer.class,account.getId())).isEqualTo(1);
        assertThat(db.queryForList("select time_precision from submission where account_id=?",String.class,account.getId())).containsOnly("USER_REPORTED");
        var board = ranking.ranking(user,"GLOBAL","WEEK","FIRST_AC","QOJ",null,null,200);
        assertThat(board.get("myRank")).isNull();
        assertThat(activity.schoolFeed(school,null,"QOJ",user,null,null,100)).isEmpty();
        assertThatThrownBy(() -> browser.begin(-1L,account.getId())).isInstanceOf(ApiException.class);
        db.update("update platform_account set ownership_status='MISMATCH' where id=?",account.getId());
        sqlSession.clearCache();
        assertThatThrownBy(() -> browser.autoBegin(ticket)).isInstanceOf(ApiException.class);
    }

    @Test void revokedOwnershipCannotBeRestoredByReaddingTheUsername() {
        var account = accounts.bind(user,"QOJ","example_user",null);
        db.update("update platform_account set ownership_note='revoked' where id=?",account.getId());
        sqlSession.clearCache();
        var readded = accounts.bind(user,"QOJ","example_user",null);
        assertThat(readded.getOwnershipNote()).isEqualTo("revoked");
        assertThatThrownBy(() -> browser.begin(user,readded.getId())).isInstanceOf(ApiException.class);
    }
}
