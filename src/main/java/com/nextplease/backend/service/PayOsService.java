package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.exception.AppException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Giao tiếp với PayOS: tạo link thanh toán và xác minh chữ ký webhook.
 *
 * Lớp này CỐ Ý không đụng tới ví hay cộng tiền — nó chỉ biết ký, gọi và kiểm
 * chữ ký. Tiền do WalletService cộng, sau khi lớp này xác nhận gói tin là thật.
 * Tách ra để chỗ xử lý tiền không lẫn với chỗ xử lý giao thức.
 */
@Service
public class PayOsService {

    private static final Logger log = LoggerFactory.getLogger(PayOsService.class);
    private static final String CREATE_URL = "https://api-merchant.payos.vn/v2/payment-requests";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String clientId;
    private final String apiKey;
    private final String checksumKey;
    private final String frontendBaseUrl;

    public PayOsService(
            @Value("${app.payos.client-id:}") String clientId,
            @Value("${app.payos.api-key:}") String apiKey,
            @Value("${app.payos.checksum-key:}") String checksumKey,
            @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.clientId = trim(clientId);
        this.apiKey = trim(apiKey);
        this.checksumKey = trim(checksumKey);
        this.frontendBaseUrl = trim(frontendBaseUrl);
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank();
    }

    /**
     * Tạo link thanh toán, trả về checkoutUrl.
     *
     * @param orderCode mã đơn dạng số, phải là duy nhất và đã lưu sẵn ở DB
     */
    public String createPaymentLink(long orderCode, int amountVnd, String description) {
        requireConfigured();

        /* Chữ ký của PayOS tính trên ĐÚNG năm trường, theo ĐÚNG thứ tự chữ cái
           này, nối bằng '&' — không phải toàn bộ body. Sai thứ tự hay thừa một
           trường là PayOS trả lỗi chữ ký, mà thông báo lỗi của họ không chỉ ra
           sai ở đâu. */
        String returnUrl = frontendBaseUrl + "/candidates/dashboard/premium_store?topup=success";
        String cancelUrl = frontendBaseUrl + "/candidates/dashboard/premium_store?topup=cancel";
        String signData = "amount=" + amountVnd
                + "&cancelUrl=" + cancelUrl
                + "&description=" + description
                + "&orderCode=" + orderCode
                + "&returnUrl=" + returnUrl;

        Map<String, Object> body = Map.of(
                "orderCode", orderCode,
                "amount", amountVnd,
                "description", description,
                "returnUrl", returnUrl,
                "cancelUrl", cancelUrl,
                "signature", hmacSha256(signData));

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CREATE_URL))
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(res.body());

            /* PayOS trả HTTP 200 kể cả khi thất bại; lỗi nằm ở trường "code"
               trong body ("00" là thành công). Chỉ kiểm mã HTTP thì mọi lỗi
               nghiệp vụ đều lọt qua và mình tạo đơn PENDING không bao giờ được
               thanh toán. */
            String code = root.path("code").asText("");
            if (!"00".equals(code)) {
                throw new AppException(HttpStatus.BAD_GATEWAY,
                        "PayOS từ chối tạo link (mã " + code + "): "
                                + root.path("desc").asText("không rõ lý do"));
            }
            String checkoutUrl = root.path("data").path("checkoutUrl").asText("");
            if (checkoutUrl.isBlank()) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "PayOS không trả về checkoutUrl.");
            }
            return checkoutUrl;
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Gọi PayOS bị gián đoạn.");
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Không gọi được PayOS: " + e.getMessage());
        }
    }

    /**
     * Xác minh chữ ký của webhook.
     *
     * ĐÂY LÀ RANH GIỚI AN TOÀN DUY NHẤT. Endpoint webhook không có đăng nhập —
     * bất kỳ ai cũng POST vào được. Nếu bỏ qua bước này thì một gói tin tự chế
     * nói "đơn X đã trả 10 triệu" sẽ được cộng NP thật.
     *
     * Cách PayOS ký: lấy object `data`, sắp xếp khoá theo thứ tự chữ cái, nối
     * thành `k1=v1&k2=v2...`, rồi HMAC-SHA256 bằng Checksum Key.
     */
    public boolean verifyWebhook(JsonNode data, String signature) {
        if (!isConfigured() || data == null || signature == null || signature.isBlank()) {
            return false;
        }
        List<String> keys = new ArrayList<>();
        data.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);

        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            JsonNode v = data.get(k);
            // null và chuỗi "null"/"undefined" phải thành chuỗi RỖNG, không
            // phải chữ "null" — đây là quy ước của PayOS, làm khác là chữ ký
            // không bao giờ khớp với các đơn có trường trống.
            String value = (v == null || v.isNull()) ? "" : v.asText("");
            if ("null".equals(value) || "undefined".equals(value)) value = "";
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(value);
        }
        String expected = hmacSha256(sb.toString());

        /* So sánh theo thời gian hằng định. So bằng equals() thì thời gian trả
           lời tiết lộ mình đã khớp được bao nhiêu ký tự, đủ để dò ra chữ ký
           hợp lệ bằng nhiều lần thử. */
        return constantTimeEquals(expected, signature.trim());
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình PayOS. Đặt PAYOS_CLIENT_ID, PAYOS_API_KEY và "
                            + "PAYOS_CHECKSUM_KEY rồi khởi động lại backend.");
        }
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được dữ liệu PayOS", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
