package com.szpu.algohub.demo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * 示例环境的发件器：不连任何邮件服务器，把邮件内容写进日志（scripts/start-demo.ps1 写到 backend/target/demo-start.log）。
 * 有了它，示例环境里邮箱验证码登录、绑定登录邮箱、找回密码都能完整走一遍，验证码到日志里看；
 * 页面验证脚本也从这份日志读验证码。只在 demo 配置下存在，开发和生产配置照旧用 spring.mail.* 的真实发件服务。
 */
@Component
@Profile("demo")
public class DemoMailSender extends JavaMailSenderImpl {

    private static final Logger log = LoggerFactory.getLogger(DemoMailSender.class);
    private static final int KEEP = 20;

    /** 最近发出的邮件，给测试核对用 */
    public record SentMail(Instant sentAt, List<String> to, String subject, String text) {
    }

    private final Deque<SentMail> recent = new ArrayDeque<>();

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
        for (int i = 0; i < mimeMessages.length; i++) {
            Object original = originalMessages != null && i < originalMessages.length ? originalMessages[i] : null;
            SentMail mail = original instanceof SimpleMailMessage simple ? fromSimple(simple) : fromMime(mimeMessages[i]);
            synchronized (recent) {
                if (recent.size() == KEEP) {
                    recent.removeFirst();
                }
                recent.addLast(mail);
            }
            log.info("【示例环境，没有真的发出】收件人 {}，主题：{}\n{}", String.join(", ", mail.to()), mail.subject(), mail.text());
        }
    }

    public List<SentMail> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    private static SentMail fromSimple(SimpleMailMessage message) {
        List<String> to = message.getTo() == null ? List.of() : Arrays.asList(message.getTo());
        return new SentMail(Instant.now(), to, message.getSubject(), message.getText());
    }

    private static SentMail fromMime(MimeMessage message) {
        try {
            var recipients = message.getAllRecipients();
            List<String> to = recipients == null ? List.of() : Arrays.stream(recipients).map(Object::toString).toList();
            return new SentMail(Instant.now(), to, message.getSubject(), plainText(message));
        } catch (MessagingException | java.io.IOException e) {
            throw new MailPreparationException("示例发件器读不出这封邮件的内容", e);
        }
    }

    private static String plainText(Part part) throws MessagingException, java.io.IOException {
        Object content = part.getContent();
        if (part.isMimeType("text/plain") && content instanceof String text) return text;
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = plainText(multipart.getBodyPart(i));
                if (text != null) return text;
            }
        }
        return null;
    }
}
