package com.szpu.algohub.social;

import com.szpu.algohub.auth.*;
import com.szpu.algohub.activity.ActivityService;
import com.szpu.algohub.reminder.StudyReminderService;
import com.szpu.algohub.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class SocialServiceTest {
    @Autowired SocialService social;
    @Autowired JdbcTemplate db;
    @Autowired StudyReminderService inbox;
    @Autowired ActivityService activity;
    @Autowired AppUserMapper users;
    @Autowired MockMvc mvc;
    long a,b,c;
    Long problem;
    Long secondProblem;
    long person() {
        String name="social-"+UUID.randomUUID();
        long id=db.queryForObject("insert into app_user(username,password_hash,display_name,role,status,onboarding_completed) values (?,'x',?,'STUDENT','ACTIVE',true) returning id",Long.class,name,name);
        db.update("insert into verified_login_email(user_id,email) values (?,?)",id,name+"@example.test");
        db.update("insert into user_privacy(user_id,profile_visibility,public_activity) values (?,'PUBLIC',true)",id);
        return id;
    }
    @BeforeEach void setup() { a=person();b=person();c=person(); }
    @AfterEach void cleanup() { db.update("delete from app_user where id in (?,?,?)",a,b,c);if(problem!=null)db.update("delete from problem where id=?",problem);if(secondProblem!=null)db.update("delete from problem where id=?",secondProblem); }
    LoginUser login(long id) { return new LoginUser(users.findById(id)); }
    void mutual() {social.follow(a,b);social.follow(b,a);}
    @Test void followsAreDirectionalIdempotentAndRevocableEvenAfterPrivacyChange() throws Exception {
        assertThat(social.follow(a,b)).containsEntry("following",true).containsEntry("mutual",false);
        social.follow(a,b);
        assertThat(db.queryForObject("select count(*) from user_follow where follower_id=? and following_id=?",Integer.class,a,b)).isEqualTo(1);
        assertThat(social.state(b,a)).containsExactlyInAnyOrderEntriesOf(Map.of("following",false,"mutual",false));
        assertThatThrownBy(()->social.send(a,b,"TRAIN",UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->social.connections(b,"FOLLOWERS",0)).isInstanceOf(ApiException.class);
        assertThat(social.counts(b)).containsExactlyInAnyOrderEntriesOf(Map.of("following",0L,"followers",1L));
        mvc.perform(get("/api/social/connections?type=FOLLOWERS").with(user(login(b)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/social/users/"+a).with(user(login(b)))).andExpect(jsonPath("$.data.followedBy").doesNotExist());
        mvc.perform(get("/api/social/counts?userId="+b).with(user(login(c))))
            .andExpect(jsonPath("$.data.followers").value(0)).andExpect(jsonPath("$.data.following").value(0));
        assertThat((List<?>)social.connections(a,"FRIENDS",0).get("items")).isEmpty();
        assertThat(social.follow(b,a)).containsEntry("mutual",true);
        assertThat((List<?>)social.connections(a,"FRIENDS",0).get("items")).hasSize(1);
        db.update("update user_privacy set profile_visibility='PRIVATE' where user_id=?",b);
        assertThatThrownBy(()->social.state(a,b)).isInstanceOf(ApiException.class);
        assertThat(social.unfollow(a,b)).containsEntry("following",false).containsEntry("mutual",false);
        assertThatThrownBy(()->social.follow(a,a)).isInstanceOf(ApiException.class);
    }
    @Test void onlyMutualsCanSendFixedMessagesAndNoEmailIsQueued() throws Exception {
        social.follow(a,b);
        assertThatThrownBy(()->social.send(a,b,"TRAIN",UUID.randomUUID())).isInstanceOf(ApiException.class).hasMessageContaining("互相关注");
        social.follow(b,a);
        UUID request=UUID.randomUUID();long id=social.send(a,b,"TRAIN",request);
        assertThat(social.send(a,b,"TRAIN",request)).isEqualTo(id);
        assertThat(db.queryForObject("select body from study_notification where id=?",String.class,id)).isEqualTo(SocialService.TEMPLATES.getFirst().text());
        assertThat(db.queryForObject("select count(*) from study_mail_outbox where notification_id=?",Integer.class,id)).isZero();
        assertThatThrownBy(()->social.send(a,b,"CUSTOM",UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->social.send(a,b,"TODAY",request)).isInstanceOf(ApiException.class).hasMessageContaining("更换");
        assertThatThrownBy(()->social.send(a,b,"TODAY",UUID.randomUUID())).isInstanceOf(ApiException.class).hasMessageContaining("一分钟");
        social.unfollow(b,a);
        assertThatThrownBy(()->social.send(a,b,"CHEER",UUID.randomUUID())).isInstanceOf(ApiException.class).hasMessageContaining("互相关注");
        assertThat(inbox.unread(b)).isEqualTo(1);
        assertThatThrownBy(()->inbox.read(c,id)).isInstanceOf(ApiException.class);
    }
    @Test void apiCannotForgeSenderOrMessageBodyAndRejectsMissingIds() throws Exception {
        mutual();
        mvc.perform(post("/api/social/users/"+b+"/messages").with(user(login(a))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"templateCode\":\"TODAY\",\"requestId\":\""+UUID.randomUUID()+"\",\"senderId\":"+c+",\"body\":\"arbitrary\"}"))
            .andExpect(status().isOk());
        var record=db.queryForMap("select created_by,body from study_notification where user_id=? and category='FRIEND'",b);
        assertThat(((Number)record.get("created_by")).longValue()).isEqualTo(a);
        assertThat(record.get("body")).isEqualTo(SocialService.TEMPLATES.get(1).text());
        mvc.perform(post("/api/social/users/"+b+"/messages").with(user(login(a))).contentType(MediaType.APPLICATION_JSON).content("{\"templateCode\":\"TRAIN\"}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/social/users/"+a+"/follow").with(user(login(a)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/social/users/"+b+"/follow")).andExpect(status().isUnauthorized());
    }
    @Test void inboxCategoriesPaginationAndReadAllRemainRecipientScoped() {
        mutual();social.send(a,b,"TRAIN",UUID.randomUUID());
        db.update("insert into study_notification(user_id,kind,dedup_key,title,body,created_by) values (?,'ADMIN','system-test','系统通知','系统正文',?)",b,a);
        assertThat((List<?>)inbox.inbox(b,0,"FRIEND").get("items")).hasSize(1);
        assertThat((List<?>)inbox.inbox(b,0,"SYSTEM").get("items")).hasSize(1);
        inbox.readAll(b,"FRIEND");assertThat(inbox.unread(b)).isEqualTo(1);
        assertThat(inbox.inbox(b,0,"ALL").get("unreadByCategory")).isEqualTo(Map.of("SYSTEM",1L,"FRIEND",0L));
        assertThat((List<?>)inbox.inbox(c,0,"ALL").get("items")).isEmpty();
        assertThatThrownBy(()->inbox.inbox(b,0,"bad")).isInstanceOf(ApiException.class);
        for(int i=0;i<22;i++) db.update("insert into study_notification(user_id,kind,dedup_key,title,body) values (?,'SYSTEM',?,'通知','内容')",b,"page-"+i);
        var first=inbox.inbox(b,0,"SYSTEM");var rows=(List<Map<String,Object>>)first.get("items");
        assertThat(rows).hasSize(20);assertThat(first.get("hasMore")).isEqualTo(true);
        long before=((Number)rows.getLast().get("id")).longValue();
        assertThat((List<?>)inbox.inbox(b,before,"SYSTEM").get("items")).hasSize(3);
    }
    @Test void concurrentSendsCannotExceedCooldownOrCreateDuplicates() throws Exception {
        mutual();
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Boolean> send=()->{start.await();try{social.send(a,b,"TRAIN",UUID.randomUUID());return true;}catch(ApiException e){return false;}};
            var one=executor.submit(send);var two=executor.submit(send);start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        for(int i=0;i<2;i++)db.update("insert into study_notification(user_id,kind,dedup_key,title,body,created_by,category) values (?,'FRIEND_NUDGE',?,'提醒','内容',?,'FRIEND')",b,"limit-"+i,a);
        assertThatThrownBy(()->social.send(a,b,"TRAIN",UUID.randomUUID())).hasMessageContaining("3 条");
        social.unfollow(a,b);social.follow(a,b);
        assertThatThrownBy(()->social.send(a,b,"TRAIN",UUID.randomUUID())).hasMessageContaining("3 条");
    }
    @Test void followingDecoratesVisibleFeedWithoutBypassingPrivacy() {
        long account=db.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'CODEFORCES','example_user','VERIFIED') returning id",Long.class,b);
        problem=db.queryForObject("insert into problem(platform,problem_key,title) values ('CODEFORCES',?,'Test') returning id",Long.class,"social-"+UUID.randomUUID());
        db.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence,source) values (?,?,?,'CODEFORCES',now(),'EXACT','SYNC')",b,account,problem);
        social.follow(a,b);
        var feed=activity.feed(a,null,b,"PUBLIC",null,null,10);
        assertThat(feed.items()).singleElement().satisfies(r->assertThat(r.get("following")).isEqualTo(true));
        db.update("update user_privacy set public_activity=false where user_id=?",b);
        assertThat(activity.feed(a,null,b,"PUBLIC",null,null,10).items()).isEmpty();
        db.update("update user_privacy set public_activity=true,profile_visibility='PRIVATE' where user_id=?",b);
        assertThat(activity.feed(a,null,b,"PUBLIC",null,null,10).items()).isEmpty();
        assertThatThrownBy(()->social.follow(c,b)).isInstanceOf(ApiException.class);
        assertThat(social.unfollow(a,b)).containsEntry("following",false);
    }
    @Test void followingFilterRunsBeforePaginationAndRespectsPrivacy() throws Exception {
        long bAccount=db.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'CODEFORCES',?,'VERIFIED') returning id",Long.class,b,"example_"+b);
        long cAccount=db.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values (?,'CODEFORCES',?,'VERIFIED') returning id",Long.class,c,"example_"+c);
        problem=db.queryForObject("insert into problem(platform,problem_key,title) values ('CODEFORCES',?,'Test') returning id",Long.class,"social-"+UUID.randomUUID());
        secondProblem=db.queryForObject("insert into problem(platform,problem_key,title) values ('CODEFORCES',?,'Test') returning id",Long.class,"social-"+UUID.randomUUID());
        db.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence,source) values (?,?,?,'CODEFORCES',now()-interval '2 hour','EXACT','SYNC'),(?,?,?,'CODEFORCES',now()-interval '1 hour','EXACT','SYNC'),(?,?,?,'CODEFORCES',now(),'EXACT','SYNC')",b,bAccount,problem,b,bAccount,secondProblem,c,cAccount,problem);
        assertThat(activity.feed(a,null,null,"PUBLIC",null,null,1,true).items()).isEmpty();
        social.follow(a,b);
        var first=activity.feed(a,null,null,"PUBLIC",null,null,1,true);
        assertThat(first.items()).singleElement().satisfies(r->assertThat(r.get("userId")).isEqualTo(b));
        assertThat(first.hasMore()).isTrue();
        var second=activity.feed(a,null,null,"PUBLIC",null,first.nextCursor(),1,true);
        assertThat(second.items()).singleElement().satisfies(r->assertThat(r.get("problemId")).isEqualTo(problem));
        assertThat(second.hasMore()).isFalse();
        assertThat(activity.feed(a,"ATCODER",null,"PUBLIC",null,null,10,true).items()).isEmpty();
        mvc.perform(get("/api/activity/feed?scope=PUBLIC&followingOnly=true&userId="+c).with(user(login(a))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        db.update("update user_privacy set public_activity=false where user_id=?",b);
        assertThat(activity.feed(a,null,null,"PUBLIC",null,null,10,true).items()).isEmpty();
        db.update("update user_privacy set public_activity=true,profile_visibility='PRIVATE' where user_id=?",b);
        assertThat(activity.feed(a,null,null,"PUBLIC",null,null,10,true).items()).isEmpty();
        social.unfollow(a,b);
        assertThat(social.counts(a)).containsEntry("following",0L);
        assertThat(social.counts(b)).containsEntry("followers",0L);
    }
}
