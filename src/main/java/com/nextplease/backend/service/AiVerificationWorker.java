package com.nextplease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.ai.AiVerificationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(AiVerificationWorker.class);
    private static final UUID AI_SYSTEM_BOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final GeminiVerificationService geminiService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final CredentialService credentialService;

    public AiVerificationWorker(
            NamedParameterJdbcTemplate jdbcTemplate,
            GeminiVerificationService geminiService,
            ConfigService configService,
            ObjectMapper objectMapper,
            @Lazy CredentialService credentialService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.geminiService = geminiService;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.credentialService = credentialService;
    }

    /**
     * Chạy ngầm bất đồng bộ để thẩm định minh chứng vừa được nộp bằng AI.
     */
    @Async
    public void processAsync(UUID experienceId) {
        log.info("[AiWorker] Bắt đầu thẩm định AI cho kinh nghiệm {}", experienceId);

        // 1. Kiểm tra cấu hình có bật AI không
        int aiEnabled = configService.getInt("ai_verification_enabled", 1);
        if (aiEnabled == 0 || !geminiService.isConfigured()) {
            log.info("[AiWorker] AI verification đang tắt hoặc chưa cấu hình API key. Bỏ qua expId={}", experienceId);
            updateAiStatus(experienceId, "SKIPPED", null, null, "Hồ sơ đã được chuyển tiếp tới Ban quản trị để kiểm tra trực tiếp.", null);
            return;
        }

        updateAiStatus(experienceId, "PROCESSING", null, null, null, null);

        // 2. Lấy dữ liệu minh chứng và ứng viên
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select e.id, e.profile_id, e.project_name, e.position, e.category, e.role_level,
                       e.description, e.proof_link, e.proof_images::text as proof_images,
                       e.started_at, e.ended_at, e.verification_status,
                       u.display_name as candidate_name, u.email as candidate_email
                from experiences e
                join profiles p on p.id = e.profile_id
                join app_users u on u.id = p.user_id
                where e.id = :id and e.deleted_at is null
                """, Map.of("id", experienceId));

        if (rows.isEmpty()) {
            log.warn("[AiWorker] Không tìm thấy minh chứng {}", experienceId);
            return;
        }

        Map<String, Object> exp = rows.get(0);
        UUID profileId = (UUID) exp.get("profile_id");
        String currentStatus = (String) exp.get("verification_status");
        if (!"PENDING".equals(currentStatus)) {
            log.info("[AiWorker] Minh chứng {} không ở trạng thái PENDING (hiện tại: {}), dừng AI.", experienceId, currentStatus);
            return;
        }

        // Lấy danh sách các hoạt động đã được duyệt trước đó của ứng viên này để AI so sánh ngữ nghĩa
        List<Map<String, Object>> candidateApprovedRows = jdbcTemplate.queryForList("""
                select project_name, position, category, proof_link
                from experiences
                where profile_id = :profileId
                  and verification_status = 'APPROVED'
                  and deleted_at is null
                  and id != :id
                """, Map.of("profileId", profileId, "id", experienceId));

        List<String> approvedSummary = new ArrayList<>();
        for (Map<String, Object> ar : candidateApprovedRows) {
            String pName = (String) ar.get("project_name");
            String pos = (String) ar.get("position");
            approvedSummary.add(pName + (pos != null ? " (" + pos + ")" : ""));
        }

        List<String> images = parseImages((String) exp.get("proof_images"));

        // 3. Gọi Gemini thẩm định (kèm lịch sử các hoạt động đã duyệt để phát hiện trùng lặp)
        AiVerificationResult result = geminiService.evaluateCredential(
                (String) exp.get("candidate_name"),
                (String) exp.get("candidate_email"),
                (String) exp.get("project_name"),
                (String) exp.get("position"),
                (String) exp.get("category"),
                (String) exp.get("role_level"),
                (String) exp.get("description"),
                exp.get("started_at") != null ? exp.get("started_at").toString() : null,
                exp.get("ended_at") != null ? exp.get("ended_at").toString() : null,
                (String) exp.get("proof_link"),
                images,
                approvedSummary
        );

        log.info("[AiWorker] Kết quả AI cho expId={}: verdict={}, score={}, nameMatched={}",
                experienceId, result.verdict(), result.confidenceScore(), result.nameMatched());

        // 4. Lưu kết quả AI vào database
        updateAiStatus(
                experienceId,
                "COMPLETED",
                result.verdict(),
                result.confidenceScore(),
                result.feedback(),
                result.rawJson()
        );

        // Cập nhật gợi ý AI vào ghi chú nội bộ cho Admin xem
        saveAiNoteForAdmin(experienceId, result);

        // 5. Phân luồng Hybrid theo điểm tự tin
        int minApproveScore = configService.getInt("ai_auto_approve_min_score", 85);
        int maxRejectScore = configService.getInt("ai_auto_reject_max_score", 40);

        try {
            // Làn Xanh: Tự động Duyệt (Auto-Approve)
            if ("APPROVE".equalsIgnoreCase(result.verdict())
                    && result.confidenceScore() >= minApproveScore
                    && result.nameMatched()) {
                log.info("[AiWorker] Đạt điều kiện LÀN XANH -> AI tự động duyệt cho expId={}", experienceId);
                credentialService.approve(
                        experienceId,
                        AI_SYSTEM_BOT_ID,
                        "AI Tự động phê duyệt (Độ tin cậy: " + result.confidenceScore() + "%): " + result.feedback()
                );
                setVerifiedByType(experienceId, "AI");
                return;
            }

            // Làn Đỏ: Tự động Từ chối (Auto-Reject) khi là ảnh rác/gian lận rõ ràng
            if ("REJECT".equalsIgnoreCase(result.verdict()) && result.confidenceScore() <= maxRejectScore) {
                log.info("[AiWorker] Đạt điều kiện LÀN ĐỎ -> AI tự động từ chối cho expId={}", experienceId);
                credentialService.reject(
                        experienceId,
                        AI_SYSTEM_BOT_ID,
                        result.feedback() != null && !result.feedback().isBlank()
                                ? result.feedback()
                                : "Bằng chứng không hợp lệ hoặc không khớp với thông tin đã khai báo."
                );
                setVerifiedByType(experienceId, "AI");
                return;
            }

            // Làn Vàng: Giữ PENDING, đẩy Admin quyết định (đã lưu ghi chú AI)
            log.info("[AiWorker] Thuộc LÀN VÀNG (cần Admin duyệt) cho expId={}, score={}", experienceId, result.confidenceScore());

        } catch (Exception e) {
            log.error("[AiWorker] Lỗi khi thực hiện quyết định duyệt/từ chối của AI: {}", e.getMessage(), e);
        }
    }

    private void updateAiStatus(
            UUID experienceId,
            String aiStatus,
            String aiVerdict,
            Integer confidenceScore,
            String feedback,
            String rawJson
    ) {
        jdbcTemplate.update("""
                update experiences
                set ai_status = :status,
                    ai_verdict = :verdict,
                    ai_confidence_score = :score,
                    ai_feedback = :feedback,
                    ai_analysis_data = cast(:rawJson as jsonb),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("status", aiStatus)
                .addValue("verdict", aiVerdict)
                .addValue("score", confidenceScore)
                .addValue("feedback", feedback)
                .addValue("rawJson", rawJson != null && !rawJson.isBlank() ? rawJson : "{}")
                .addValue("id", experienceId));
    }

    private void setVerifiedByType(UUID experienceId, String type) {
        jdbcTemplate.update("""
                update experiences
                set verified_by_type = :type,
                    updated_at = now()
                where id = :id
                """, Map.of("type", type, "id", experienceId));
    }

    private void saveAiNoteForAdmin(UUID experienceId, AiVerificationResult result) {
        try {
            String noteText = String.format("[AI Đề xuất: %s - Độ tin cậy: %d%%] %s",
                    result.verdict(), result.confidenceScore(), result.feedback());

            jdbcTemplate.update("""
                    insert into admin_reviews (item_type, item_id, internal_notes, updated_at)
                    values ('EXPERIENCE', :id, :note, now())
                    on conflict (item_type, item_id)
                    do update set
                        internal_notes = case
                            when admin_reviews.internal_notes is null or admin_reviews.internal_notes = ''
                                then :note
                            else admin_reviews.internal_notes || E'\\n' || :note
                        end,
                        updated_at = now()
                    """, Map.of("id", experienceId, "note", noteText));
        } catch (Exception e) {
            log.warn("[AiWorker] Không thể lưu ghi chú AI vào admin_reviews: {}", e.getMessage());
        }
    }

    private List<String> parseImages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}

