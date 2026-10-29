package com.nextplease.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiVerificationResult(
        String verdict,             // "APPROVE", "REJECT", "MANUAL_REVIEW"
        int confidenceScore,        // 0 - 100
        String feedback,            // Nhận xét tóm tắt bằng tiếng Việt
        boolean nameMatched,        // Tên trên chứng chỉ có khớp tên ứng viên không
        String detectedName,        // Họ tên bóc tách được từ ảnh
        String detectedIssuer,      // Đơn vị/tổ chức cấp bằng/chứng nhận
        String detectedActivity,    // Tên hoạt động/sự kiện bóc tách được
        String detectedRole,        // Vị trí/vai trò bóc tách được
        boolean hasValidProof,      // Có dấu mộc đỏ, chữ ký hoặc định dạng chứng chỉ hợp lệ không
        List<String> riskFlags,     // Danh sách cờ cảnh báo (ảnh chế, photoshop, ảnh rác, mờ...)
        String rawJson              // Toàn bộ JSON gốc trả về từ Gemini
) {
    public static AiVerificationResult fallback(String reason) {
        return new AiVerificationResult(
                "MANUAL_REVIEW",
                50,
                reason,
                false,
                null,
                null,
                null,
                null,
                false,
                List.of("AI_FALLBACK"),
                "{}"
        );
    }
}

