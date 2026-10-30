package com.szpu.algohub.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

/** 示例发件器：不连邮件服务器，只记下来；超过 20 封时丢掉最早的。 */
class DemoMailSenderTest {

    @Test
    void recordsMailInsteadOfSending() {
        DemoMailSender sender = new DemoMailSender();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("demo@algohub.local");
        message.setTo("chenyiming@example.com");
        message.setSubject("算法学习平台：登录验证码");
        message.setText("登录验证码：123456\n10 分钟内有效");

        // 没有配置任何邮件服务器：真的去连就会抛异常
        sender.send(message);

        assertThat(sender.recent()).singleElement().satisfies(mail -> {
            assertThat(mail.to()).containsExactly("chenyiming@example.com");
            assertThat(mail.subject()).isEqualTo("算法学习平台：登录验证码");
            assertThat(mail.text()).contains("登录验证码：123456");
        });
    }

    @Test
    void keepsOnlyTheLatestTwenty() {
        DemoMailSender sender = new DemoMailSender();
        for (int i = 0; i < 25; i++) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo("user" + i + "@example.com");
            message.setSubject("第 " + i + " 封");
            message.setText("内容 " + i);
            sender.send(message);
        }
        assertThat(sender.recent()).hasSize(20);
        assertThat(sender.recent().getFirst().subject()).isEqualTo("第 5 封");
        assertThat(sender.recent().getLast().subject()).isEqualTo("第 24 封");
    }

    @Test
    void recordsPlainTextPartOfHtmlMail() throws Exception {
        DemoMailSender sender = new DemoMailSender();
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("demo@algohub.local");
        helper.setTo("student@example.com");
        helper.setSubject("AlgoHub 验证码");
        helper.setText("验证码：123456", "<strong>123456</strong>");

        sender.send(message);

        assertThat(sender.recent()).singleElement().satisfies(mail -> {
            assertThat(mail.to()).containsExactly("student@example.com");
            assertThat(mail.text()).contains("验证码：123456");
        });
    }
}
