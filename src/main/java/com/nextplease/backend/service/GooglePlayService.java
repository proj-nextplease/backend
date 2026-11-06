package com.nextplease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.exception.AppException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Giao tiếp với Google Play Developer API (Android Publisher) để xác minh và
 * tiêu thụ (consume) giao dịch mua gói NP trong app Android.
 *
 * Giống PayOsService, lớp này KHÔNG đụng tới ví — nó chỉ trả lời "giao dịch
 * này có thật, đã trả tiền, của gói nào". Tiền do WalletService cộng.
 *
 * Xác thực bằng service account: tự ký JWT RS256 rồi đổi lấy access token
 * (luồng OAuth 2.0 server-to-server của Google). Tự làm thay vì kéo thư viện
 * google-api-client vì chỉ cần đúng hai lời gọi REST.
 */
@Service
public class GooglePlayService {

    private static final Logger log = LoggerFactory.getLogger(GooglePlayService.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private static final String API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";

    /**
     * Gói NP bán trên Google Play: productId → số NP (= giá VND, 1 VND = 1 NP).
     *
     * Số NP lấy từ ĐÂY, không bao giờ từ app gửi lên. ProductId phải trùng
     * từng ký tự với sản phẩm tạo trong Play Console.
     */
    public static final Map<String, Integer> PRODUCTS = Map.of(
            "np_39k", 39_000,
            "np_69k", 69_000,
            "np_99k", 99_000,
            "np_199k", 199_000);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String packageName;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public GooglePlayService(
            @Value("${app.google-play.package-name:online.fonlio.app}") String packageName,
            @Value("${app.google-play.service-account-json:}") String serviceAccountJson) {
        this.packageName = packageName == null ? "" : packageName.trim();

        String email = "";
        String uri = "https://oauth2.googleapis.com/token";
        PrivateKey key = null;
        String raw = serviceAccountJson == null ? "" : serviceAccountJson.trim();
        if (!raw.isEmpty()) {
            try {
                if (!raw.startsWith("{")) {
                    raw = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
                }
                JsonNode json = objectMapper.readTree(raw);
                email = json.path("client_email").asText("");
                uri = json.path("token_uri").asText(uri);
                key = parsePrivateKey(json.path("private_key").asText(""));
            } catch (Exception e) {
                // Không ném lỗi lúc khởi động: cấu hình sai chỉ làm tắt tính
                // năng nạp qua Google Play, không được làm sập cả backend.
                log.error("[GooglePlay] GOOGLE_PLAY_SERVICE_ACCOUNT_JSON không đọc được: {}", e.getMessage());
                email = "";
                key = null;
            }
        }
        this.clientEmail = email;
        this.tokenUri = uri;
        this.privateKey = key;
    }

    public boolean isConfigured() {
        return privateKey != null && !clientEmail.isBlank() && !packageName.isBlank();
    }

    /** Kết quả xác minh một giao dịch mua gói NP. */
    public record ProductPurchase(
            int purchaseState,          // 0 = đã trả, 1 = đã huỷ, 2 = đang chờ
            int consumptionState,       // 0 = chưa tiêu thụ, 1 = đã tiêu thụ
            String orderId,
            String obfuscatedAccountId,
            boolean testPurchase) {
    }

    /**
     * Hỏi Google trạng thái thật của một giao dịch.
     *
     * Đây là ranh giới an toàn: purchaseToken do app gửi lên, coi như người
     * dùng sửa được. Chỉ câu trả lời từ máy chủ Google mới là bằng chứng đã
     * trả tiền.
     */
    public ProductPurchase getProductPurchase(String productId, String purchaseToken) {
        requireConfigured();
        String url = API + enc(packageName) + "/purchases/products/" + enc(productId)
                + "/tokens/" + enc(purchaseToken);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken())
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 400 || res.statusCode() == 404 || res.statusCode() == 410) {
                // Token không tồn tại / không thuộc app này / sai productId.
                throw new AppException(HttpStatus.BAD_REQUEST, "Giao dịch Google Play không hợp lệ.");
            }
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                log.error("[GooglePlay] Service account không có quyền đọc giao dịch (HTTP {}): {}",
                        res.statusCode(), truncate(res.body()));
                throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Máy chủ chưa được cấp quyền xác minh giao dịch Google Play.");
            }
            if (res.statusCode() / 100 != 2) {
                log.warn("[GooglePlay] purchases.products.get HTTP {}: {}", res.statusCode(), truncate(res.body()));
                throw new AppException(HttpStatus.BAD_GATEWAY, "Không xác minh được giao dịch với Google Play.");
            }
            JsonNode root = objectMapper.readTree(res.body());
            return new ProductPurchase(
                    root.path("purchaseState").asInt(-1),
                    root.path("consumptionState").asInt(0),
                    root.path("orderId").asText(""),
                    root.path("obfuscatedExternalAccountId").asText(""),
                    // purchaseType chỉ có mặt với giao dịch đặc biệt; 0 = mua thử
                    // bằng tài khoản người thử thanh toán (không mất tiền thật).
                    root.has("purchaseType") && root.path("purchaseType").asInt(-1) == 0);
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Gọi Google Play bị gián đoạn.");
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Không gọi được Google Play: " + e.getMessage());
        }
    }

    /**
     * Đánh dấu giao dịch đã tiêu thụ để người dùng mua lại được gói đó.
     *
     * Làm ở backend, SAU khi đã cộng NP: nếu chỉ để app làm thì app bị tắt
     * giữa chừng sẽ để giao dịch treo chưa xác nhận, và Google tự hoàn tiền
     * sau 3 ngày trong khi NP đã vào ví.
     *
     * Không ném lỗi — thất bại thì lần app gửi lại token sau sẽ thử tiếp.
     */
    public boolean consume(String productId, String purchaseToken) {
        if (!isConfigured()) return false;
        String url = API + enc(packageName) + "/purchases/products/" + enc(productId)
                + "/tokens/" + enc(purchaseToken) + ":consume";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken())
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) return true;
            log.warn("[GooglePlay] consume HTTP {}: {}", res.statusCode(), truncate(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[GooglePlay] consume lỗi: {}", e.getMessage());
        }
        return false;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình Google Play Billing. Đặt GOOGLE_PLAY_SERVICE_ACCOUNT_JSON "
                            + "rồi khởi động lại backend.");
        }
    }

    /** Access token sống 1 giờ; giữ lại dùng chung, làm mới trước khi hết 5 phút. */
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(300))) {
            return accessToken;
        }
        long now = Instant.now().getEpochSecond();
        String header = b64(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64(objectMapper.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", SCOPE,
                "aud", tokenUri,
                "iat", now,
                "exp", now + 3600)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + b64(signer.sign());

        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + enc(jwt);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            log.error("[GooglePlay] Không lấy được access token (HTTP {}): {}",
                    res.statusCode(), truncate(res.body()));
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không xác thực được với Google Play. Kiểm tra lại service account.");
        }
        JsonNode root = objectMapper.readTree(res.body());
        accessToken = root.path("access_token").asText();
        accessTokenExpiresAt = Instant.now().plusSeconds(root.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
