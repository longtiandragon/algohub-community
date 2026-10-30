package com.szpu.algohub.mail;

import java.nio.charset.StandardCharsets;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Shared, self-contained email layout with a plain-text alternative. */
public final class BrandedMail {
    private BrandedMail() {}

    public static void sendCode(JavaMailSender sender, String from, String to, String subject,
                                String title, String intro, String code, int minutes,
                                String detail, String safety) {
        String plain = title + "\n\n" + intro + "\n\n验证码：" + code + "\n"
                + minutes + " 分钟内有效。\n\n" + detail + "\n\n" + safety + "\n\nAlgoHub · 算法学习平台";
        String content = """
                <p style="margin:0 0 20px;color:#334155;font-size:15px;line-height:1.8">%s</p>
                <div style="margin:0 0 20px;padding:22px 18px;text-align:center;background:#f0f5ff;border:1px solid #dbe7ff;border-radius:12px">
                  <div style="margin-bottom:8px;color:#64748b;font-size:12px;letter-spacing:2px">验证码</div>
                  <div style="color:#1d4ed8;font-family:Arial,sans-serif;font-size:32px;font-weight:700;letter-spacing:8px;line-height:1.2">%s</div>
                  <div style="margin-top:10px;color:#64748b;font-size:13px">%d 分钟内有效</div>
                </div>
                <p style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.8">%s</p>
                <p style="margin:0;padding:14px 16px;color:#7c2d12;background:#fff7ed;border-radius:8px;font-size:13px;line-height:1.7">%s</p>
                """.formatted(html(intro), html(code), minutes, html(detail), html(safety));
        send(sender, from, to, subject, title, plain, content);
    }

    public static void sendNotice(JavaMailSender sender, String from, String to, String subject,
                                  String title, String body, String footnote) {
        String plain = title + "\n\n" + body + "\n\n" + footnote + "\n\nAlgoHub · 算法学习平台";
        String content = """
                <div style="padding:18px 20px;color:#334155;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;font-size:15px;line-height:1.9">%s</div>
                <p style="margin:20px 0 0;color:#64748b;font-size:13px;line-height:1.7">%s</p>
                """.formatted(html(body), html(footnote));
        send(sender, from, to, subject, title, plain, content);
    }

    private static void send(JavaMailSender sender, String from, String to, String subject,
                             String title, String plain, String content) {
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f1f5f9;font-family:Arial,'Microsoft YaHei',sans-serif">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f1f5f9"><tr><td align="center" style="padding:28px 12px">
                  <table role="presentation" width="560" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:560px;background:#fff;border:1px solid #e2e8f0;border-radius:12px">
                    <tr><td style="padding:24px 28px;background:#102a56;color:#fff;font-size:20px;font-weight:700">AlgoHub <span style="color:#93c5fd">/</span> 算法学习平台</td></tr>
                    <tr><td style="padding:28px"><h1 style="margin:0 0 18px;color:#0f172a;font-size:23px;line-height:1.4">%s</h1>%s</td></tr>
                    <tr><td style="padding:18px 28px;border-top:1px solid #e2e8f0;color:#94a3b8;font-size:12px;line-height:1.6">这是一封由 AlgoHub 自动发送的邮件，请勿直接回复。</td></tr>
                  </table>
                </td></tr></table></body></html>
                """.formatted(html(title), content);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "AlgoHub");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plain, html);
            sender.send(message);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            throw new MailPreparationException("无法创建邮件", e);
        }
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }
}
