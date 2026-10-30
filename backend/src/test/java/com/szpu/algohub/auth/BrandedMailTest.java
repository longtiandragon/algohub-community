package com.szpu.algohub.auth;

import com.szpu.algohub.mail.BrandedMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BrandedMailTest {
    @Test void codeMailHasPlainAndHtmlAlternativesWithEscapedDynamicContent() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailTestSupport.stubMimeMessages(sender);

        String code = "314159";
        String dynamic = "<script>alert(\"x&y\")</script>";
        BrandedMail.sendCode(sender, "hello@example.test", "student@example.test", "验证码",
                "验证 <身份>", dynamic, code, 10, "详情 <b>&", "安全提示 '谨慎'");

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        MimeMessage message = captor.getValue();
        var from = (jakarta.mail.internet.InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("AlgoHub");
        assertThat(from.getAddress()).isEqualTo("hello@example.test");
        String plain = MailTestSupport.plain(message);
        String html = MailTestSupport.html(message);

        assertThat(plain).contains(dynamic, code, "验证 <身份>");
        assertThat(html).contains("&lt;script&gt;alert(&quot;x&amp;y&quot;)&lt;/script&gt;", "验证 &lt;身份&gt;")
                .contains("详情 &lt;b&gt;&amp;", "安全提示 &#39;谨慎&#39;")
                .doesNotContain(dynamic, "<script>");
        assertThat(MailTestSupport.code(message)).isEqualTo(code);
        assertThat(html).contains(code);
        assertThat(MailTestSupport.recipients(message)).containsExactly("student@example.test");
    }
}
