package com.szpu.algohub.school;

import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.LoginUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentDirectoryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;

    private long school() {
        return db.queryForObject("insert into school(name) values (?) returning id",Long.class,UUID.randomUUID().toString());
    }

    private long student(long school,String number) {
        long id=db.queryForObject("insert into app_user(username,display_name) values (?,?) returning id",
            Long.class,UUID.randomUUID().toString(),"昵称"+number);
        db.update("insert into school_member(school_id,user_id,enrollment_year,class_name,student_no,status) values (?,?,2026,'软件一班',?,'APPROVED')",school,id,number);
        db.update("insert into verified_login_email(user_id,email) values (?,?)",id,UUID.randomUUID()+"@test.invalid");
        return id;
    }

    private void manager(long school,long actor) {
        db.update("insert into school_administrator(school_id,user_id) values (?,?)",school,actor);
    }

    private RequestPostProcessor as(long id) { return user(new LoginUser(users.findById(id))); }

    @Test void ownerAndSchoolIsolateRemarksIncludingDirectorySearch() throws Exception {
        long school=school(), foreign=school();
        long alice=student(school,"01"), bob=student(school,"02"), target=student(school,"03");
        long outsider=student(foreign,"04");
        manager(school,alice);
        manager(school,bob);
        String base="/api/schools/"+school+"/students";
        mvc.perform(put(base+"/"+target+"/remark").with(as(alice)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"remark\":\" 私人名 \"}")).andExpect(status().isOk());
        mvc.perform(get(base+"/remarks").with(as(alice))).andExpect(jsonPath("$.data['"+target+"']").value("私人名"));
        for(long viewer:new long[]{bob,target,outsider})
            mvc.perform(get(base+"/remarks").with(as(viewer))).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get(base+"/directory").with(as(alice)).param("q","私人名"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].studentNo").value("03"));
        mvc.perform(get(base+"/directory").with(as(bob)).param("q","私人名")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get(base+"/directory").with(as(target))).andExpect(status().isForbidden());
        mvc.perform(get(base+"/directory").with(as(outsider))).andExpect(status().isForbidden());
        mvc.perform(put(base+"/"+outsider+"/remark").with(as(alice)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"remark\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(put(base+"/"+target+"/remark").with(as(alice)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"remark\":\"\"}")).andExpect(status().isOk());
        mvc.perform(get(base+"/remarks").with(as(alice))).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void paginatesOrdersAndHydratesOnlyEligibleStudents() throws Exception {
        long school=school(),admin=student(school,"00");
        manager(school,admin);
        long last=0;
        for(int i=1;i<=32;i++) last=student(school,String.format("%02d",i));
        String url="/api/schools/"+school+"/students/directory";
        mvc.perform(get(url).with(as(admin)).param("year","2026").param("className","软件一班").param("page","2"))
            .andExpect(jsonPath("$.data.total").value(33)).andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].studentNo").value("30")).andExpect(jsonPath("$.data.groups[0].count").value(33));
        mvc.perform(get(url).with(as(admin)).param("ids",admin+","+last).param("restriction","LEADER"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].userId").value(last));
    }
}
