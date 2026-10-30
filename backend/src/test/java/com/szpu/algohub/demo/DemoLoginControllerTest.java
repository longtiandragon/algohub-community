package com.szpu.algohub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 示例登录只在 demo 配置下存在：其他配置里没有这个 Bean，请求也签发不了登录状态。 */
@SpringBootTest(properties = "algohub.email.from=test@example.test")
@AutoConfigureMockMvc
class DemoLoginControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ApplicationContext context;
    @MockitoBean
    JavaMailSender mail;

    @Test
    void demoLoginDoesNotExistOutsideDemoProfile() throws Exception {
        assertThat(context.getBeanNamesForType(DemoLoginController.class)).isEmpty();
        mvc.perform(post("/api/auth/demo-login").contentType("application/json").content("{\"username\":\"admin\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
