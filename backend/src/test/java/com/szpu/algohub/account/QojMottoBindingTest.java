package com.szpu.algohub.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.adapter.QojServerClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="algohub.qoj.server-enabled=true")
@Transactional
class QojMottoBindingTest {
    @Autowired BindingRequestService requests;
    @Autowired AccountService accounts;
    @Autowired QojBrowserImportService browser;
    @Autowired JdbcTemplate db;
    @MockitoBean QojServerClient server;
    long user;
    String handle;
    ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() {
        user=db.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x','Example','STUDENT') returning id",Long.class,"qoj-proof-"+UUID.randomUUID());
        handle="example_"+UUID.randomUUID().toString().replace("-","");
        when(server.enabled()).thenReturn(true);
        motto("");
    }
    void motto(String value) {
        when(server.profile(handle)).thenReturn(json.createObjectNode().put("motto",value).put("total",0).set("problemIds",json.createArrayNode()));
    }
    @Test void strictMottoBindsOnceAndAllowsPluginWithoutPersonalCookie() {
        assertThatThrownBy(()->accounts.bind(user,"QOJ",handle,null)).isInstanceOf(ApiException.class);
        var request=requests.create(user,"QOJ",handle);
        assertThat(request.get("settingsUrl")).isEqualTo("https://qoj.ac/user/modify-profile/"+handle+"/security");
        long id=((Number)request.get("id")).longValue();
        String token=(String)request.get("token");
        motto("prefix "+token);
        assertThatThrownBy(()->requests.verify(user,id)).isInstanceOf(ApiException.class);
        motto(token);
        var result=requests.verify(user,id);
        assertThat(result.outcome()).isEqualTo("BOUND");
        assertThat(browser.begin(user,result.accountId(),true)).containsKey("ticket");
        assertThat(db.queryForObject("select count(*) from platform_credential where account_id=?",Integer.class,result.accountId())).isZero();
        assertThatThrownBy(()->requests.verify(user,id)).isInstanceOf(ApiException.class);
    }
    @Test void expiredProofCannotBindAndUnverifiedLegacyCannotImport() {
        var request=requests.create(user,"QOJ",handle);
        long id=((Number)request.get("id")).longValue();
        motto((String)request.get("token"));
        db.update("update binding_request set expires_at=now()-interval '1 minute' where id=?",id);
        assertThatThrownBy(()->requests.verify(user,id)).isInstanceOf(ApiException.class);
        long account=db.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode) values (?,'QOJ',?,?,'UNVERIFIED','BROWSER') returning id",Long.class,user,handle,handle);
        assertThatThrownBy(()->browser.begin(user,account)).isInstanceOf(ApiException.class);
    }

    @Test void migrationArchivesRevokesAndNotifiesOnceWithoutRemovingNewBindings() throws Exception {
        long old=db.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,created_at) values (?,'QOJ',?,?,'VERIFIED','BROWSER',now()-interval '1 day') returning id",Long.class,user,handle,handle);
        String ticket=browser.begin(user,old,true).get("ticket");
        long fresh=db.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,created_at) values (?,'QOJ',?,?,'VERIFIED','PUBLIC',now()+interval '1 day') returning id",Long.class,user,handle+"new",handle+"new");
        String sql=java.nio.file.Files.readString(java.nio.file.Path.of("../deploy/migrate-qoj-motto.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'cutoff'::timestamptz", "now()")
                .replace("begin;", "").replace("commit;", "");
        db.execute(sql);
        assertThat(db.queryForObject("select count(*) from platform_account where id=?",Integer.class,old)).isZero();
        assertThat(db.queryForObject("select count(*) from platform_account where id=?",Integer.class,fresh)).isOne();
        assertThat(db.queryForObject("select jsonb_exists(related_data,'qoj_browser_import') from qoj_legacy_archive where account_id=?",Boolean.class,old)).isTrue();
        assertThatThrownBy(()->browser.pageWithTicket(ticket,new QojBrowserImportService.Page(1,0,500,java.util.List.of()))).isInstanceOf(ApiException.class);
        db.execute("drop table qoj_migration_target");
        db.execute(sql);
        assertThat(db.queryForObject("select count(*) from study_notification where user_id=? and dedup_key='qoj-motto-v1' and category='SYSTEM'",Integer.class,user)).isOne();
    }
}
