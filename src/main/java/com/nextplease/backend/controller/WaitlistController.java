package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.WaitlistRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.WaitlistService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh sách chờ Early Access. Công khai, không cần đăng nhập — người đăng ký
 * chưa có tài khoản, đó là lý do họ ở đây.
 */
@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ApiResponse<String> signUp(@RequestBody WaitlistRequest request) {
        waitlistService.signUp(request);
        return ApiResponse.success("Đã ghi nhận đăng ký của bạn.");
    }

    /** Chỉ trả về CON SỐ, không trả danh sách — đây là endpoint công khai. */
    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count() {
        return ApiResponse.success(Map.of("total", waitlistService.count()));
    }

    /**
     * Xuất toàn bộ danh sách cho Google Apps Script đồng bộ sang Sheet.
     *
     * Không đi qua JWT vì Apps Script không đăng nhập được bằng tài khoản
     * Supabase; cổng duy nhất là token bí mật ở header, kiểm trong
     * WaitlistService. Token rỗng (chưa cấu hình) thì endpoint coi như không
     * tồn tại — mặc định phải là đóng với một endpoint trả dữ liệu cá nhân.
     *
     * Dùng HEADER chứ không phải query param: query string bị ghi vào log của
     * reverse proxy, lịch sử trình duyệt và header Referer, nên token nằm ở
     * đó là rò ra bốn chỗ cùng lúc.
     */
    @GetMapping("/export")
    public ApiResponse<List<Map<String, Object>>> export(
            @RequestHeader(value = "X-Export-Token", required = false) String token) {
        waitlistService.assertExportAllowed(token);
        return ApiResponse.success(waitlistService.exportAll());
    }
}
