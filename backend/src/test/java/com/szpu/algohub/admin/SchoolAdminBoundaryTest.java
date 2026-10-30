package com.szpu.algohub.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class SchoolAdminBoundaryTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired AuthService auth; @Autowired AppUserMapper users;
 long a,b,manager,student,other,platform,memberA,memberB;
 String schoolToken, platformToken;
 long user(String role){String name="schooltest"+java.util.UUID.randomUUID();long id=db.queryForObject("insert into app_user(username,password_hash,display_name,role) values (?,'x',?,?) returning id",Long.class,name,name,role);db.update("insert into verified_login_email(user_id,email) values (?,?)",id,name+"@test.invalid");return id;}
 String token(long id){return "Bearer "+auth.issueSession(users.findById(id),"127.0.0.1","JUnit","LOGIN_SUCCESS").view().accessToken();}
 long member(long school,long user){return db.queryForObject("insert into school_member(school_id,user_id,status,enrollment_year,class_name,student_no) values (?,?,'PENDING',2026,'A','123') returning id",Long.class,school,user);}
 @BeforeEach void setup(){
  a=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"School A "+System.nanoTime());
  b=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"School B "+System.nanoTime());
  manager=user("STUDENT");student=user("STUDENT");other=user("STUDENT");platform=user("ADMIN");
  memberA=member(a,student);memberB=member(b,other);
  db.update("insert into school_administrator(user_id,school_id,granted_by) values (?,?,?)",manager,a,platform);
  schoolToken=token(manager);platformToken=token(platform);
 }
 @Test void schoolManagerIsScopedAndCannotReachPlatformAdministration() throws Exception {
  mvc.perform(get("/api/school-admin/schools").header("Authorization",schoolToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(a));
  mvc.perform(get("/api/school-admin/"+a+"/members").header("Authorization",schoolToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(1));
  mvc.perform(get("/api/school-admin/"+b+"/members").header("Authorization",schoolToken)).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/users").header("Authorization",schoolToken)).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/school-admins").header("Authorization",schoolToken).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":"+manager+",\"schoolId\":"+b+"}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/school-admin/"+a+"/members").header("Authorization",token(student))).andExpect(status().isForbidden());
  assertThat(auth.me(manager).managedSchools()).hasSize(1);
 }
 @Test void platformAdminCanGrantRevokeAndManageEverySchoolImmediately() throws Exception {
  mvc.perform(get("/api/school-admin/"+b+"/members").header("Authorization",platformToken)).andExpect(status().isOk());
  mvc.perform(post("/api/admin/school-admins").header("Authorization",platformToken).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":"+manager+",\"schoolId\":"+b+"}")).andExpect(status().isOk());
  mvc.perform(get("/api/school-admin/"+b+"/members").header("Authorization",schoolToken)).andExpect(status().isOk());
  mvc.perform(delete("/api/admin/school-admins/"+manager+"/"+b).header("Authorization",platformToken)).andExpect(status().isOk());
  mvc.perform(get("/api/school-admin/"+b+"/members").header("Authorization",schoolToken)).andExpect(status().isForbidden());
 }
 @Test void forgedMemberIdCannotCrossSchoolsOrModifyPlatformAdmin() throws Exception {
  mvc.perform(post("/api/school-admin/"+a+"/members/"+memberB+"/review").header("Authorization",schoolToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}")).andExpect(status().isNotFound());
  mvc.perform(post("/api/school-admin/"+a+"/members/"+memberA+"/review").header("Authorization",schoolToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}")).andExpect(status().isOk());
  assertThat(db.queryForObject("select status from school_member where id=?",String.class,memberA)).isEqualTo("APPROVED");
  long adminMember=member(a,platform);
  mvc.perform(post("/api/school-admin/"+a+"/members/"+adminMember+"/profile").header("Authorization",schoolToken).contentType(MediaType.APPLICATION_JSON).content("{\"enrollmentYear\":2026,\"className\":\"B\",\"studentNo\":\"456\"}")).andExpect(status().isForbidden());
 }
 @Test void announcementsAreScopedForBothWritesAndStudentReads() throws Exception {
  long foreign=db.queryForObject("insert into announcement(school_id,title,content,published) values (?,'foreign','x',true) returning id",Long.class,b);
  mvc.perform(post("/api/school-admin/"+a+"/announcements/"+foreign).header("Authorization",platformToken).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"bad\",\"content\":\"x\",\"published\":true}")).andExpect(status().isNotFound());
  mvc.perform(delete("/api/school-admin/"+a+"/announcements/"+foreign).header("Authorization",platformToken)).andExpect(status().isNotFound());
  mvc.perform(post("/api/school-admin/"+a+"/announcements").header("Authorization",platformToken).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"school-only-test\",\"content\":\"x\",\"published\":true}")).andExpect(status().isOk());
  db.update("update school_member set status='APPROVED' where id=?",memberA);
  String mine=mvc.perform(get("/api/announcements").header("Authorization",token(student))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  String theirs=mvc.perform(get("/api/announcements").header("Authorization",token(other))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(mine).contains("school-only-test").doesNotContain("foreign");assertThat(theirs).doesNotContain("school-only-test");
 }
 @Test void restrictedPlatformAdminCannotGrantOrUseUngrantAreas() throws Exception {
  db.update("update app_user set admin_permissions=array['CONTENT']::varchar[] where id=?",platform);
  mvc.perform(get("/api/admin/school-admins").header("Authorization",platformToken)).andExpect(status().isForbidden());
  mvc.perform(get("/api/school-admin/"+b+"/members").header("Authorization",platformToken)).andExpect(status().isForbidden());
  mvc.perform(get("/api/school-admin/"+b+"/announcements").header("Authorization",platformToken)).andExpect(status().isOk());
 }
 @Test void schoolDeletionCannotCascadeAwayGrantsOrAnnouncements() throws Exception {
  long empty=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Protected "+System.nanoTime());
  db.update("update app_user set admin_permissions=array['SCHOOLS']::varchar[] where id=?",platform);
  db.update("insert into school_administrator(user_id,school_id) values (?,?)",manager,empty);
  mvc.perform(delete("/api/admin/schools/"+empty).header("Authorization",platformToken)).andExpect(status().isConflict());
  db.update("delete from school_administrator where school_id=?",empty);
  db.update("insert into announcement(school_id,title,content) values (?,'protected','content')",empty);
  mvc.perform(delete("/api/admin/schools/"+empty).header("Authorization",platformToken)).andExpect(status().isConflict());
 }
 @Test void promotedRestrictedAdminCannotBypassPlatformPermissionsWithOldSchoolGrant() throws Exception {
  db.update("update app_user set role='ADMIN',admin_permissions=array['CONTENT']::varchar[] where id=?",manager);
  mvc.perform(get("/api/school-admin/"+a+"/members").header("Authorization",token(manager))).andExpect(status().isForbidden());
 }
 void privateProfile(long id) {
  db.update("insert into user_privacy(user_id,profile_visibility,show_email,show_accounts) values (?,'PRIVATE',false,false) on conflict(user_id) do update set profile_visibility='PRIVATE',show_email=false,show_accounts=false",id);
 }
 String[] profileUrls(long id) {
  return new String[]{"/api/me/profile/"+id,"/api/stats/profile/"+id,"/api/stats/heatmap?userId="+id,
   "/api/history/user/"+id,"/api/stats/rating-history/"+id,"/api/activity/achievements/"+id,
   "/api/activity/streak/"+id,"/api/accounts/luogu-extra/"+id,"/api/accounts/nowcoder-tracker/cache/"+id};
 }
 @Test void schoolManagerReadsApprovedPrivateStudentProfileAcrossAllSections() throws Exception {
  privateProfile(student); privateProfile(other);
  db.update("update app_user set email='private-profile@example.test' where id=?",student);
  db.update("update school_member set status='APPROVED' where id in (?,?)",memberA,memberB);
  // The manager need not be a school member; the current grant determines the scope.
  for(String url:profileUrls(student)) mvc.perform(get(url).header("Authorization",schoolToken)).andExpect(status().isOk());
  for(String url:profileUrls(other)) mvc.perform(get(url).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  mvc.perform(get("/api/me/profile/"+student).header("Authorization",schoolToken)).andExpect(jsonPath("$.data.email").doesNotExist());
  mvc.perform(get("/api/school-admin/"+a+"/members").header("Authorization",schoolToken)).andExpect(jsonPath("$.data.rows[0].userId").value(student));
  mvc.perform(post("/api/social/users/"+student+"/follow").header("Authorization",schoolToken)).andExpect(status().isNotFound());
  assertThat(db.queryForObject("select count(*) from auth_event where user_id=? and event='SCHOOL_PRIVATE_PROFILE_VIEW' and detail=?",Integer.class,manager,"targetUserId="+student)).isGreaterThan(0);
 }
 @Test void privateProfileGrantRevocationAndMembershipChangesApplyToExistingToken() throws Exception {
  privateProfile(student);
  for(String state:new String[]{"PENDING","REJECTED"}) {
   db.update("update school_member set status=? where id=?",state,memberA);
   mvc.perform(get("/api/me/profile/"+student).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  }
  db.update("update school_member set status='APPROVED' where id=?",memberA);
  mvc.perform(get("/api/me/profile/"+student).header("Authorization",schoolToken)).andExpect(status().isOk());
  db.update("update school_member set school_id=? where id=?",b,memberA);
  for(String url:profileUrls(student)) mvc.perform(get(url).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  db.update("update school_member set school_id=? where id=?",a,memberA);
  db.update("update app_user set status='DISABLED' where id=?",student);
  mvc.perform(get("/api/me/profile/"+student).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  db.update("update app_user set status='ACTIVE' where id=?",student);
  db.update("delete from school_administrator where user_id=?",manager);
  for(String url:profileUrls(student)) mvc.perform(get(url).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  mvc.perform(get("/api/me/profile/"+student).header("Authorization",platformToken)).andExpect(status().isOk());
 }
 @Test void schoolGrantDoesNotExposePrivatePlatformAdminsOrOverrideRestrictedPlatformRole() throws Exception {
  privateProfile(platform); privateProfile(student);
  long adminMember=member(a,platform);
  db.update("update school_member set status='APPROVED' where id in (?,?)",adminMember,memberA);
  mvc.perform(get("/api/me/profile/"+platform).header("Authorization",schoolToken)).andExpect(status().isNotFound());
  db.update("update app_user set role='ADMIN',admin_permissions=array['CONTENT']::varchar[] where id=?",manager);
  mvc.perform(get("/api/me/profile/"+student).header("Authorization",token(manager))).andExpect(status().isNotFound());
 }
}
