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

    public WalletService(NamedParameterJdbcTemplate jdbcTemplate, ConfigService configService) {
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
            if (raw instanceof OffsetDateTime odt) {
                premiumUntil = odt;
            } else if (raw != null) {
                premiumUntil = OffsetDateTime.parse(raw.toString());
            }
        } catch (Exception e) {
            log.warn("Could not parse premium_until for user {}: {}", userId, e.getMessage());
        }

        boolean isPremium = premiumUntil != null && premiumUntil.isAfter(OffsetDateTime.now(VN));

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
    public Map<String, Object> topUp(UUID userId, int amountVnd) {
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
            if (raw instanceof OffsetDateTime odt && odt.isAfter(now)) {
                currentExpiry = odt;
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
                on conflict do nothing
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
}
