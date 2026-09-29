package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import com.nextplease.backend.exception.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final int MIN_TOPUP_VND = 10_000;
    private static final int PREMIUM_PRICE_NP = 40_000;
    private static final String PREMIUM_PLAN = "candidate_premium_monthly";
    private static final int PREMIUM_DURATION_DAYS = 30;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    /** Mặc định TẮT: an toàn phải là mặc định, bật mới cần khai báo. */
    private final boolean mockTopUpEnabled;

    public WalletService(NamedParameterJdbcTemplate jdbcTemplate, ConfigService configService,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${app.wallet.mock-topup-enabled:false}") boolean mockTopUpEnabled) {
        this.mockTopUpEnabled = mockTopUpEnabled;
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    private int premiumPriceNp() { return configService.getInt("premium_price_np", PREMIUM_PRICE_NP); }
    private int minTopupVnd() { return configService.getInt("min_topup_vnd", MIN_TOPUP_VND); }

    /** Returns wallet balance, premium status, and last 20 transactions. */
    public Map<String, Object> getWallet(UUID userId) {
        Map<String, Object> wallet = fetchWalletOrThrow(userId);

        // Premium status from app_users
        OffsetDateTime premiumUntil = null;
        try {
            Object raw = jdbcTemplate.queryForObject(
                    "select premium_until from app_users where id = :userId",
                    Map.of("userId", userId), Object.class);
            premiumUntil = parseOffsetDateTime(raw);
        } catch (Exception e) {
            log.warn("Could not retrieve or parse premium_until for user {}: {}", userId, e.getMessage());
        }

        boolean isPremium = premiumUntil != null && premiumUntil.isAfter(OffsetDateTime.now(VN));

        OffsetDateTime matchAlertUntil = null;
        try {
            Object raw = jdbcTemplate.queryForObject("""
                    select expires_at from subscriptions
                    where user_id = :userId and plan_code = 'job_match_alert_monthly'
                      and status = 'ACTIVE' and expires_at > now()
                    order by expires_at desc limit 1
                    """, Map.of("userId", userId), Object.class);
            matchAlertUntil = parseOffsetDateTime(raw);
        } catch (Exception ignored) {}
        boolean hasJobMatchAlert = matchAlertUntil != null && matchAlertUntil.isAfter(OffsetDateTime.now(VN));

        List<Map<String, Object>> recentTx = jdbcTemplate.queryForList("""
                select id, amount_np, balance_after_np, transaction_type, reason, created_at
                from wallet_transactions
                where wallet_id = :walletId
                order by created_at desc
                limit 20
                """, Map.of("walletId", wallet.get("id")));

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("npBalance", wallet.get("np_balance"));
        result.put("lockedNpBalance", wallet.get("locked_np_balance"));
        result.put("isPremium", isPremium);
        result.put("premiumUntil", premiumUntil != null ? premiumUntil.toString() : null);
        result.put("hasJobMatchAlert", hasJobMatchAlert);
        result.put("jobMatchAlertUntil", matchAlertUntil != null ? matchAlertUntil.toString() : null);
        result.put("recentTransactions", recentTx);
        result.put("premiumPriceNp", premiumPriceNp());
        result.put("minTopupVnd", minTopupVnd());
        return result;
    }

    /**
     * MOCK top-up: instantly credits NP to wallet (1 VND = 1 NP).
     * Creates a payment_request + wallet_transaction atomically.
     * When real PayOS is integrated, replace with async webhook flow.
     */
    @Transactional
    /**
     * Nạp tiền GIẢ — cộng NP ngay, không qua thanh toán nào.
     *
     * Đây là một endpoint in tiền. Từ khi có PayOS, nó chỉ được phép sống ở
     * máy dev và ở app mobile chưa nối PayOS. Trên production phải để
     * APP_MOCK_TOPUP_ENABLED=false (mặc định), nếu không bất kỳ ai đăng nhập
     * cũng tự cộng 10 triệu NP cho mình.
     */
    public Map<String, Object> topUp(UUID userId, int amountVnd) {
        if (!mockTopUpEnabled) {
            throw new AppException(HttpStatus.GONE,
                    "Nạp tiền thử đã tắt. Dùng luồng thanh toán PayOS.");
        }
        int minTopup = minTopupVnd();
        if (amountVnd < minTopup) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    String.format("Số tiền nạp tối thiểu là %,d VND.", minTopup));
        }
        if (amountVnd > 10_000_000) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Số tiền nạp tối đa một lần là 10,000,000 VND.");
        }

        int amountNp = amountVnd; // 1 VND = 1 NP

        // Create payment_request record (MOCK: instantly PAID)
        String transferContent = "NP" + userId.toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID paymentId = jdbcTemplate.queryForObject("""
                insert into payment_requests
                    (user_id, amount_vnd, amount_np, transfer_content, provider, status, paid_at, expires_at)
                values
                    (:userId, :amountVnd, :amountNp, :content, 'MOCK', 'PAID', now(), now() + interval '15 minutes')
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amountVnd", amountVnd)
                .addValue("amountNp", amountNp)
                .addValue("content", transferContent),
                UUID.class);

        // Credit wallet with SELECT FOR UPDATE
        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balanceBefore = ((Number) wallet.get("np_balance")).intValue();
        int balanceAfter = balanceBefore + amountNp;

        jdbcTemplate.update("""
                update wallets set np_balance = :balance, updated_at = now()
                where id = :walletId
                """, Map.of("walletId", wallet.get("id"), "balance", balanceAfter));

        jdbcTemplate.update("""
                insert into wallet_transactions
                    (wallet_id, amount_np, balance_after_np, transaction_type, reason,
                     source_type, source_id, idempotency_key)
                values
                    (:walletId, :amount, :balanceAfter, 'TOPUP', :reason,
                     'payment_request', :paymentId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", amountNp)
                .addValue("balanceAfter", balanceAfter)
                .addValue("reason", String.format("Nạp %,d NP (MOCK)", amountNp))
                .addValue("paymentId", paymentId)
                .addValue("ikey", "topup_" + paymentId));

        log.info("[WalletService] User {} topped up {} NP → balance {}", userId, amountNp, balanceAfter);
        return Map.of(
                "amountNp", amountNp,
                "balanceAfter", balanceAfter,
                "paymentId", paymentId
        );
    }


    /* ─────────────────────── Nạp tiền thật qua PayOS ─────────────────────── */

    /**
     * Tạo một yêu cầu nạp tiền ở trạng thái PENDING.
     *
     * KHÔNG cộng NP ở đây. Tiền chỉ được cộng khi webhook của PayOS về và chữ
     * ký hợp lệ — xem {@link #creditFromPayOs}. Người dùng quay lại returnUrl
     * KHÔNG phải bằng chứng đã trả: đó là URL trong trình duyệt của họ, gõ tay
     * được trong hai giây.
     *
     * @return orderCode để gọi sang PayOS
     */
    @Transactional
    public long createTopUpRequest(UUID userId, int amountVnd) {
        validateTopUpAmount(amountVnd);

        /* orderCode phải là số, duy nhất toàn hệ thống, và KHÔNG đoán được
           thứ tự để người ngoài không dò được đơn của người khác. Mốc thời
           gian mili-giây cho tính duy nhất và tăng dần; ba chữ số ngẫu nhiên
           cuối tránh đụng khi hai người bấm trong cùng một mili-giây. */
        long orderCode = System.currentTimeMillis() * 1000
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);

        String transferContent = "NP" + userId.toString().replace("-", "").substring(0, 8).toUpperCase();

        jdbcTemplate.update("""
                insert into payment_requests
                    (user_id, amount_vnd, amount_np, transfer_content, provider, status,
                     order_code, expires_at)
                values
                    (:userId, :amountVnd, :amountVnd, :content, 'PAYOS', 'PENDING',
                     :orderCode, now() + interval '15 minutes')
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amountVnd", amountVnd)
                .addValue("content", transferContent)
                .addValue("orderCode", orderCode));

        return orderCode;
    }

    /**
     * Cộng NP sau khi PayOS xác nhận đã thu tiền.
     *
     * CHỈ gọi từ webhook, và CHỈ sau khi PayOsService đã xác minh chữ ký.
     *
     * Chống cộng trùng bằng chính câu UPDATE có điều kiện: PayOS gửi lại
     * webhook khi không nhận được HTTP 200, nên cùng một lần trả tiền có thể
     * tới nhiều lần. `where status = 'PENDING'` khiến lần thứ hai cập nhật 0
     * dòng, và mình dừng ngay. Kiểm bằng `select` trước rồi mới `update` sẽ
     * KHÔNG an toàn — hai webhook chạy song song đều đọc thấy PENDING.
     *
     * @return true nếu lần này thật sự cộng tiền
     */
    @Transactional
    public boolean creditFromPayOs(long orderCode, int paidAmountVnd) {
        Map<String, Object> req;
        try {
            req = jdbcTemplate.queryForMap("""
                    select id, user_id, amount_vnd, status
                    from payment_requests
                    where order_code = :orderCode and provider = 'PAYOS'
                    """, Map.of("orderCode", orderCode));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.warn("[PayOS] Webhook cho orderCode {} không khớp đơn nào", orderCode);
            return false;
        }

        int expected = ((Number) req.get("amount_vnd")).intValue();
        /* Đối chiếu với số ĐÃ LƯU, không tin số trong webhook. Chữ ký chỉ
           chứng minh gói tin đến từ PayOS, không chứng minh nó khớp đơn của
           mình — một gói tin hợp lệ của đơn 10.000đ không được phép cộng cho
           đơn 500.000đ. */
        if (paidAmountVnd != expected) {
            log.warn("[PayOS] orderCode {} lệch tiền: webhook {} đ, đơn {} đ",
                    orderCode, paidAmountVnd, expected);
            return false;
        }

        int updated = jdbcTemplate.update("""
                update payment_requests
                set status = 'PAID', paid_at = now(), updated_at = now()
                where order_code = :orderCode and status = 'PENDING'
                """, Map.of("orderCode", orderCode));
        if (updated != 1) {
            log.info("[PayOS] orderCode {} đã xử lý trước đó, bỏ qua", orderCode);
            return false;
        }

        UUID userId = (UUID) req.get("user_id");
        UUID paymentId = (UUID) req.get("id");

        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balanceAfter = ((Number) wallet.get("np_balance")).intValue() + expected;

        jdbcTemplate.update("""
                update wallets set np_balance = :balance, updated_at = now()
                where id = :walletId
                """, Map.of("walletId", wallet.get("id"), "balance", balanceAfter));

        jdbcTemplate.update("""
                insert into wallet_transactions
                    (wallet_id, amount_np, balance_after_np, transaction_type, reason,
                     source_type, source_id, idempotency_key)
                values
                    (:walletId, :amount, :balanceAfter, 'TOPUP', :reason,
                     'payment_request', :paymentId, :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", expected)
                .addValue("balanceAfter", balanceAfter)
                .addValue("reason", String.format("Nạp %,d NP qua PayOS", expected))
                .addValue("paymentId", paymentId)
                .addValue("ikey", "payos_" + orderCode));

        log.info("[PayOS] Cộng {} NP cho user {} → số dư {}", expected, userId, balanceAfter);
        return true;
    }

    /**
     * Trang thai mot don nap, cho frontend hoi sau khi nguoi dung quay lai.
     *
     * Loc theo user_id chu khong chi order_code: khong co dieu kien do thi bat
     * ky ai doan duoc order_code cung xem duoc don nap cua nguoi khac.
     */
    public Map<String, Object> getTopUpStatus(UUID userId, long orderCode) {
        try {
            return jdbcTemplate.queryForMap("""
                    select status, amount_vnd as "amountVnd", paid_at as "paidAt"
                    from payment_requests
                    where order_code = :orderCode and user_id = :userId
                    """, Map.of("orderCode", orderCode, "userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(HttpStatus.NOT_FOUND, "Khong tim thay yeu cau nap tien nay.");
        }
    }

    private void validateTopUpAmount(int amountVnd) {
        int minTopup = minTopupVnd();
        if (amountVnd < minTopup) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    String.format("Số tiền nạp tối thiểu là %,d VND.", minTopup));
        }
        if (amountVnd > 10_000_000) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                    "Số tiền nạp tối đa một lần là 10,000,000 VND.");
        }
    }

    /**
     * Deducts 40,000 NP and activates Premium Pass for 30 days.
     * Extends existing premium if already active.
     */
    @Transactional
    public Map<String, Object> buyPremium(UUID userId) {
        // Lock wallet
        Map<String, Object> wallet = lockWalletOrThrow(userId);
        int balance = ((Number) wallet.get("np_balance")).intValue();
        int premiumPrice = premiumPriceNp();

        if (balance < premiumPrice) {
            throw new AppException(HttpStatus.PAYMENT_REQUIRED,
                    String.format("Số dư NP không đủ. Cần %,d NP, hiện tại %,d NP.", premiumPrice, balance),
                    "INSUFFICIENT_NP");
        }

        // Deduct NP
        int newBalance = balance - premiumPrice;
        jdbcTemplate.update("""
                update wallets set np_balance = :balance, updated_at = now()
                where id = :walletId
                """, Map.of("walletId", wallet.get("id"), "balance", newBalance));

        jdbcTemplate.update("""
                insert into wallet_transactions
                    (wallet_id, amount_np, balance_after_np, transaction_type, reason,
                     source_type, idempotency_key)
                values
                    (:walletId, :amount, :balanceAfter, 'PREMIUM_PURCHASE',
                     'Mua Premium Pass 30 ngày', 'subscription',
                     :ikey)
                """, new MapSqlParameterSource()
                .addValue("walletId", wallet.get("id"))
                .addValue("amount", -premiumPrice)
                .addValue("balanceAfter", newBalance)
                .addValue("ikey", "premium_" + userId + "_" + System.currentTimeMillis()));

        // Resolve new premium_until — extend if currently active
        OffsetDateTime now = OffsetDateTime.now(VN);
        OffsetDateTime currentExpiry = null;
        try {
            Object raw = jdbcTemplate.queryForObject(
                    "select premium_until from app_users where id = :userId",
                    Map.of("userId", userId), Object.class);
            OffsetDateTime parsed = parseOffsetDateTime(raw);
            if (parsed != null && parsed.isAfter(now)) {
                currentExpiry = parsed;
            }
        } catch (Exception ignored) {}

        OffsetDateTime newExpiry = (currentExpiry != null ? currentExpiry : now)
                .plusDays(configService.getInt("premium_duration_days", PREMIUM_DURATION_DAYS));

        // Update premium_until on app_users
        jdbcTemplate.update("""
                update app_users set premium_until = :expiry, updated_at = now()
                where id = :userId
                """, Map.of("expiry", newExpiry, "userId", userId));

        // Upsert subscription row
        jdbcTemplate.update("""
                insert into subscriptions (user_id, plan_code, price_np, status, starts_at, expires_at)
                values (:userId, :plan, :price, 'ACTIVE', now(), :expiry)
                on conflict (user_id, plan_code) where status = 'ACTIVE'
                do update set expires_at = excluded.expires_at, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("plan", PREMIUM_PLAN)
                .addValue("price", premiumPrice)
                .addValue("expiry", newExpiry));

        log.info("[WalletService] User {} bought Premium Pass → expires {}", userId, newExpiry);
        return Map.of(
                "premiumUntil", newExpiry.toString(),
                "npBalance", newBalance,
                "durationDays", PREMIUM_DURATION_DAYS
        );
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Map<String, Object> fetchWalletOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap(
                    "select id, np_balance, locked_np_balance from wallets where user_id = :userId",
                    Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Ví NP chưa được khởi tạo cho tài khoản này.");
        }
    }

    private Map<String, Object> lockWalletOrThrow(UUID userId) {
        try {
            return jdbcTemplate.queryForMap(
                    "select id, np_balance, locked_np_balance from wallets where user_id = :userId for update",
                    Map.of("userId", userId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("Ví NP chưa được khởi tạo cho tài khoản này.");
        }
    }

    private OffsetDateTime parseOffsetDateTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof OffsetDateTime odt) {
            return odt;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return OffsetDateTime.ofInstant(ts.toInstant(), VN);
        }
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(VN).toOffsetDateTime();
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            log.warn("Failed to parse date string {}: {}", raw, e.getMessage());
            return null;
        }
    }
}
