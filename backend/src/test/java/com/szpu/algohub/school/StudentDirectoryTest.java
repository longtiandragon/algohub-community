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

    @Test void restrictsSchoolDirectoryAndSearchesSubmittedNames() throws Exception {
        long school=school(), foreign=school();
        long manager=student(school,"01"), target=student(school,"02"), outsider=student(foreign,"03");
        manager(school,manager);
        db.update("update school_member set real_name=? where user_id in (?,?)","陈同学",target,outsider);
        String directory="/api/schools/"+school+"/students/directory";
        mvc.perform(get(directory).with(as(manager)).param("q","陈同"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].realName").value("陈同学"))
            .andExpect(jsonPath("$.data.items[0].name").value("昵称02"))
            .andExpect(jsonPath("$.data.items[0].remark").doesNotExist());
        mvc.perform(get(directory).with(as(target))).andExpect(status().isForbidden());
        mvc.perform(get(directory).with(as(outsider))).andExpect(status().isForbidden());
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
