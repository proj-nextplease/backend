package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.ai.AiVerificationResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GeminiVerificationService {

    private static final Logger log = LoggerFactory.getLogger(GeminiVerificationService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;

    public GeminiVerificationService(
            ObjectMapper objectMapper,
            @Value("${app.gemini.api-key:}") String apiKey,
            @Value("${app.gemini.model:gemini-3.5-flash-lite}") String model,
            @Value("${app.gemini.timeout-seconds:45}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Thẩm định minh chứng bằng Model Gemini 1.5/2.0 Flash đa phương thức (Multimodal).
     */
    public AiVerificationResult evaluateCredential(
            String candidateName,
            String candidateEmail,
            String projectName,
            String position,
            String category,
            String roleLevel,
            String description,
            String startedAt,
            String endedAt,
            String proofLink,
            List<String> proofImages
    ) {
        if (!isConfigured()) {
            log.warn("[GeminiVerificationService] Gemini API Key chưa được cấu hình. Bỏ qua thẩm định AI.");
            return AiVerificationResult.fallback("Chưa cấu hình API Key AI, chuyển hàng chờ Admin.");
        }

        try {
            List<Map<String, Object>> parts = new ArrayList<>();
            List<Map<String, Object>> imageParts = new ArrayList<>();

            // 1. Tải và đính kèm tối đa 3 ảnh minh chứng vào request
            if (proofImages != null && !proofImages.isEmpty()) {
                for (String imgUrl : proofImages) {
                    if (imgUrl == null || imgUrl.isBlank() || imageParts.size() >= 3) continue;
                    Map<String, Object> inlineData = fetchImageAsInlineData(imgUrl.trim());
                    if (inlineData != null) {
                        imageParts.add(Map.of("inlineData", inlineData));
                    }
                }
            }

            // Nếu trong proofImages chưa có ảnh hoặc còn chỗ, thử lấy ảnh từ proofLink (nếu là Google Drive hoặc link ảnh)
            if (imageParts.size() < 3 && proofLink != null && !proofLink.isBlank()) {
                Map<String, Object> inlineData = fetchImageAsInlineData(proofLink.trim());
                if (inlineData != null) {
                    imageParts.add(Map.of("inlineData", inlineData));
                }
            }

            boolean hasAttachedImages = !imageParts.isEmpty();

            // 2. Chuẩn bị prompt chỉ dẫn cho AI (kèm chỉ dẫn rõ ràng khi có ảnh hay chỉ có link ngoài)
            String promptText = buildVerificationPrompt(
                    candidateName, candidateEmail, projectName, position, category,
                    roleLevel, description, startedAt, endedAt, proofLink, hasAttachedImages
            );
            parts.add(Map.of("text", promptText));
            parts.addAll(imageParts);

            // 3. Tạo payload gửi Gemini
            Map<String, Object> requestPayload = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.1
                    )
            );

            String requestBodyJson = objectMapper.writeValueAsString(requestPayload);
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                    + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[GeminiVerificationService] Gemini API trả về mã lỗi HTTP {}: {}",
                        response.statusCode(), response.body());
                return AiVerificationResult.fallback("Gemini API lỗi (" + response.statusCode() + "), chuyển hàng chờ Admin.");
            }

            return parseGeminiResponse(response.body());

        } catch (Exception e) {
            log.error("[GeminiVerificationService] Lỗi khi gọi Gemini thẩm định minh chứng: {}", e.getMessage(), e);
            return AiVerificationResult.fallback("Lỗi ngoại lệ khi gọi AI: " + e.getMessage());
        }
    }

    private String buildVerificationPrompt(
            String candidateName,
            String candidateEmail,
            String projectName,
            String position,
            String category,
            String roleLevel,
            String description,
            String startedAt,
            String endedAt,
            String proofLink,
            boolean hasAttachedImages
    ) {
        String additionalGuidance = hasAttachedImages
                ? """
                  TRƯỜNG HỢP CÓ ẢNH MINH CHỨNG:
                  - Đọc kỹ văn bản (OCR) trên ảnh, tìm họ tên ứng viên (hoặc viết tắt/không dấu), tên chứng chỉ, cơ quan cấp.
                  - Nếu tên trùng khớp và là chứng chỉ/minh chứng hợp lệ -> Đánh giá APPROVE (85-100).
                  """
                : """
                  TRƯỜNG HỢP KHÔNG CÓ ẢNH TRỰC TIẾP (ỨNG VIÊN CHỈ CUNG CẤP LINK NGOÀI NHƯ GOOGLE DRIVE, FACEBOOK, WEBSITE):
                  - Vì bạn không thể truy cập duyệt web hoặc mở link riêng tư ngoài hệ thống, BẮT BUỘC:
                    + "verdict": "MANUAL_REVIEW"
                    + "confidence_score": 50
                    + "feedback": "Minh chứng qua liên kết ngoài (%s). AI không thể truy cập tài liệu trên liên kết ngoài, chuyển hàng chờ Admin kiểm tra thủ công."
                    + "name_matched": false
                    + "has_valid_proof": false
                    + "risk_flags": ["Minh chứng bằng liên kết ngoài", "Cần Admin mở link kiểm tra"]
                  - TUYỆT ĐỐI KHÔNG nhận xét "thiếu mộc đỏ", "thiếu chữ ký" hay "không có tên trên bằng" khi không có ảnh trực tiếp.
                  """.formatted(proofLink != null && !proofLink.isBlank() ? proofLink : "Link ngoài");

        return """
                Bạn là Chuyên viên Thẩm định Minh chứng Hoạt động (Proof of Work) cho nền tảng sinh viên NextPlease.
                Nhiệm vụ của bạn là đọc các ảnh đính kèm (giấy chứng nhận, bằng khen, chứng chỉ, thư mời, ảnh xác nhận...)
                và đối chiếu với lời khai báo của ứng viên để đưa ra kết quả thẩm định.

                THÔNG TIN ỨNG VIÊN KHAI BÁO:
                - Họ và tên ứng viên: %s
                - Email: %s
                - Tên hoạt động / Dự án: %s
                - Vai trò / Vị trí: %s
                - Loại hình: %s
                - Cấp bậc: %s (MEMBER: Thành viên, LEADER: Trưởng nhóm / Trưởng ban)
                - Mô tả công việc: %s
                - Thời gian: Từ %s đến %s
                - Link minh chứng đi kèm: %s

                QUY TẮC THẨM ĐỊNH:
                1. Đọc văn bản trên ảnh (OCR): Tìm xem có tên ứng viên không. Chấp nhận các biến thể không dấu, đảo họ tên (ví dụ: Nguyen Van A vs An Nguyen).
                2. Kiểm tra tính xác thực: Ảnh có dấu mộc đỏ, chữ ký, logo đơn vị tổ chức, hoặc chứng chỉ số chính quy (Coursera, Google, trường đại học, Đoàn - Hội...) không.
                3. Đánh giá độ khớp: Nội dung ảnh có khớp với hoạt động và vai trò đã khai không.
                4. Phát hiện bất thường / giả mạo: Ảnh chó mèo, meme, ảnh chế, ảnh chụp màn hình không liên quan, hoặc photoshop rõ rệt.

                %s

                QUY TẮC PHÁN QUYẾT (verdict) & ĐIỂM TIN CẬY (confidence_score từ 0 - 100):
                - "APPROVE": Điểm từ 85 - 100. Đủ điều kiện: Tên người nhận trùng khớp hoặc tương đương với ứng viên, bằng chứng rõ ràng, có chữ ký/mộc hoặc chứng chỉ số hợp lệ, nội dung khớp.
                - "REJECT": Điểm từ 0 - 39. Bằng chứng rác, ảnh không liên quan (chó mèo, phong cảnh, game), hoặc gian lận rõ ràng.
                - "MANUAL_REVIEW": Điểm từ 40 - 84. Ảnh bị mờ, tài liệu cần kiểm tra thêm, link riêng tư, hoặc tên người nhận bị sai lệch cần con người xem xét.

                BẮT BUỘC TRẢ VỀ JSON THEO SCHEMA SAU:
                {
                  "verdict": "APPROVE" | "REJECT" | "MANUAL_REVIEW",
                  "confidence_score": 95,
                  "feedback": "Tóm tắt ngắn gọn nhận xét bằng tiếng Việt (1-2 câu)",
                  "name_matched": true | false,
                  "detected_name": "Tên người nhận đọc được trên ảnh hoặc null",
                  "detected_issuer": "Tên đơn vị cấp chứng nhận hoặc null",
                  "detected_activity": "Tên hoạt động đọc được trên ảnh hoặc null",
                  "detected_role": "Vai trò đọc được trên ảnh hoặc null",
                  "has_valid_proof": true | false,
                  "risk_flags": ["mô tả rủi ro nếu có, ví dụ: 'ảnh mờ', 'không thấy mộc'"]
                }
                """.formatted(
                candidateName != null ? candidateName : "Chưa cập nhật",
                candidateEmail != null ? candidateEmail : "Chưa cập nhật",
                projectName != null ? projectName : "Chưa cập nhật",
                position != null ? position : "Chưa cập nhật",
                category != null ? category : "Chưa cập nhật",
                roleLevel != null ? roleLevel : "MEMBER",
                description != null ? description : "",
                startedAt != null ? startedAt : "Không rõ",
                endedAt != null ? endedAt : "Không rõ",
                proofLink != null ? proofLink : "Không có",
                additionalGuidance
        );
    }

    private static final java.util.regex.Pattern GDRIVE_PATTERN =
            java.util.regex.Pattern.compile("https?://drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?(?:.*&)?id=)([a-zA-Z0-9_-]+)");

    private String resolveDirectImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        java.util.regex.Matcher m = GDRIVE_PATTERN.matcher(rawUrl);
        if (m.find()) {
            String fileId = m.group(1);
            // Thumbnail API trả về hình ảnh trực tiếp (JPEG/PNG) phân giải cao từ Google Drive public
            return "https://drive.google.com/thumbnail?id=" + fileId + "&sz=w2048";
        }
        return rawUrl;
    }

    private Map<String, Object> fetchImageAsInlineData(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isBlank()) {
                return null;
            }

            // Trường hợp 1: Ảnh được lưu trực tiếp dưới dạng Data URI Base64 (data:image/...;base64,...)
            if (imageUrl.startsWith("data:") && imageUrl.contains(";base64,")) {
                int commaIdx = imageUrl.indexOf(";base64,");
                String mimeType = imageUrl.substring(5, commaIdx).trim();
                String base64Data = imageUrl.substring(commaIdx + 8).trim();
                if (mimeType.isBlank() || !mimeType.startsWith("image/")) {
                    mimeType = "image/jpeg";
                }
                return Map.of(
                        "mimeType", mimeType,
                        "data", base64Data
                );
            }

            // Trường hợp 2: Link ảnh qua giao thức http/https (hỗ trợ cả link Google Drive công khai)
            String fetchUrl = resolveDirectImageUrl(imageUrl);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(fetchUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body() == null || res.body().length == 0) {
                return null;
            }

            // Giới hạn ảnh < 6MB để tránh tràn bộ nhớ
            if (res.body().length > 6 * 1024 * 1024) {
                log.warn("[GeminiVerificationService] Ảnh quá lớn (>6MB), bỏ qua: {}", imageUrl);
                return null;
            }

            String mimeType = res.headers().firstValue("Content-Type").orElse("");
            if (mimeType.contains(";")) {
                mimeType = mimeType.substring(0, mimeType.indexOf(";")).trim();
            }

            // Nếu trả về text/html (trang web Google Drive private hoặc web HTML), không gửi giả làm ảnh cho AI
            if (mimeType.toLowerCase().contains("text/html") || !mimeType.toLowerCase().startsWith("image/")) {
                log.info("[GeminiVerificationService] Link cung cấp trả về HTML web hoặc không phải ảnh trực tiếp (Content-Type: {}): {}", mimeType, imageUrl);
                return null;
            }

            String base64Data = Base64.getEncoder().encodeToString(res.body());
            return Map.of(
                    "mimeType", mimeType,
                    "data", base64Data
            );
        } catch (Exception e) {
            String preview = imageUrl.length() > 60 ? imageUrl.substring(0, 60) + "..." : imageUrl;
            log.warn("[GeminiVerificationService] Không thể tải/đọc ảnh từ: {}. Lỗi: {}", preview, e.getMessage());
            return null;
        }
    }

    private AiVerificationResult parseGeminiResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return AiVerificationResult.fallback("AI không tạo ra phản hồi hợp lệ.");
            }

            JsonNode partsNode = candidates.get(0).path("content").path("parts");
            String aiRawOutput = null;
            if (partsNode.isArray()) {
                for (JsonNode part : partsNode) {
                    if (part.hasNonNull("text") && !part.path("text").asText().isBlank()) {
                        aiRawOutput = part.path("text").asText().trim();
                        break;
                    }
                }
            }
            if (aiRawOutput == null || aiRawOutput.isBlank()) {
                return AiVerificationResult.fallback("AI trả về nội dung rỗng.");
            }
            // Đề phòng trường hợp AI bọc trong ```json ... ```
            if (aiRawOutput.startsWith("```json")) {
                aiRawOutput = aiRawOutput.substring(7);
            }
            if (aiRawOutput.startsWith("```")) {
                aiRawOutput = aiRawOutput.substring(3);
            }
            if (aiRawOutput.endsWith("```")) {
                aiRawOutput = aiRawOutput.substring(0, aiRawOutput.length() - 3);
            }
            aiRawOutput = aiRawOutput.trim();

            JsonNode parsed = objectMapper.readTree(aiRawOutput);

            String verdict = parsed.path("verdict").asText("MANUAL_REVIEW").toUpperCase();
            if (!List.of("APPROVE", "REJECT", "MANUAL_REVIEW").contains(verdict)) {
                verdict = "MANUAL_REVIEW";
            }

            int score = parsed.path("confidence_score").asInt(50);
            String feedback = parsed.path("feedback").asText("Đã thẩm định bởi AI.");
            boolean nameMatched = parsed.path("name_matched").asBoolean(false);
            String detectedName = parsed.path("detected_name").isNull() ? null : parsed.path("detected_name").asText();
            String detectedIssuer = parsed.path("detected_issuer").isNull() ? null : parsed.path("detected_issuer").asText();
            String detectedActivity = parsed.path("detected_activity").isNull() ? null : parsed.path("detected_activity").asText();
            String detectedRole = parsed.path("detected_role").isNull() ? null : parsed.path("detected_role").asText();
            boolean hasValidProof = parsed.path("has_valid_proof").asBoolean(false);

            List<String> riskFlags = new ArrayList<>();
            JsonNode risks = parsed.path("risk_flags");
            if (risks.isArray()) {
                risks.forEach(r -> riskFlags.add(r.asText()));
            }

            return new AiVerificationResult(
                    verdict,
                    score,
                    feedback,
                    nameMatched,
                    detectedName,
                    detectedIssuer,
                    detectedActivity,
                    detectedRole,
                    hasValidProof,
                    riskFlags,
                    aiRawOutput
            );

        } catch (Exception e) {
            log.error("[GeminiVerificationService] Lỗi khi parse JSON từ Gemini: {}", e.getMessage(), e);
            return AiVerificationResult.fallback("Không thể phân tích dữ liệu AI trả về.");
        }
    }
}

