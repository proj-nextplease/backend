package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

    private static final DateTimeFormatter VIETNAM_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy", Locale.forLanguageTag("vi-VN"));

    private final RestClient restClient;
    private final JavaMailSender javaMailSender;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean brevoMode;
    private final boolean smtpMode;
    private final String fromAddress;
    private final String fromName;

    public EmailDeliveryService(
            @Value("${app.mail.enabled:false}") boolean enabled,
            @Value("${app.mail.from-address:no-reply@nextplease.vn}") String fromAddress,
            @Value("${app.mail.from-name:nextplease}") String fromName,
            @Value("${app.mail.brevo-api-key:}") String brevoApiKey,
            @Autowired(required = false) JavaMailSender javaMailSender,
            @Autowired(required = false) NamedParameterJdbcTemplate jdbcTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.javaMailSender = javaMailSender;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        boolean hasBrevo = brevoApiKey != null && !brevoApiKey.isBlank();
        this.brevoMode = enabled && hasBrevo;
        this.smtpMode = enabled && !hasBrevo && javaMailSender != null;
        this.enabled = this.brevoMode || this.smtpMode;

        if (this.brevoMode) {
            this.restClient = RestClient.builder()
                    .baseUrl("https://api.brevo.com/v3")
                    .defaultHeader("accept", "application/json")
                    .defaultHeader("api-key", brevoApiKey)
                    .defaultHeader("content-type", "application/json")
                    .build();
            log.info("[EmailDeliveryService] Initialized using Brevo REST API.");
        } else {
            this.restClient = null;
            if (this.smtpMode) {
                log.info("[EmailDeliveryService] Initialized using JavaMailSender (SMTP).");
            } else {
                log.info("[EmailDeliveryService] MOCK mode active (app.mail.enabled={}, brevoApiKey={}, smtp={}). Emails logged to console and email_logs.",
                        enabled, hasBrevo, javaMailSender != null);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
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

        String subject = "Mã OTP xác thực ứng viên nextplease";
        String textContent = buildCandidateOtpText(displayName, otp, expiresAt);
        String htmlContent = buildCandidateOtpHtml(displayName, otp, expiresAt);

        try {
            Map<String, Object> requestBody = Map.of(
                    "sender", Map.of("name", fromName, "email", fromAddress),
                    "to", List.of(Map.of("email", recipientEmail, "name", normalizeDisplayName(displayName))),
                    "subject", subject,
                    "htmlContent", htmlContent,
                    "textContent", textContent
            );

            restClient.post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully sent candidate registration OTP email to={} via Brevo API", recipientEmail);
        } catch (Exception exception) {
            log.error("Failed to send candidate registration OTP email to={} via Brevo API: {}",
                    recipientEmail, exception.getMessage(), exception);
            throw new EmailSendException("Could not send candidate registration OTP email: " + exception.getMessage(), exception);
        }
    }

    /**
     * Sends an organization-membership invitation email containing the redeem link.
     * No-op when mail delivery is disabled (the caller still returns the link in the API response).
     */
    public void sendCompanyInvitation(
            String recipientEmail,
            String companyName,
            String roleLabel,
            String inviteUrl,
            OffsetDateTime expiresAt
    ) {
        if (!isEnabled()) {
            return;
        }

        String subject = "Lời mời tham gia " + companyName + " trên nextplease";
        String safeCompany = escapeHtml(companyName);
        String safeRole = escapeHtml(roleLabel);
        String safeUrl = escapeHtml(inviteUrl);
        String safeExpires = escapeHtml(VIETNAM_TIME_FORMATTER.format(expiresAt));

        String textContent = """
                Xin chào,

                Bạn được mời tham gia tổ chức "%s" trên nextplease với vai trò %s.

                Nhấn vào liên kết sau để chấp nhận lời mời (đăng nhập hoặc đăng ký bằng chính email này):
                %s

                Lời mời có hiệu lực đến %s (giờ Việt Nam).

                nextplease
                """.formatted(companyName, roleLabel, inviteUrl, VIETNAM_TIME_FORMATTER.format(expiresAt));

        String htmlContent = """
                <!doctype html>
                <html lang="vi">
                  <body style="margin:0;background:#f4f7ff;font-family:Arial,Helvetica,sans-serif;color:#111827;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f7ff;padding:32px 16px;">
                      <tr><td align="center">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border-radius:28px;overflow:hidden;border:1px solid #dfe7fb;">
                          <tr><td style="padding:34px 34px 20px;background:linear-gradient(135deg,#2563eb,#ff7a1a);color:#ffffff;">
                            <div style="font-size:14px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;">nextplease</div>
                            <h1 style="margin:18px 0 10px;font-size:28px;line-height:1.2;">Lời mời tham gia tổ chức</h1>
                          </td></tr>
                          <tr><td style="padding:34px;">
                            <p style="margin:0 0 16px;font-size:17px;line-height:1.6;">Bạn được mời tham gia <strong>%s</strong> với vai trò <strong>%s</strong>.</p>
                            <p style="margin:0 0 24px;font-size:16px;line-height:1.7;color:#4b5563;">Đăng nhập hoặc đăng ký bằng chính email này rồi nhấn nút bên dưới để chấp nhận.</p>
                            <div style="text-align:center;">
                              <a href="%s" style="display:inline-block;background:#2563eb;color:#ffffff;text-decoration:none;font-weight:700;padding:14px 28px;border-radius:14px;">Chấp nhận lời mời</a>
                            </div>
                            <p style="margin:24px 0 0;font-size:14px;line-height:1.7;color:#6b7280;">Lời mời có hiệu lực đến %s (giờ Việt Nam).</p>
                          </td></tr>
                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(safeCompany, safeRole, safeUrl, safeExpires);

        try {
            Map<String, Object> requestBody = Map.of(
                    "sender", Map.of("name", fromName, "email", fromAddress),
                    "to", List.of(Map.of("email", recipientEmail)),
                    "subject", subject,
                    "htmlContent", htmlContent,
                    "textContent", textContent
            );
            restClient.post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes());
                        log.error("Brevo rejected company invitation email to={}: {} – {}",
                                recipientEmail, res.getStatusCode(), responseBody);
                        throw new EmailSendException(
                                "Brevo " + res.getStatusCode() + ": " + responseBody, null);
                    })
                    .toBodilessEntity();
            log.info("Sent company invitation email to={} for company={}", recipientEmail, companyName);
        } catch (Exception exception) {
            log.error("Failed to send company invitation email to={}: {}", recipientEmail, exception.getMessage(), exception);
            throw new EmailSendException("Could not send company invitation email: " + exception.getMessage(), exception);
        }
    }

    /**
     * Generic notification email (mirrors an in-app notification). Fail-soft:
     * never throws, so a mail failure can't break the triggering transaction.
     * optionalLink, when present, renders a prominent button.
     */
    public void sendNotificationEmail(String recipientEmail, String displayName, String title, String body, String link) {
        sendNotificationEmail(null, recipientEmail, displayName, title, body, link, "NOTIFICATION");
    }

    public void sendNotificationEmail(UUID userId, String recipientEmail, String displayName, String title, String body, String link, String templateCode) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }

        String safeTemplate = templateCode != null && !templateCode.isBlank() ? templateCode : "NOTIFICATION";
        String safeName = escapeHtml(normalizeDisplayName(displayName));
        String safeTitle = escapeHtml(title == null ? "Thông báo từ Fonlio / nextplease" : title);
        String safeBody = escapeHtml(body == null ? "" : body).replace("\n", "<br/>");
        String button = (link == null || link.isBlank()) ? "" :
                "<div style=\"text-align:center;margin-top:28px;\"><a href=\"" + escapeHtml(link)
                + "\" style=\"display:inline-block;background:#2563eb;color:#ffffff;text-decoration:none;font-weight:700;font-size:15px;padding:14px 28px;border-radius:12px;box-shadow:0 4px 14px rgba(37,99,235,0.3);\">Xem chi tiết trên Fonlio</a></div>";

        String htmlContent = """
                <!doctype html>
                <html lang="vi">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  </head>
                  <body style="margin:0;padding:0;background:#f3f4f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f4f6;padding:36px 16px;">
                      <tr><td align="center">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:580px;background:#ffffff;border-radius:24px;overflow:hidden;border:1px solid #e5e7eb;box-shadow:0 12px 36px rgba(0,0,0,0.06);">
                          <tr>
                            <td style="padding:32px 36px 24px;background:linear-gradient(135deg,#1d4ed8 0%%,#2563eb 50%%,#f97316 100%%);color:#ffffff;">
                              <div style="font-size:13px;font-weight:800;letter-spacing:0.14em;text-transform:uppercase;color:#dbeafe;">FONLIO · NEXTPLEASE</div>
                              <h1 style="margin:12px 0 0;font-size:24px;font-weight:800;line-height:1.3;color:#ffffff;">%s</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:36px 36px 28px;">
                              <p style="margin:0 0 16px;font-size:16px;line-height:1.6;color:#111827;">Xin chào <strong>%s</strong>,</p>
                              <div style="font-size:15px;line-height:1.7;color:#374151;">%s</div>
                              %s
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 36px;background:#f9fafb;border-top:1px solid #f3f4f6;color:#6b7280;font-size:12px;line-height:1.6;">
                              Email này được gửi tự động từ nền tảng <strong>Fonlio / nextplease</strong>.<br>
                              Vui lòng không trả lời trực tiếp email này.
                            </td>
                          </tr>
                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(safeTitle, safeName, safeBody, button);

        String textContent = (title == null ? "" : title) + "\n\n"
                + "Xin chào " + normalizeDisplayName(displayName) + ",\n\n"
                + (body == null ? "" : body)
                + (link == null || link.isBlank() ? "" : "\n\nXem chi tiết tại: " + link)
                + "\n\n--\nFonlio / nextplease";

        if (!isEnabled()) {
            log.info("[EmailDeliveryService MOCK] Notification Email -> to={}, subject='{}', body='{}', link={}",
                    recipientEmail, title, body, link);
            logEmailAudit(userId, recipientEmail, safeTemplate, "MOCK", "SENT",
                    Map.of("title", title != null ? title : "", "link", link != null ? link : ""));
            return;
        }

        if (brevoMode && restClient != null) {
            try {
                Map<String, Object> requestBody = Map.of(
                        "sender", Map.of("name", fromName, "email", fromAddress),
                        "to", List.of(Map.of("email", recipientEmail, "name", normalizeDisplayName(displayName))),
                        "subject", title == null ? "Thông báo từ Fonlio / nextplease" : title,
                        "htmlContent", htmlContent,
                        "textContent", textContent
                );
                restClient.post()
                        .uri("/smtp/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .toBodilessEntity();
                log.info("[EmailDeliveryService] Sent notification email to={} via Brevo", recipientEmail);
                logEmailAudit(userId, recipientEmail, safeTemplate, "BREVO", "SENT",
                        Map.of("title", title != null ? title : "", "link", link != null ? link : ""));
            } catch (Exception e) {
                log.warn("[EmailDeliveryService] Failed to send notification email via Brevo to={}: {}",
                        recipientEmail, e.getMessage());
                logEmailAudit(userId, recipientEmail, safeTemplate, "BREVO", "FAILED",
                        Map.of("title", title != null ? title : "", "error", e.getMessage() != null ? e.getMessage() : ""));
            }
        } else if (smtpMode && javaMailSender != null) {
            try {
                MimeMessage mimeMessage = javaMailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setFrom(new InternetAddress(fromAddress, fromName));
                helper.setTo(recipientEmail);
                helper.setSubject(title == null ? "Thông báo từ Fonlio / nextplease" : title);
                helper.setText(textContent, htmlContent);
                javaMailSender.send(mimeMessage);
                log.info("[EmailDeliveryService] Sent notification email to={} via SMTP", recipientEmail);
                logEmailAudit(userId, recipientEmail, safeTemplate, "SMTP", "SENT",
                        Map.of("title", title != null ? title : "", "link", link != null ? link : ""));
            } catch (Exception e) {
                log.warn("[EmailDeliveryService] Failed to send notification email via SMTP to={}: {}",
                        recipientEmail, e.getMessage());
                logEmailAudit(userId, recipientEmail, safeTemplate, "SMTP", "FAILED",
                        Map.of("title", title != null ? title : "", "error", e.getMessage() != null ? e.getMessage() : ""));
            }
        }
    }

    /**
     * Best-effort audit logging into email_logs table. Never throws to caller.
     */
    public void logEmailAudit(UUID userId, String recipientEmail, String templateCode,
                              String provider, String status, Map<String, Object> metadata) {
        if (jdbcTemplate == null || recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        try {
            String metaJson = metadata != null ? objectMapper.writeValueAsString(metadata) : "{}";
            jdbcTemplate.update("""
                    insert into email_logs (user_id, recipient_email, template_code, provider, status, metadata, sent_at)
                    values (:userId, :recipientEmail, :templateCode, :provider, :status, :metadata::jsonb, now())
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("recipientEmail", recipientEmail)
                    .addValue("templateCode", templateCode != null ? templateCode : "GENERAL")
                    .addValue("provider", provider != null ? provider : "UNKNOWN")
                    .addValue("status", status != null ? status : "SENT")
                    .addValue("metadata", metaJson));
        } catch (Exception e) {
            log.debug("[EmailDeliveryService] Could not persist email_log for {}: {}", recipientEmail, e.getMessage());
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
                  </td>
                </tr>
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
