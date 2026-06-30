package com.nextplease.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lọc nội dung do người dùng nhập (tiêu đề/mô tả tin & quest...).
 *
 * Hai lớp:
 *   1) Blocklist tiếng Việt (đã bỏ dấu) — bắt phần lớn ca thực tế.
 *   2) PurgoMalum (API công khai, miễn phí) — bắt thêm từ tục tiếng Anh.
 *
 * Triết lý: FAIL-OPEN. Nếu API lỗi/timeout thì KHÔNG chặn người dùng — chỉ dựa vào
 * blocklist nội bộ. Kết quả là "cờ cảnh báo" để Admin xem, KHÔNG chặn cứng đăng tin.
 */
@Service
public class ContentModerationService {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationService.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Từ cấm tiếng Việt ở dạng chuẩn hoá (chữ thường, đã bỏ dấu, đ -> d). */
    private static final Set<String> VN_BLOCKLIST = Set.of(
            "dit", "du ma", "do ma", "dcm", "dkm", "ddm", "dmm", "vcl", "vl", "clm",
            "cmm", "cc", "loz", "lol", "con cho", "do cho", "thang cho", "do ngu",
            "thang ngu", "con ngu", "ngu si", "mat day", "mat dich", "khon nan",
            "do dien", "thang dien", "do cho de", "cho de", "su vat", "bo me may"
    );

    /**
     * @return true nếu nội dung nghi ngờ chứa từ ngữ không phù hợp.
     */
    public boolean containsProfanity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (localVietnameseCheck(text)) {
            return true;
        }
        return purgomalumCheck(text);
    }

    private boolean localVietnameseCheck(String text) {
        String norm = normalize(text);
        String padded = " " + norm + " ";
        for (String bad : VN_BLOCKLIST) {
            if (padded.contains(" " + bad + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean purgomalumCheck(String text) {
        try {
            String url = "https://www.purgomalum.com/service/containsprofanity?text="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && "true".equalsIgnoreCase(resp.body().trim());
        } catch (Exception e) {
            // FAIL-OPEN: API công khai không có SLA — lỗi thì bỏ qua, không chặn người dùng.
            log.warn("[Moderation] PurgoMalum không phản hồi, bỏ qua (fail-open): {}", e.getMessage());
            return false;
        }
    }

    /** Chuẩn hoá: chữ thường, bỏ dấu, đ->d, chỉ giữ chữ-số-khoảng trắng. */
    private String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd'); // đ
        n = Pattern.compile("[^a-z0-9\\s]").matcher(n).replaceAll(" ");
        return n.replaceAll("\\s+", " ").trim();
    }
}
