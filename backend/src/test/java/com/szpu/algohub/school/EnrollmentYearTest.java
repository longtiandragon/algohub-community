package com.szpu.algohub.school;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EnrollmentYearTest {
    @Autowired EnrollmentYearService years;
    @Autowired MockMvc mvc;
    @Test void initialOptionsAreExactlyFourAndAvailableBeforeLogin() throws Exception {
        assertThat(years.active()).containsExactly(2026,2025,2024,2023);
        mvc.perform(get("/api/auth/enrollment-years")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(post("/api/admin/enrollment-years").contentType("application/json")
            .content("{\"year\":2027,\"monthlyTarget\":30,\"enabled\":true}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void administratorCanAddYearAndDisableWithoutErasingHistoryOrGoal() {
        years.save(new EnrollmentYearService.Setting(2027,35,true));
        assertThat(years.active()).contains(2027);
        years.validate(2027,null);
        years.save(new EnrollmentYearService.Setting(2023,20,false));
        assertThat(years.active()).doesNotContain(2023);
        assertThatThrownBy(()->years.validate(2023,null)).isInstanceOf(ApiException.class);
        years.validate(2023,2023); // 已有成员可保留历史年级并修改班级
        assertThat(years.all()).anySatisfy(row->{
            assertThat(row.get("year")).isEqualTo(2023);
            assertThat(row.get("monthlyTarget")).isEqualTo(20);
            assertThat(row.get("enabled")).isEqualTo(false);
        });
        assertThatThrownBy(()->years.save(new EnrollmentYearService.Setting(2028,-1,true))).isInstanceOf(ApiException.class);
    }
}
