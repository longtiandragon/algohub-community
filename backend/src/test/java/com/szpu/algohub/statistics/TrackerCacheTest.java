package com.szpu.algohub.statistics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.szpu.algohub.platform.*;
import com.szpu.algohub.auth.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @AutoConfigureMockMvc @Transactional
class TrackerCacheTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 @Autowired com.szpu.algohub.account.NowcoderTrackerController tracker;
 @MockitoBean PlatformHttpClient http;
 Long verifiedOtherUser() {
  Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('tracker-other','x','其他同学','STUDENT','ACTIVE') returning id",Long.class);
  jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",id,"tracker-other@example.test");
  return id;
 }
 @Test void storesAcceptedOnlyDeduplicatesAndSeparatesOwners() throws Exception {
  Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('tracker-test','x','测试','STUDENT','ACTIVE') returning id",Long.class);
  jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",id,"tracker-test@example.test");
  jdbc.update("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status) values (?,'NOWCODER','123','123','RESOLVED','PUBLIC','OK')",id);
  AppUser u=new AppUser();u.setId(id);u.setUsername("tracker-test");u.setRole("STUDENT");u.setStatus("ACTIVE");
  when(http.get(eq(Platform.NOWCODER),contains("tracker/list"))).thenReturn("""
   {"code":0,"data":{"totalCount":1,"papers":[{"questions":[
   {"problemId":1,"title":"题 A","questionUrl":"https://www.nowcoder.com/practice/aaa","difficulty":800},
   {"problemId":2,"title":"题 B","questionUrl":"https://www.nowcoder.com/practice/bbb","difficulty":900}]}]}}
   """);
  when(http.postForm(eq(Platform.NOWCODER),contains("tracker/diff"),anyMap(),anyMap(),isNull())).thenReturn("{\"code\":0,\"data\":{\"ac1Qids\":[1]}}");
  for(int n=0;n<2;n++) mvc.perform(get("/api/accounts/nowcoder-tracker/problems").with(user(new LoginUser(u)))).andExpect(status().isOk());
  mvc.perform(get("/api/accounts/nowcoder-tracker/cache").with(user(new LoginUser(u)))).andExpect(jsonPath("$.data.problems.length()").value(1));
  assertThat(jdbc.queryForObject("select count(*) from nowcoder_tracker_problem where pid='1'",Integer.class)).isEqualTo(1);
  for(int n=0;n<2;n++) org.springframework.test.util.ReflectionTestUtils.invokeMethod(tracker,"mergeCachedSolved");
  assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where user_id=? and source='TRACKER' and earliest_known_ac_at is null",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from submission s join platform_account a on a.id=s.account_id where a.user_id=?",Integer.class,id)).isZero();
  u.setId(verifiedOtherUser());
  mvc.perform(get("/api/accounts/nowcoder-tracker/cache").with(user(new LoginUser(u)))).andExpect(jsonPath("$.data.problems.length()").value(0));
  mvc.perform(get("/api/accounts/nowcoder-tracker/cache")).andExpect(status().isUnauthorized());
 }
 @Test void missingSourcePageDoesNotEraseCache() throws Exception {
  Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('tracker-missing','x','测试','STUDENT','ACTIVE') returning id",Long.class);
  jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",id,"tracker-missing@example.test");
  Long account=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status) values (?,'NOWCODER','124','124','RESOLVED','PUBLIC','OK') returning id",Long.class,id);
  jdbc.update("insert into nowcoder_tracker_problem(account_id,pid,title,url) values (?,'42','已通过','https://www.nowcoder.com/practice/test')",account);
  AppUser u=new AppUser();u.setId(id);u.setUsername("tracker-missing");u.setRole("STUDENT");u.setStatus("ACTIVE");
  when(http.get(eq(Platform.NOWCODER),contains("tracker/list"))).thenReturn("{\"code\":0,\"data\":{\"totalCount\":907,\"papers\":[]}}");
  mvc.perform(get("/api/accounts/nowcoder-tracker/problems").with(user(new LoginUser(u)))).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.errorKind").value("PARSE_ERROR"));
  assertThat(jdbc.queryForObject("select count(*) from nowcoder_tracker_problem where account_id=?",Integer.class,account)).isEqualTo(1);
 }
 @Test void manualSyncKeepsCheckpointAndOtherUsersUntouched() throws Exception {
  Long id=jdbc.queryForObject("insert into app_user(username,password_hash,display_name,role,status) values ('tracker-queue','x','测试','STUDENT','ACTIVE') returning id",Long.class);
  jdbc.update("insert into verified_login_email(user_id,email) values (?,?)",id,"tracker-queue@example.test");
  Long account=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status,auth_mode,sync_status) values (?,'NOWCODER','125','125','RESOLVED','PUBLIC','OK') returning id",Long.class,id);
  AppUser u=new AppUser();u.setId(id);u.setUsername("tracker-queue");u.setRole("STUDENT");u.setStatus("ACTIVE");
  jdbc.update("insert into nowcoder_tracker_progress(account_id,complete,completed_pages) values (?,true,31)",account);
  org.springframework.test.util.ReflectionTestUtils.setField(tracker,"enabled",true);
  try {
   mvc.perform(post("/api/accounts/nowcoder-tracker/sync").with(user(new LoginUser(u)))).andExpect(status().isOk());
   assertThat(jdbc.queryForObject("select complete from nowcoder_tracker_progress where account_id=?",Boolean.class,account)).isFalse();
   jdbc.update("update nowcoder_tracker_progress set next_page=12,completed_pages=11,error_message='retry',next_run_at=now()+interval '5 minutes' where account_id=?",account);
   mvc.perform(post("/api/accounts/nowcoder-tracker/sync").with(user(new LoginUser(u)))).andExpect(status().isOk());
   assertThat(jdbc.queryForObject("select next_page from nowcoder_tracker_progress where account_id=?",Integer.class,account)).isEqualTo(12);
   assertThat(jdbc.queryForObject("select next_run_at>now() from nowcoder_tracker_progress where account_id=?",Boolean.class,account)).isTrue();
   u.setId(verifiedOtherUser());
   mvc.perform(post("/api/accounts/nowcoder-tracker/sync").with(user(new LoginUser(u)))).andExpect(jsonPath("$.success").value(false));
   mvc.perform(post("/api/accounts/nowcoder-tracker/sync")).andExpect(status().isUnauthorized());
  } finally { org.springframework.test.util.ReflectionTestUtils.setField(tracker,"enabled",false); }
 }
}
