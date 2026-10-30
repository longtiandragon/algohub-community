package com.szpu.algohub.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

final class MailTestSupport {
    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])");

    private MailTestSupport() { }

    static void stubMimeMessages(JavaMailSender mail) {
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        clearInvocations(mail);
    }

    static String plain(MimeMessage message) {
        save(message);
        return part(message, "text/plain");
    }

    static String html(MimeMessage message) {
        save(message);
        return part(message, "text/html");
    }

    static String code(MimeMessage message) {
        var matcher = CODE.matcher(plain(message));
        assertThat(matcher.find()).as("mail contains a six-digit verification code").isTrue();
        return matcher.group();
    }

    static List<String> recipients(MimeMessage message) {
        try {
            var to = message.getAllRecipients();
            if (to == null) return List.of();
            List<String> values = new ArrayList<>();
            for (var address : to) values.add(address.toString());
            return values;
        } catch (MessagingException e) {
            throw new AssertionError("Cannot read mail recipients", e);
        }
    }

    private static String part(Part parent, String mimeType) {
        try {
            Object content = parent.getContent();
            if (content instanceof String text && parent.isMimeType(mimeType)) return text;
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    String found = part(multipart.getBodyPart(i), mimeType);
                    if (found != null) return found;
                }
            }
            return null;
        } catch (MessagingException | IOException e) {
            throw new AssertionError("Cannot read mail " + mimeType + " part", e);
        }
    }

    private static void save(MimeMessage message) {
        try {
            message.saveChanges();
        } catch (MessagingException e) {
            throw new AssertionError("Cannot finalize test mail", e);
        }
    }
}
