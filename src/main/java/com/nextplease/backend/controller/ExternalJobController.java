package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.ExternalJobService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tin tuyển dụng nguồn ngoài.
 *
 * Đọc thì công khai và luôn lấy từ DB của mình. Đồng bộ thì chỉ admin, và là
 * thao tác chủ động — không có lịch chạy nền. Lý do: bản miễn phí của Careerjet
 * có giới hạn lượt gọi; nếu trang việc làm phụ thuộc API lúc người dùng mở thì
 * hết quota là mục này trống, mà điều đó thường xảy ra đúng lúc đông người.
 */
@RestController
@RequestMapping("/api/v1")
public class ExternalJobController {

    private final ExternalJobService externalJobService;
    private final CurrentUserService currentUserService;

    public ExternalJobController(ExternalJobService externalJobService,
                                 CurrentUserService currentUserService) {
        this.externalJobService = externalJobService;
        this.currentUserService = currentUserService;
    }

    /**
     * Người dùng mở tab Việc làm. Gọi thẳng Careerjet bằng IP và user-agent của
     * chính người đang xem — đúng cách API này được thiết kế để dùng — rồi ghi
     * kết quả vào DB và trả về. Careerjet hỏng thì rơi về dữ liệu đã lưu.
     */
    @GetMapping("/jobs/external")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest request) {
        return ApiResponse.success(externalJobService.search(
                q, location, limit, clientIp(request), request.getHeader("User-Agent")));
    }

    @GetMapping("/admin/external-jobs/stats")
    public ApiResponse<Map<String, Object>> stats() {
        currentUserService.requireAdmin();
        Map<String, Object> out = new HashMap<>(externalJobService.stats());
        out.put("configured", externalJobService.isConfigured());
        return ApiResponse.success(out);
    }

    /** Nạp sẵn hàng loạt trước buổi demo. Không còn là đường duy nhất để có
        dữ liệu — GET /jobs/external ở trên đã tự gọi API theo từng người dùng. */
    @PostMapping("/admin/external-jobs/sync")
    public ApiResponse<Map<String, Object>> sync(@RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        currentUserService.requireAdmin();
        Map<String, Object> b = body == null ? Map.of() : body;
        String keywords = str(b.get("keywords"));
        String location = str(b.get("location"));
        int pages = b.get("pages") instanceof Number n ? n.intValue() : 1;
        return ApiResponse.success(
                externalJobService.sync(keywords, location, pages, clientIp(request)));
    }

    @PostMapping("/admin/external-jobs/deactivate-stale")
    public ApiResponse<Map<String, Object>> deactivateStale(@RequestBody(required = false) Map<String, Object> body) {
        currentUserService.requireAdmin();
        int days = (body != null && body.get("days") instanceof Number n) ? n.intValue() : 30;
        return ApiResponse.success(Map.of("deactivated", externalJobService.deactivateStale(days)));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /* Careerjet bắt buộc gửi kèm IP của người dùng đã kích hoạt lời gọi. Sau
       proxy của Railway thì IP thật nằm ở X-Forwarded-For. */
    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
