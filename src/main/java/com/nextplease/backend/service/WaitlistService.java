package com.nextplease.backend.service;

import com.nextplease.backend.dto.request.WaitlistRequest;
import com.nextplease.backend.exception.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận đăng ký danh sách chờ Early Access.
 *
 * Xem V59__waitlist_signups.sql để biết vì sao dữ liệu nằm ở đây thay vì
 * Netlify Forms.
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    /* Cố tình dễ dãi. Mục đích là chặn lỗi gõ nhầm thấy rõ ("an@gmail"), không
       phải phán xử tính hợp lệ của địa chỉ — chuyện đó chỉ có máy chủ thư trả
       lời được, và mọi biểu thức chặt chẽ hơn đều từng loại nhầm email thật. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private static final int MAX_NAME = 150;
    private static final int MAX_EMAIL = 255;
    private static final int MAX_CHOICE = 60;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String exportToken;

    public WaitlistService(NamedParameterJdbcTemplate jdbcTemplate,
                           @Value("${app.waitlist.export-token:}") String exportToken) {
        this.jdbcTemplate = jdbcTemplate;
        this.exportToken = exportToken == null ? "" : exportToken.trim();
    }

    @Transactional
    public void signUp(WaitlistRequest request) {
        if (request == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Thiếu dữ liệu đăng ký.");
        }

        /* Bot điền ô ẩn thì trả về NHƯ THÀNH CÔNG, chỉ không ghi gì. Báo lỗi
           là mách cho kẻ viết bot biết bẫy nằm ở đâu để lần sau né. */
        if (notBlank(request.botField())) {
            log.info("[Waitlist] Bỏ qua một lượt gửi dính bẫy honeypot.");
            return;
        }

        String fullName = trimTo(request.fullName(), MAX_NAME);
        String email = trimTo(request.email(), MAX_EMAIL);

        if (fullName == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Vui lòng điền họ và tên.");
        }
        if (email == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Vui lòng điền email.");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Email chưa đúng định dạng.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("fullName", fullName);
        params.put("email", email);
        params.put("careerStage", trimTo(request.careerStage(), MAX_CHOICE));
        params.put("specialization", trimTo(request.specialization(), MAX_CHOICE));
        params.put("projectCount", trimTo(request.projectCount(), MAX_CHOICE));
        params.put("mainChallenge", trimTo(request.mainChallenge(), MAX_CHOICE));
        params.put("betaInterest", trimTo(request.betaInterest(), MAX_CHOICE));
        params.put("emailConsent", Boolean.TRUE.equals(request.emailConsent()));
        params.put("source", trimTo(request.source(), 80));

        /* Gửi lại cùng một email là SỬA câu trả lời cũ, không tạo dòng trùng.
           Giữ nguyên created_at để thứ tự "ai đăng ký sớm nhất" không bị xáo —
           nhóm đang hứa ưu đãi cho 10 người đầu, nên mốc đó phải bất biến. */
        jdbcTemplate.update("""
                insert into waitlist_signups
                    (full_name, email, career_stage, specialization, project_count,
                     main_challenge, beta_interest, email_consent, source)
                values
                    (:fullName, :email, :careerStage, :specialization, :projectCount,
                     :mainChallenge, :betaInterest, :emailConsent, :source)
                on conflict (lower(email)) do update set
                    full_name      = excluded.full_name,
                    career_stage   = excluded.career_stage,
                    specialization = excluded.specialization,
                    project_count  = excluded.project_count,
                    main_challenge = excluded.main_challenge,
                    beta_interest  = excluded.beta_interest,
                    email_consent  = excluded.email_consent,
                    updated_at     = now()
                """, params);

        log.info("[Waitlist] Ghi nhận đăng ký cho {}", maskEmail(email));
    }

    /** Số người đã trong danh sách — dùng cho "bạn là người thứ N". */
    @Transactional(readOnly = true)
    public long count() {
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from waitlist_signups", Map.of(), Long.class);
        return total == null ? 0L : total;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    /** Không ghi nguyên email ra log — đó là dữ liệu cá nhân. */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * Cổng duy nhất của endpoint xuất dữ liệu.
     *
     * Trả 404 chứ không 401/403 trong cả hai trường hợp (chưa cấu hình, hoặc
     * sai token): 403 là lời xác nhận "có endpoint ở đây và bạn đoán gần
     * đúng". 404 thì người dò không học được gì.
     */
    public void assertExportAllowed(String token) {
        if (exportToken.isEmpty()) {
            log.warn("[Waitlist] Có người gọi endpoint xuất nhưng WAITLIST_EXPORT_TOKEN chưa được đặt.");
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy.");
        }
        byte[] expected = exportToken.getBytes(StandardCharsets.UTF_8);
        byte[] given = (token == null ? "" : token.trim()).getBytes(StandardCharsets.UTF_8);
        /* So sánh theo thời gian hằng định. `equals()` của String thoát ra ở
           ký tự lệch đầu tiên, nên thời gian trả lời rò rỉ độ dài tiền tố
           đúng — đủ để dò từng ký tự một. */
        if (!MessageDigest.isEqual(expected, given)) {
            log.warn("[Waitlist] Từ chối một lượt gọi endpoint xuất vì sai token.");
            throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy.");
        }
    }

    /**
     * Toàn bộ danh sách, cũ trước mới sau.
     *
     * Trả TẤT CẢ chứ không phân trang hay lọc theo mốc thời gian: script bên
     * Google Sheet ghi đè cả sheet mỗi lần chạy, nên thao tác là bất biến —
     * chạy lại bao nhiêu lần cũng ra cùng kết quả, không sinh dòng trùng, và
     * dòng nào sửa ở DB thì sheet cũng cập nhật theo. Danh sách chờ cỡ vài
     * nghìn dòng thì cách này vừa đơn giản vừa đủ nhanh.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportAll() {
        return jdbcTemplate.queryForList("""
                select created_at, full_name, email, career_stage, specialization,
                       project_count, main_challenge, beta_interest, email_consent, source
                from waitlist_signups
                order by created_at
                """, Map.of());
    }
}
