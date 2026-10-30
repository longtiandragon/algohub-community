package com.szpu.algohub.auth;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.MimeMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="algohub.email.from=test@example.test") @AutoConfigureMockMvc
class PasswordResetTest {
 @Autowired PasswordResetService reset;
 @Autowired EmailLoginService emailLogin;
 @Autowired EmailCodeGuard emailGuard;
 @Autowired AuthService auth;
 @Autowired AppUserMapper users;
 @Autowired PasswordEncoder encoder;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @MockitoBean JavaMailSender mail;
 AppUser user;String email;
 @BeforeEach void setup(){MailTestSupport.stubMimeMessages(mail);String key="reset"+System.nanoTime();email=key+"@example.test";Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values (?,?,'测试','ADMIN','ACTIVE') returning id",Long.class,key,encoder.encode("original-password"));user=users.findById(id);}
 void bind(){jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",user.getId(),email);}
 void pendingEmailOperations(){
  jdbc.update("insert into email_login_challenge(email,code_hash,expires_at) values (?,'test-hash',now()+interval '10 minutes')",email);
  jdbc.update("insert into email_verification_challenge(user_id,email,code_hash,expires_at) values (?,?,'test-hash',now()+interval '10 minutes')",user.getId(),"new-"+email);
 }
 void assertNoPendingEmailOperations(){
  assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email=?",Integer.class,email)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from email_verification_challenge where user_id=?",Integer.class,user.getId())).isZero();
  assertThat(jdbc.queryForObject("select count(*) from password_reset_challenge where user_id=?",Integer.class,user.getId())).isZero();
  assertThat(users.findByVerifiedEmail(email).getId()).isEqualTo(user.getId());
 }
 @AfterEach void cleanup(){if(user!=null)jdbc.update("delete from app_user where id=?",user.getId());}
 String send(){reset.send(email,"ip-"+System.nanoTime());var c=ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(c.capture());return MailTestSupport.code(c.getValue());}
 @Test void resetConsumesCodeAndRevokesBothSessionTokensImmediately() throws Exception {
  bind();var old=auth.issueSession(user,"test","test","LOGIN_SUCCESS");String code=send();pendingEmailOperations();
  reset.reset(email,code,"new-password-123","test","test");
  assertThat(encoder.matches("new-password-123",users.findById(user.getId()).getPasswordHash())).isTrue();
  assertNoPendingEmailOperations();
  assertThatThrownBy(()->reset.reset(email,code,"other-password","test","test")).hasMessageContaining("失效");
  mvc.perform(get("/api/me").header("Authorization","Bearer "+old.view().accessToken())).andExpect(status().isUnauthorized());
  assertThatThrownBy(()->auth.refresh(old.refreshToken(),"test","test")).hasMessageContaining("无效");
  var fresh=auth.login(user.getUsername(),"new-password-123","test","test");
  mvc.perform(get("/api/me").header("Authorization","Bearer "+fresh.view().accessToken())).andExpect(status().isOk());
 }
 @Test void unverifiedAccountIsRestrictedByBackendAndVerifiedAccountCanProceed() throws Exception {
  jdbc.update("update app_user set role='STUDENT' where id=?",user.getId());user=users.findById(user.getId());
  var session=auth.issueSession(user,"test","test","LOGIN_SUCCESS");String token="Bearer "+session.view().accessToken();
  mvc.perform(get("/api/accounts").header("Authorization",token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/me/login-email").header("Authorization",token)).andExpect(status().isOk());
  bind();mvc.perform(get("/api/accounts").header("Authorization",token)).andExpect(status().isOk());
 }
 @Autowired EmailLoginService login;
 @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
 @Test void wrongCodesLockTogetherWithLoginCodes(){
  bind();String code=send();String wrong=code.equals("000000")?"000001":"000000";
  for(int i=0;i<5;i++)assertThatThrownBy(()->reset.reset(email,wrong,"new-password","test","test")).hasMessageContaining("验证码");
  // 登录码与找回密码合并计数：再猜错 5 次登录码就锁定
  reset(mail);MailTestSupport.stubMimeMessages(mail);login.send(email,"ip");
  for(int i=0;i<4;i++)assertThatThrownBy(()->login.verify(email,"999999".equals(code)?"999998":"999999")).hasMessageContaining("验证码");
  assertThatThrownBy(()->login.verify(email,"999999".equals(code)?"999998":"999999")).hasMessageContaining("锁定");
  assertThatThrownBy(()->reset.reset(email,code,"new-password","test","test")).hasMessageContaining("锁定");
  redis.delete("algohub:reset:"+EmailLoginService.hash("email:"+email));
  assertThatThrownBy(()->reset.send(email,"ip")).hasMessageContaining("锁定");
  assertThat(encoder.matches("original-password",users.findById(user.getId()).getPasswordHash())).isTrue();
 }
 @Test void expiredCodeCannotChangePassword(){
  bind();String code=send();
  jdbc.update("update password_reset_challenge set expires_at=now()-interval '1 second' where email=?",email);
  assertThatThrownBy(()->reset.reset(email,code,"new-password","test","test")).hasMessageContaining("失效");
  assertThat(encoder.matches("original-password",users.findById(user.getId()).getPasswordHash())).isTrue();
 }
 @Test void noAccountEnumerationOrCodeForUnverifiedEmail(){reset.send(email,"ip-"+System.nanoTime());verifyNoInteractions(mail);assertThat(jdbc.queryForObject("select count(*) from password_reset_challenge where email=?",Integer.class,email)).isZero();}
 @Test void changedEmailCannotUseOldResetCode(){bind();String code=send();jdbc.update("update verified_login_email set email=? where user_id=?","changed-"+email,user.getId());assertThatThrownBy(()->reset.reset(email,code,"new-password","test","test")).hasMessageContaining("失效");}
 @Test void firstPasswordRequiresEmailProofAndPreservesEmailLogin() throws Exception {
  bind();jdbc.update("update app_user set password_hash=null where id=?",user.getId());
  var session=auth.issueSession(users.findById(user.getId()),"test","test","EMAIL_LOGIN_SUCCESS");
  mvc.perform(post("/api/me/password").header("Authorization","Bearer "+session.view().accessToken())
      .contentType("application/json").content("{\"newPassword\":\"first-password-123\"}"))
      .andExpect(status().isBadRequest());
  assertThat(users.findById(user.getId()).getPasswordHash()).isNull();
  String code=send();
  mvc.perform(post("/api/auth/password-reset").contentType("application/json")
      .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("email",email,"code",code,"password","first-password-123"))))
      .andExpect(status().isOk());
  assertThat(auth.login(user.getUsername(),"first-password-123","test","test")).isNotNull();
  assertThat(users.findByVerifiedEmail(email).getId()).isEqualTo(user.getId());
  mvc.perform(get("/api/me").header("Authorization","Bearer "+session.view().accessToken())).andExpect(status().isUnauthorized());
 }
 @Test void passwordChangeCancelsPreviouslyIssuedEmailOperations(){
  bind();String code=send();pendingEmailOperations();
  auth.changePassword(user.getId(),"original-password","changed-password","test","test");
  assertNoPendingEmailOperations();
  assertThatThrownBy(()->reset.reset(email,code,"another-password","test","test")).hasMessageContaining("失效");
 }
 @Test void rejectedPasswordChangePreservesPendingOperations(){
  bind();String code=send();pendingEmailOperations();
  assertThatThrownBy(()->auth.changePassword(user.getId(),"wrong-password","changed-password","test","test")).hasMessageContaining("原密码");
  assertThat(jdbc.queryForObject("select count(*) from email_verification_challenge where user_id=?",Integer.class,user.getId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from email_login_challenge where email=?",Integer.class,email)).isEqualTo(1);
  reset.reset(email,code,"new-password-123","test","test");
  assertNoPendingEmailOperations();
 }
 @Test void queuedPasswordChangeCannotOverwriteNewPassword() throws Exception {
  bind();
  Throwable failure=runBehindIdentityLock(
      ()->auth.changePassword(user.getId(),"original-password","losing-password","test","test"),
      ()->auth.changePassword(user.getId(),"original-password","winning-password","test","test"));
  assertThat(failure).isInstanceOf(com.szpu.algohub.common.ApiException.class).hasMessageContaining("原密码");
  assertThat(encoder.matches("winning-password",users.findById(user.getId()).getPasswordHash())).isTrue();
 }
 @Test void queuedResetCannotConsumeCodeRevokedByLogoutAll() throws Exception {
  bind();String code=send();
  Throwable failure=runBehindIdentityLock(
      ()->reset.reset(email,code,"losing-password","test","test"),
      ()->auth.logoutAll(user.getId(),"test","test"));
  assertThat(failure).isInstanceOf(com.szpu.algohub.common.ApiException.class).hasMessageContaining("失效");
  assertThat(encoder.matches("original-password",users.findById(user.getId()).getPasswordHash())).isTrue();
 }
 @Test void queuedResetMailRechecksEmailOwnership() throws Exception {
  bind();
  Throwable failure=runBehindIdentityLock(()->reset.send(email,"test"),
      ()->jdbc.update("update verified_login_email set email=? where user_id=?","changed-"+email,user.getId()));
  assertThat(failure).isNull();
  verifyNoInteractions(mail);
  assertThat(jdbc.queryForObject("select count(*) from password_reset_challenge where user_id=?",Integer.class,user.getId())).isZero();
 }
 @Test void queuedEmailLoginCannotUseCodeRevokedByLogoutAll() throws Exception {
  bind();insertLoginCode();
  Throwable failure=runBehindIdentityLock(()->emailLogin.verify(email,"123456"),
      ()->auth.logoutAll(user.getId(),"test","test"));
  assertThat(failure).isInstanceOf(com.szpu.algohub.common.ApiException.class).hasMessageContaining("失效");
 }
 @Test void queuedEmailLoginRechecksOwnershipInsteadOfCreatingAnotherAccount() throws Exception {
  bind();insertLoginCode();
  Throwable failure=runBehindIdentityLock(()->emailLogin.verify(email,"123456"),
      ()->jdbc.update("update verified_login_email set email=? where user_id=?","changed-"+email,user.getId()));
  assertThat(failure).isInstanceOf(com.szpu.algohub.common.ApiException.class).hasMessageContaining("归属已变化");
  assertThat(users.findByVerifiedEmail(email)).isNull();
  jdbc.update("delete from email_login_challenge where email=?",email);
 }
 @Test void queuedEmailLoginRechecksLockoutAfterWaiting() throws Exception {
  bind();insertLoginCode();
  Throwable failure=runBehindIdentityLock(()->emailLogin.verify(email,"123456"),()->{
   for(int i=0;i<EmailCodeGuard.MAX_FAILURES;i++) emailGuard.recordFailure(email);
  });
  assertThat(failure).isInstanceOf(com.szpu.algohub.common.ApiException.class).hasMessageContaining("锁定");
  jdbc.update("delete from email_login_challenge where email=?",email);
 }
 private void insertLoginCode() {
  jdbc.update("insert into email_login_challenge(email,code_hash,expires_at) values(?,?,now()+interval '10 minutes')",email,EmailLoginService.hash("LOGIN:"+email+":123456"));
 }
 private Throwable runBehindIdentityLock(Runnable queued,Runnable winner) throws Exception {
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  var workerPid=new java.util.concurrent.atomic.AtomicInteger();
  var ready=new java.util.concurrent.CountDownLatch(1);
  var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Throwable>>();
  try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
   tx.executeWithoutResult(status->{
    users.lockIdentity(user.getId());
    result.set(executor.submit(()->{
     try {
      tx.executeWithoutResult(workerStatus->{
       jdbc.execute("set local statement_timeout='8s'");
       workerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
       ready.countDown();queued.run();
      });
      return null;
     } catch(Throwable failure) { return failure; }
    }));
    try {
     assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
     long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
     boolean blocked=false;
     while(System.nanoTime()<deadline) {
      blocked=Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,workerPid.get()));
      if(blocked || result.get().isDone()) break;
      Thread.sleep(10);
     }
     assertThat(blocked).as("competing request waits for the identity transaction").isTrue();
     winner.run();
    } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
   });
   return result.get().get(10,java.util.concurrent.TimeUnit.SECONDS);
  }
 }
}
