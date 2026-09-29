package com.nextplease.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.PayOsService;
import com.nextplease.backend.service.WalletService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nạp tiền thật qua PayOS. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PayOsService payOsService;
    private final WalletService walletService;
    private final CurrentUserService currentUserService;

    public PaymentController(PayOsService payOsService, WalletService walletService,
                             CurrentUserService currentUserService) {
        this.payOsService = payOsService;
        this.walletService = walletService;
        this.currentUserService = currentUserService;
    }

    /** Tạo link thanh toán. Chưa cộng NP — tiền chỉ vào ví khi webhook xác nhận. */
    @PostMapping("/payos/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        Object raw = body.get("amountVnd");
        int amountVnd;
        try {
            amountVnd = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Số tiền không hợp lệ.");
        }

        long orderCode = walletService.createTopUpRequest(userId, amountVnd);
        /* Mô tả hiện trên app ngân hàng của người trả. PayOS giới hạn 25 ký tự;
           dài hơn là bị từ chối tạo link. */
        String description = "NextPlease " + orderCode % 1_000_000L;
        Map<String, Object> payment = payOsService.createPaymentLink(orderCode, amountVnd, description);

        Map<String, Object> out = new java.util.LinkedHashMap<>(payment);
        out.put("orderCode", orderCode);
        out.put("amountVnd", amountVnd);
        return ApiResponse.success(out);
    }

    /**
     * Webhook PayOS gọi khi có người trả tiền.
     *
     * KHÔNG có đăng nhập — PayOS gọi từ máy chủ của họ, không mang token người
     * dùng nào. Vì vậy chữ ký là ranh giới an toàn DUY NHẤT.
     *
     * LUÔN trả HTTP 200, kể cả khi từ chối. PayOS coi mã khác 200 là thất bại
     * và gửi lại nhiều lần; trả 4xx cho một gói tin giả sẽ khiến nó dội liên
     * tục mà chẳng giải quyết được gì. Việc chấp nhận hay không nằm ở trường
     * `success` trong nội dung trả về.
     */
    @PostMapping("/payos/webhook")
    public Map<String, Object> webhook(@RequestBody JsonNode payload) {
        JsonNode data = payload.path("data");
        String signature = payload.path("signature").asText(null);

        if (!payOsService.verifyWebhook(data, signature)) {
            // Không ghi nội dung gói tin vào log: nó do người ngoài gửi, và
            // log là nơi rất dễ rò rỉ ra ngoài.
            log.warn("[PayOS] Webhook có chữ ký không hợp lệ, đã bỏ qua");
            return Map.of("success", false, "message", "invalid signature");
        }

        /* PayOS gửi một webhook thử khi mình khai URL, với orderCode = 123 và
           dữ liệu giả. Nó có chữ ký hợp lệ nên qua được bước trên; phải nhận
           và trả success để họ chấp nhận URL, nhưng KHÔNG cộng tiền. */
        long orderCode = data.path("orderCode").asLong(0);
        int amount = data.path("amount").asInt(0);
        String code = data.path("code").asText("");

        if (!"00".equals(code)) {
            log.info("[PayOS] orderCode {} báo mã {}, không phải thanh toán thành công", orderCode, code);
            return Map.of("success", true);
        }

        boolean credited = walletService.creditFromPayOs(orderCode, amount);
        if (!credited) {
            // Đã xử lý trước đó, lệch tiền, hoặc là webhook thử. Vẫn trả
            // success để PayOS ngừng gửi lại.
            log.info("[PayOS] orderCode {} không cộng tiền lần này", orderCode);
        }
        return Map.of("success", true);
    }

    /**
     * Người dùng bấm huỷ trên màn thanh toán của mình.
     *
     * Chỉ đánh dấu đơn ở phía mình, KHÔNG gọi PayOS huỷ. Nếu tiền đã chuyển
     * xong trước khi họ bấm huỷ thì webhook vẫn phải cộng được — vì vậy điều
     * kiện `status = 'PENDING'` là bắt buộc: đơn đã PAID thì lệnh này không
     * đụng tới.
     */
    @PostMapping("/payos/cancel")
    public ApiResponse<Map<String, Object>> cancel(@RequestBody Map<String, Object> body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        long orderCode;
        try {
            Object raw = body.get("orderCode");
            orderCode = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "orderCode không hợp lệ.");
        }
        boolean cancelled = walletService.cancelTopUpRequest(userId, orderCode);
        return ApiResponse.success(Map.of("cancelled", cancelled));
    }

    /** Cho frontend hỏi trạng thái sau khi người dùng quay lại từ PayOS. */
    @GetMapping("/payos/status")
    public ApiResponse<Map<String, Object>> status(
            @org.springframework.web.bind.annotation.RequestParam long orderCode) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(walletService.getTopUpStatus(userId, orderCode));
    }
}
