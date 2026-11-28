package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Số liệu tiền vào hệ thống cho tab Tài chính và trang Tổng quan của admin.
 * Phần tính toán nằm ở {@link FinanceSummary}.
 *
 * Danh sách "tài khoản test" (tài khoản nhóm dùng để nạp thử qua cổng thật) lưu
 * trong system_configs, khoá {@link #TEST_ACCOUNTS_KEY}, dạng chuỗi UUID cách
 * nhau bởi dấu phẩy. Dùng bảng cấu hình có sẵn để không phải đổi schema.
 * Nhóm cấu hình 'finance' được ẩn khỏi trang Cấu hình hệ thống vì nó được quản lý
 * ngay trong tab Tài chính.
 */
@Service
public class AdminFinanceService {

    public static final String TEST_ACCOUNTS_KEY = "finance_test_account_ids";

    private static final Logger log = LoggerFactory.getLogger(AdminFinanceService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public AdminFinanceService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** Tách chuỗi đã lưu thành tập UUID hợp lệ; bỏ qua phần tử rỗng hoặc sai định dạng. */
    static Set<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) {
                continue;
            }
            try {
                out.add(UUID.fromString(id).toString());
            } catch (IllegalArgumentException ignored) {
                // Giá trị hỏng thì bỏ qua thay vì làm hỏng cả trang tài chính.
            }
        }
        return out;
    }

    Set<String> loadTestUserIds() {
        List<String> rows = jdbc.queryForList(
                "select value_text from system_configs where config_key = :k",
                Map.of("k", TEST_ACCOUNTS_KEY), String.class);
        return rows.isEmpty() ? new LinkedHashSet<>() : parseIds(rows.get(0));
    }

    private void saveTestUserIds(Set<String> ids, UUID adminId) {
        String value = String.join(",", ids);
        jdbc.update("""
                insert into system_configs (config_key, value_text, data_type, config_group, label, description, updated_by, updated_at)
                values (:k, :v, 'TEXT', 'finance', 'Tài khoản test (không tính vào tài chính)',
                        'Quản lý trong tab Tài chính của trang admin.', :admin, now())
                on conflict (config_key) do update
                    set value_text = excluded.value_text,
                        updated_by = excluded.updated_by,
                        updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("k", TEST_ACCOUNTS_KEY)
                .addValue("v", value)
                .addValue("admin", adminId));
    }

    private void audit(UUID adminId, String action, String userId) {
        try {
            jdbc.update("""
                    insert into audit_logs (actor_user_id, action, entity_type, entity_id, metadata)
                    values (:admin, :action, 'app_user', cast(:target as uuid), '{}'::jsonb)
                    """, new MapSqlParameterSource()
                    .addValue("admin", adminId)
                    .addValue("action", action)
                    .addValue("target", userId));
        } catch (Exception e) {
            log.warn("Failed to write finance audit log ({}): {}", action, e.getMessage());
        }
    }

    public List<Map<String, Object>> listTestAccounts() {
        Set<String> ids = loadTestUserIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> uuids = ids.stream().map(UUID::fromString).collect(Collectors.toList());
        return jdbc.queryForList("""
                select u.id::text as "userId",
                       coalesce(u.display_name, split_part(u.email, '@', 1)) as "name",
                       u.email
                from app_users u
                where u.id in (:ids)
                order by u.email
                """, Map.of("ids", uuids));
    }

    /** Đánh dấu một tài khoản là tài khoản test, theo email hoặc theo id người dùng. */
    public List<Map<String, Object>> addTestAccount(String email, String userId, UUID adminId) {
        String resolved;
        if (userId != null && !userId.isBlank()) {
            Set<String> parsed = parseIds(userId);
            if (parsed.isEmpty()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Mã người dùng không hợp lệ.");
            }
            resolved = parsed.iterator().next();
            List<String> found = jdbc.queryForList(
                    "select id::text from app_users where id = :id",
                    Map.of("id", UUID.fromString(resolved)), String.class);
            if (found.isEmpty()) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng này.");
            }
        } else if (email != null && !email.isBlank()) {
            List<String> found = jdbc.queryForList(
                    "select id::text from app_users where lower(email) = lower(:e)",
                    Map.of("e", email.trim()), String.class);
            if (found.isEmpty()) {
                throw new AppException(HttpStatus.NOT_FOUND, "Không có tài khoản nào dùng email " + email.trim() + ".");
            }
            resolved = found.get(0);
        } else {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cần nhập email của tài khoản test.");
        }

        Set<String> ids = loadTestUserIds();
        if (ids.add(resolved)) {
            saveTestUserIds(ids, adminId);
            audit(adminId, "admin.finance_test_account.added", resolved);
        }
        return listTestAccounts();
    }

    public List<Map<String, Object>> removeTestAccount(String userId, UUID adminId) {
        Set<String> ids = loadTestUserIds();
        Set<String> target = parseIds(userId);
        if (!target.isEmpty() && ids.removeAll(target)) {
            saveTestUserIds(ids, adminId);
            audit(adminId, "admin.finance_test_account.removed", target.iterator().next());
        }
        return listTestAccounts();
    }

    /** Bỏ các dòng của tài khoản test, giữ tối đa {@code limit} dòng đầu, giữ thứ tự. */
    static List<Map<String, Object>> withoutTestUsers(List<Map<String, Object>> rows, Set<String> testIds, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (out.size() >= limit) {
                break;
            }
            if (!testIds.contains(String.valueOf(r.get("userId")))) {
                out.add(r);
            }
        }
        return out;
    }

    public Map<String, Object> getOverview() {
        Set<String> testIds = loadTestUserIds();

        List<FinanceSummary.Payment> payments = jdbc.query("""
                select user_id::text as user_id, provider, status, amount_vnd, created_at, paid_at
                from payment_requests
                """, Map.of(), (rs, i) -> new FinanceSummary.Payment(
                rs.getString("user_id"),
                rs.getString("provider"),
                rs.getString("status"),
                rs.getLong("amount_vnd"),
                instant(rs, "created_at"),
                instant(rs, "paid_at")));

        List<FinanceSummary.WalletTxn> txns = jdbc.query("""
                select w.user_id::text as user_id, wt.transaction_type, wt.amount_np, wt.created_at
                from wallet_transactions wt
                join wallets w on w.id = wt.wallet_id
                where wt.transaction_type <> 'TOPUP'
                """, Map.of(), (rs, i) -> new FinanceSummary.WalletTxn(
                rs.getString("user_id"),
                rs.getString("transaction_type"),
                rs.getLong("amount_np"),
                instant(rs, "created_at")));

        List<Map<String, Object>> balances = jdbc.queryForList(
                "select user_id::text as user_id, np_balance + locked_np_balance as total from wallets", Map.of());
        long outstanding = 0;
        for (Map<String, Object> b : balances) {
            if (!testIds.contains(String.valueOf(b.get("user_id"))) && b.get("total") instanceof Number n) {
                outstanding += n.longValue();
            }
        }

        Map<String, Object> summary = FinanceSummary.build(
                payments, txns, outstanding, LocalDate.now(FinanceSummary.VN), testIds);

        List<Map<String, Object>> recent = jdbc.queryForList("""
                select pr.id,
                       pr.user_id::text as "userId",
                       pr.amount_vnd as "amountVnd",
                       pr.provider,
                       coalesce(pr.paid_at, pr.created_at) as "paidAt",
                       coalesce(u.display_name, split_part(u.email, '@', 1), 'Người dùng') as "userName",
                       u.email as "userEmail"
                from payment_requests pr
                left join app_users u on u.id = pr.user_id
                where pr.status = 'PAID' and pr.provider <> 'MOCK'
                order by coalesce(pr.paid_at, pr.created_at) desc
                limit 40
                """, Map.of());
        summary.put("recentPayments", withoutTestUsers(recent, testIds, 10));

        List<Map<String, Object>> unpaid = jdbc.queryForList("""
                select pr.id,
                       pr.user_id::text as "userId",
                       pr.status,
                       pr.amount_vnd as "amountVnd",
                       pr.provider,
                       pr.created_at as "createdAt",
                       coalesce(u.display_name, split_part(u.email, '@', 1), 'Người dùng') as "userName",
                       u.email as "userEmail"
                from payment_requests pr
                left join app_users u on u.id = pr.user_id
                where pr.status in ('PENDING', 'CANCELLED', 'EXPIRED', 'FAILED') and pr.provider <> 'MOCK'
                order by pr.created_at desc
                limit 300
                """, Map.of());
        summary.put("unpaidRequests", withoutTestUsers(unpaid, testIds, 200));
        summary.put("testAccounts", listTestAccounts());
        summary.put("generatedAt", Instant.now().toString());
        return summary;
    }
}
