package com.szpu.algohub.reminder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.mail.Session;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import com.szpu.algohub.auth.*;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.reminder.StudyReminderService.*;

@SpringBootTest(properties="algohub.email.from=reminders@example.invalid")
@AutoConfigureMockMvc
class StudyReminderTest {
    @Autowired StudyReminderService service;
    @Autowired StudyReminderJobs jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserMapper users;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    long uid,other,admin;
    final List<Long> ownedUsers=new ArrayList<>();
    final List<Long> problems=new ArrayList<>();

    @BeforeEach void setup() {
        stubMimeMessages();
        uid=newUser(2024,"STUDENT");other=newUser(2025,"STUDENT");admin=newUser(2026,"ADMIN");
    }
    void stubMimeMessages() {
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        clearInvocations(mail);
    }
    List<String> recipients(MimeMessage message) {
        try {
            var addresses=message.getAllRecipients();
            return addresses==null ? List.of() : Arrays.stream(addresses).map(Object::toString).toList();
        } catch (MessagingException e) {
            throw new AssertionError("Cannot inspect reminder mail recipients",e);
        }
    }
    long newUser(int year,String role) {
        long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values(?,'x','提醒测试',?,'ACTIVE') returning id",
            Long.class,"reminder-"+UUID.randomUUID(),role);
        ownedUsers.add(id);
        long school=jdbc.queryForObject("select id from school order by id limit 1",Long.class);
        jdbc.update("insert into school_member(school_id,user_id,enrollment_year,status) values(?,?,?,'APPROVED')",school,id,year);
        jdbc.update("insert into verified_login_email(user_id,email) values(?,?)",id,"reminder-"+id+"@example.invalid");
        return id;
    }
    @AfterEach void cleanup() {
        for(long id:ownedUsers) {
            jdbc.update("delete from school_member where user_id=?",id);
            jdbc.update("delete from platform_account where user_id=?",id);
        }
        for(long id:ownedUsers) jdbc.update("delete from app_user where id=?",id);
        for(long id:problems) jdbc.update("delete from problem where id=?",id);
    }
    Goal goal(long user,String period) { return (Goal)service.current(user).get(period); }
    long manual(long user) { return ((Number)service.manual(admin,user,new ManualRequest("保持练习",UUID.randomUUID())).get("id")).longValue(); }
    String mailStatus(long id) { return jdbc.queryForObject("select status from study_mail_outbox where notification_id=?",String.class,id); }
    int notices(long user) { return jdbc.queryForObject("select count(*) from study_notification where user_id=?",Integer.class,user); }

    @Test void defaultsPersonalPlanAndAdminOverridesAreIndependent() {
        assertThat(goal(uid,"monthly").target()).isEqualTo(60);
        assertThat(goal(other,"monthly").target()).isEqualTo(50);
        assertThat(goal(admin,"monthly").target()).isEqualTo(30);
        service.save(uid,new PersonalRequest(12,20,true));
        assertThat(goal(uid,"weekly").target()).isEqualTo(12);
        assertThat(goal(uid,"monthly").target()).isEqualTo(60);
        service.assign(admin,uid,new AssignmentRequest(15,25));
        assertThat(goal(uid,"weekly").target()).isEqualTo(15);
        assertThat(goal(uid,"monthly").target()).isEqualTo(25);
        service.assign(admin,uid,new AssignmentRequest(15,25));
        assertThat(notices(uid)).isEqualTo(1);
        service.assign(admin,uid,new AssignmentRequest(0,0));
        assertThat(goal(uid,"monthly").target()).isEqualTo(20);
        service.save(uid,new PersonalRequest(null,null,false));
        assertThat(goal(uid,"monthly").target()).isZero();
        service.assign(admin,uid,new AssignmentRequest(null,null));
        assertThat(goal(uid,"monthly").target()).isEqualTo(60);
    }

    @Test void validatesTargetsAndUnknownGradeHasNoInventedRequirement() {
        long unknown=newUser(2027,"STUDENT");
        assertThat(goal(unknown,"monthly").target()).isZero();
        assertThatThrownBy(()->service.save(uid,new PersonalRequest(0,null,true))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.assign(admin,uid,new AssignmentRequest(-1,10001))).isInstanceOf(ApiException.class);
    }

    @Test void deduplicationHoldsAcrossConcurrentSchedulerCalls() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++) tasks.add(()->{service.scheduled(uid,"MONTHLY",LocalDate.of(2026,10,15));return null;});
            for(var result:pool.invokeAll(tasks)) result.get(20,TimeUnit.SECONDS);
        }
        assertThat(notices(uid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from study_mail_outbox o join study_notification n on n.id=o.notification_id where n.user_id=?",Integer.class,uid)).isEqualTo(1);
    }

    @Test void schedulingUsesBeijingNineAmAndHandlesThursdayOnTheFifteenth() {
        // 隔离其他测试遗留成员，mock dueUsers 之外只断言当前用户的消息。
        jobs.generate(ZonedDateTime.parse("2026-10-15T00:59:00Z"));
        assertThat(notices(uid)).isZero();
        jobs.generate(ZonedDateTime.parse("2026-10-15T01:00:00Z"));
        assertThat(jdbc.queryForList("select kind from study_notification where user_id=? order by kind",String.class,uid)).containsExactly("MONTHLY","WEEKLY");
        jobs.generate(ZonedDateTime.parse("2026-10-15T04:00:00Z"));
        assertThat(notices(uid)).isEqualTo(2);
        jobs.generate(ZonedDateTime.parse("2026-10-19T01:00:00Z"));
        assertThat(notices(uid)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select body from study_notification where user_id=? and kind='REPORT'",String.class,uid)).contains("2026-10-12 至 2026-10-18");
        assertThat(notices(admin)).isZero();
    }

    @Test void manualRetryIsIdempotentAndCooldownCannotBeBypassed() {
        var request=new ManualRequest("加油",UUID.randomUUID());
        assertThat(service.manual(admin,uid,request)).isEqualTo(service.manual(admin,uid,request));
        assertThatThrownBy(()->manual(uid)).isInstanceOf(ApiException.class);
        assertThat(notices(uid)).isEqualTo(1);
    }

    @Test void inboxIsPrivateAndStudentCannotCallAdminApi() throws Exception {
        long id=manual(uid);
        assertThat(service.unread(uid)).isEqualTo(1);
        assertThat(service.unread(other)).isZero();
        mvc.perform(get("/api/admin/reminders/users").with(user(new LoginUser(users.findById(uid))))).andExpect(status().isForbidden());
        mvc.perform(post("/api/reminders/inbox/"+id+"/read").with(user(new LoginUser(users.findById(other)))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/reminders/inbox").with(user(new LoginUser(users.findById(other)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        service.read(uid,id);assertThat(service.unread(uid)).isZero();
    }

    @Test void mailUsesVerifiedAddressAndHasRetriesAndOptOut() {
        jdbc.update("update app_user set email='public-contact@example.invalid' where id=?",uid);
        long id=manual(uid);
        doThrow(new MailSendException("fake SMTP error")).when(mail).send(any(MimeMessage.class));
        jobs.dispatchBatch(); assertThat(mailStatus(id)).isEqualTo("PENDING");
        jdbc.update("update study_mail_outbox set available_at=now() where notification_id=?",id);
        reset(mail);stubMimeMessages();
        jobs.dispatchBatch();assertThat(mailStatus(id)).isEqualTo("SENT");
        var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail,atLeastOnce()).send(capture.capture());
        assertThat(capture.getAllValues()).anySatisfy(m->assertThat(recipients(m)).containsExactly("reminder-"+uid+"@example.invalid"));
        assertThat(capture.getAllValues()).noneSatisfy(m->assertThat(recipients(m)).contains("public-contact@example.invalid"));
        service.save(other,new PersonalRequest(null,null,false));
        assertThat(mailStatus(manual(other))).isEqualTo("SKIPPED");
    }

    @Test void workerRechecksOptOutAndStopsAfterFiveFailures() {
        long id=manual(uid);
        service.save(uid,new PersonalRequest(null,null,false));
        jobs.dispatchBatch();assertThat(mailStatus(id)).isEqualTo("SKIPPED");
        long failure=manual(other);
        doThrow(new MailSendException("fake")).when(mail).send(any(MimeMessage.class));
        for(int i=0;i<5;i++) { jdbc.update("update study_mail_outbox set available_at=now() where notification_id=?",failure);jobs.dispatchBatch(); }
        assertThat(mailStatus(failure)).isEqualTo("FAILED");
    }

    @Test void parallelMailWorkersClaimEachMessageOnce() throws Exception {
        long id=manual(uid);
        try(var pool=Executors.newFixedThreadPool(4)) {
            for(var result:pool.invokeAll(List.<Callable<Void>>of(()->{jobs.dispatchBatch();return null;},()->{jobs.dispatchBatch();return null;}))) result.get(20,TimeUnit.SECONDS);
        }
        assertThat(mailStatus(id)).isEqualTo("SENT");
        verify(mail,times(1)).send(argThat((MimeMessage m)->recipients(m).contains("reminder-"+uid+"@example.invalid")));
    }

    long account() {
        return jdbc.queryForObject("insert into platform_account(user_id,platform,handle,ownership_status) values(?,'CODEFORCES',?,'RESOLVED') returning id",Long.class,uid,UUID.randomUUID().toString());
    }
    long problem() {
        long id=jdbc.queryForObject("insert into problem(platform,problem_key,title,url) values('CODEFORCES',?,'test','https://example.invalid') returning id",Long.class,UUID.randomUUID().toString());
        problems.add(id);return id;
    }
    void solve(long account,long problem,String time,String confidence) {
        jdbc.update("insert into user_solved_problem(user_id,account_id,problem_id,platform,earliest_known_ac_at,first_ac_confidence) values(?,?,?,'CODEFORCES',?,?)",
            uid,account,problem,time==null?null:OffsetDateTime.parse(time),confidence);
    }
    @Test void progressDeduplicatesBeforeFilteringAndExcludesUnknownTimesAtMonthBoundary() {
        long a=account(),b=account(),old=problem();
        solve(a,old,"2026-09-01T00:00:00+08:00","EXACT");
        solve(b,old,"2026-10-02T00:00:00+08:00","EXACT");
        solve(a,problem(),"2026-09-30T16:00:00Z","EXACT"); // 正好10月1日
        solve(a,problem(),"2026-10-31T16:00:00Z","EXACT"); // 正好11月1日
        solve(a,problem(),null,"UNKNOWN");
        solve(a,problem(),"2026-10-10T00:00:00Z","ESTIMATED");
        assertThat(service.solved(uid,LocalDate.of(2026,10,1),LocalDate.of(2026,11,1))).isEqualTo(1);
    }
}
