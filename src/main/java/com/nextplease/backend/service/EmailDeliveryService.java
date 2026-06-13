package com.nextplease.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailDeliveryService {

    private static final DateTimeFormatter VIETNAM_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy", Locale.forLanguageTag("vi-VN"));

    private final Optional<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String fromAddress;
    private final String fromName;

    public EmailDeliveryService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.mail.enabled:false}") boolean enabled,
            @Value("${app.mail.from-address:no-reply@nextplease.vn}") String fromAddress,
            @Value("${app.mail.from-name:nextplease}") String fromName
    ) {
        this.mailSender = Optional.ofNullable(mailSenderProvider.getIfAvailable());
        this.enabled = enabled;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public boolean isEnabled() {
        return enabled && mailSender.isPresent();
    }

    public void sendCandidateRegistrationOtp(
            String recipientEmail,
            String displayName,
            String otp,
            OffsetDateTime expiresAt
    ) {
        if (!isEnabled()) {
            return;
        }

        try {
            MimeMessage message = mailSender.orElseThrow().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(recipientEmail);
            helper.setSubject("Mã OTP xác thực ứng viên nextplease");
            helper.setText(buildCandidateOtpText(displayName, otp, expiresAt), buildCandidateOtpHtml(displayName, otp, expiresAt));
            mailSender.orElseThrow().send(message);
        } catch (IllegalStateException | MessagingException | MailException | UnsupportedEncodingException exception) {
            throw new EmailSendException("Could not send candidate registration OTP email", exception);
        }
    }

    private String buildCandidateOtpText(String displayName, String otp, OffsetDateTime expiresAt) {
        String safeName = normalizeDisplayName(displayName);
        return """
                Chào %s,

                Mã OTP xác thực tài khoản ứng viên nextplease của bạn là: %s

                Mã này có hiệu lực đến %s (giờ Việt Nam). Nếu bạn không yêu cầu mã này, bạn có thể bỏ qua email.

                nextplease
                """.formatted(safeName, otp, VIETNAM_TIME_FORMATTER.format(expiresAt));
    }

    private String buildCandidateOtpHtml(String displayName, String otp, OffsetDateTime expiresAt) {
        String safeName = escapeHtml(normalizeDisplayName(displayName));
        String safeOtp = escapeHtml(otp);
        String safeExpiresAt = escapeHtml(VIETNAM_TIME_FORMATTER.format(expiresAt));

        return """
                <!doctype html>
                <html lang="vi">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Mã OTP nextplease</title>
                  </head>
                  <body style="margin:0;background:#f4f7ff;font-family:Arial,Helvetica,sans-serif;color:#111827;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f7ff;padding:32px 16px;">
                      <tr>
                        <td align="center">
                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border-radius:28px;overflow:hidden;border:1px solid #dfe7fb;box-shadow:0 24px 70px rgba(37,99,235,0.14);">
                            <tr>
                              <td style="padding:34px 34px 20px;background:linear-gradient(135deg,#2563eb,#ff7a1a);color:#ffffff;">
                                <div style="font-size:14px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;">nextplease</div>
                                <h1 style="margin:18px 0 10px;font-size:30px;line-height:1.18;">Xác thực tài khoản ứng viên</h1>
                                <p style="margin:0;font-size:16px;line-height:1.6;color:#eef4ff;">Chỉ còn một bước nhỏ để mở Portfolio 3D và bắt đầu gom proof thật cho hành trình của bạn.</p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:34px;">
                                <p style="margin:0 0 16px;font-size:17px;line-height:1.6;">Chào <strong>%s</strong>,</p>
                                <p style="margin:0 0 24px;font-size:16px;line-height:1.7;color:#4b5563;">Nhập mã OTP bên dưới vào trang đăng ký để xác thực email ứng viên của bạn.</p>
                                <div style="background:#eef4ff;border:1px solid #c7d7fe;border-radius:22px;padding:24px;text-align:center;">
                                  <div style="font-size:13px;font-weight:800;letter-spacing:0.14em;text-transform:uppercase;color:#2563eb;">Mã OTP của bạn</div>
                                  <div style="margin-top:10px;font-size:44px;font-weight:900;letter-spacing:0.22em;color:#111827;">%s</div>
                                  <div style="margin-top:12px;font-size:14px;color:#6b7280;">Có hiệu lực đến %s (giờ Việt Nam)</div>
                                </div>
                                <p style="margin:24px 0 0;font-size:14px;line-height:1.7;color:#6b7280;">Nếu bạn không yêu cầu mã này, cứ bỏ qua email. Không chia sẻ OTP cho bất kỳ ai để giữ tài khoản an toàn.</p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:22px 34px;background:#f8fbff;border-top:1px solid #e5edff;color:#6b7280;font-size:13px;line-height:1.6;">
                                nextplease biến trải nghiệm sinh viên thành hồ sơ ứng viên sống động.
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(safeName, safeOtp, safeExpiresAt);
    }

    private String normalizeDisplayName(String displayName) {
        return Optional.ofNullable(displayName)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse("bạn");
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
