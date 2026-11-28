package com.nextplease.backend.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tính số liệu tài chính cho trang admin từ các dòng thô.
 *
 * Tách khỏi phần truy vấn để test được bằng dữ liệu tự dựng, không cần database.
 * Nguyên tắc:
 *  - "Tiền thật" chỉ tính đơn nạp PAID qua cổng thật (PAYOS, VIETQR, GOOGLE_PLAY).
 *    MOCK là cộng tiền thử, không có đồng nào vào tài khoản ngân hàng, nên tách riêng.
 *  - Ngày của một khoản tiền là paid_at (lúc tiền về); thiếu thì dùng created_at.
 *  - Giao dịch của tài khoản test (admin đánh dấu ở tab Tài chính) cũng tách riêng,
 *    kể cả phí NP họ trả: đó là tiền nhóm tự nạp để thử, không phải doanh thu.
 *  - Mọi mốc ngày, tháng tính theo giờ Việt Nam.
 */
public final class FinanceSummary {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public record Payment(String userId, String provider, String status, long amountVnd,
                          Instant createdAt, Instant paidAt) {
    }

    public record WalletTxn(String userId, String type, long amountNp, Instant createdAt) {
    }

    private static final Map<String, String> PROVIDER_LABELS = Map.of(
            "PAYOS", "PayOS (VietQR)",
            "VIETQR", "Chuyển khoản VietQR",
            "GOOGLE_PLAY", "Google Play",
            "MOCK", "Nạp thử");

    private FinanceSummary() {
    }

    static boolean isReal(Payment p) {
        return p.provider() != null && !"MOCK".equalsIgnoreCase(p.provider());
    }

    static boolean isPaid(Payment p) {
        return "PAID".equalsIgnoreCase(p.status());
    }

    static LocalDate moneyDate(Payment p) {
        Instant t = p.paidAt() != null ? p.paidAt() : p.createdAt();
        return t == null ? null : t.atZone(VN).toLocalDate();
    }

    /** % thay đổi so với kỳ trước, làm tròn số nguyên; null khi kỳ trước bằng 0. */
    static Integer deltaPct(long current, long previous) {
        if (previous <= 0) {
            return null;
        }
        return (int) Math.round((current - previous) * 100.0 / previous);
    }

    public static Map<String, Object> build(List<Payment> payments, List<WalletTxn> walletTxns,
                                            long outstandingNp, LocalDate today) {
        return build(payments, walletTxns, outstandingNp, today, Set.of());
    }

    public static Map<String, Object> build(List<Payment> payments, List<WalletTxn> walletTxns,
                                            long outstandingNp, LocalDate today, Set<String> testUserIds) {
        Set<String> testUsers = testUserIds == null ? Set.of() : testUserIds;
        long realRevenue = 0;
        int paidCount = 0;
        int requestCount = 0;
        Set<String> payingUsers = new HashSet<>();
        long pendingAmount = 0;
        int pendingCount = 0;
        int cancelled = 0;
        int expired = 0;
        int failed = 0;
        long mockAmount = 0;
        int mockCount = 0;
        long testAmount = 0;
        int testCount = 0;
        long thisMonth = 0;
        long prevMonth = 0;

        YearMonth currentMonth = YearMonth.from(today);
        YearMonth previousMonth = currentMonth.minusMonths(1);

        Map<String, long[]> byProvider = new LinkedHashMap<>();
        for (String code : List.of("PAYOS", "GOOGLE_PLAY", "VIETQR")) {
            byProvider.put(code, new long[2]);
        }

        LocalDate dailyStart = today.minusDays(29);
        Map<LocalDate, long[]> daily = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            daily.put(dailyStart.plusDays(i), new long[2]);
        }
        Map<YearMonth, long[]> monthly = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) {
            monthly.put(currentMonth.minusMonths(i), new long[2]);
        }

        for (Payment p : payments) {
            if (p.userId() != null && testUsers.contains(p.userId())) {
                if (isPaid(p)) {
                    testCount++;
                    testAmount += p.amountVnd();
                }
                continue;
            }
            if (!isReal(p)) {
                if (isPaid(p)) {
                    mockCount++;
                    mockAmount += p.amountVnd();
                }
                continue;
            }
            requestCount++;
            String status = p.status() == null ? "" : p.status().toUpperCase();
            switch (status) {
                case "PAID" -> {
                    paidCount++;
                    realRevenue += p.amountVnd();
                    if (p.userId() != null) {
                        payingUsers.add(p.userId());
                    }
                    long[] prov = byProvider.computeIfAbsent(p.provider().toUpperCase(), k -> new long[2]);
                    prov[0]++;
                    prov[1] += p.amountVnd();
                    LocalDate d = moneyDate(p);
                    if (d != null) {
                        long[] day = daily.get(d);
                        if (day != null) {
                            day[0]++;
                            day[1] += p.amountVnd();
                        }
                        YearMonth ym = YearMonth.from(d);
                        long[] month = monthly.get(ym);
                        if (month != null) {
                            month[0]++;
                            month[1] += p.amountVnd();
                        }
                        if (ym.equals(currentMonth)) {
                            thisMonth += p.amountVnd();
                        } else if (ym.equals(previousMonth)) {
                            prevMonth += p.amountVnd();
                        }
                    }
                }
                case "PENDING" -> {
                    pendingCount++;
                    pendingAmount += p.amountVnd();
                }
                case "CANCELLED" -> cancelled++;
                case "EXPIRED" -> expired++;
                case "FAILED" -> failed++;
                default -> {
                    // RESOLVED và trạng thái lạ: vẫn tính là một lượt tạo mã, không cộng tiền.
                }
            }
        }

        long jobFees = 0;
        long exportFees = 0;
        long premium = 0;
        long otherSpend = 0;
        long rewards = 0;
        long adjustments = 0;
        for (WalletTxn t : walletTxns) {
            if (t.userId() != null && testUsers.contains(t.userId())) {
                continue;
            }
            String type = t.type() == null ? "" : t.type().toUpperCase();
            long amt = t.amountNp();
            switch (type) {
                case "JOB_ACCEPTANCE_FEE" -> jobFees += Math.abs(amt);
                case "PORTFOLIO_EXPORT_FEE" -> exportFees += Math.abs(amt);
                case "PREMIUM_PURCHASE" -> premium += Math.abs(amt);
                case "SPEND", "ITEM_PURCHASE", "BOOST", "SUBSCRIPTION" -> otherSpend += Math.abs(amt);
                case "REWARD" -> rewards += amt;
                case "ADJUSTMENT" -> adjustments += amt;
                default -> {
                    // TOPUP đã tính ở phía tiền vào; REFUND hiện chưa phát sinh.
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realRevenueVnd", realRevenue);
        out.put("paidCount", paidCount);
        out.put("payingUsers", payingUsers.size());
        out.put("avgTopupVnd", paidCount == 0 ? 0 : Math.round((double) realRevenue / paidCount));
        out.put("requestCount", requestCount);
        out.put("successRatePct", requestCount == 0 ? 0.0 : Math.round(paidCount * 1000.0 / requestCount) / 10.0);
        out.put("pendingCount", pendingCount);
        out.put("pendingAmountVnd", pendingAmount);
        out.put("cancelledCount", cancelled);
        out.put("expiredCount", expired);
        out.put("failedCount", failed);
        out.put("mockCount", mockCount);
        out.put("mockAmountVnd", mockAmount);
        out.put("testAccountCount", testUsers.size());
        out.put("testPaidCount", testCount);
        out.put("testAmountVnd", testAmount);
        out.put("revenueThisMonthVnd", thisMonth);
        out.put("revenuePrevMonthVnd", prevMonth);
        out.put("monthDeltaPct", deltaPct(thisMonth, prevMonth));

        List<Map<String, Object>> providers = new ArrayList<>();
        byProvider.forEach((code, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", code);
            row.put("label", PROVIDER_LABELS.getOrDefault(code, code));
            row.put("count", v[0]);
            row.put("amountVnd", v[1]);
            providers.add(row);
        });
        out.put("byProvider", providers);

        List<Map<String, Object>> dailyRows = new ArrayList<>();
        daily.forEach((d, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("count", v[0]);
            row.put("amountVnd", v[1]);
            dailyRows.add(row);
        });
        out.put("daily", dailyRows);

        List<Map<String, Object>> monthlyRows = new ArrayList<>();
        monthly.forEach((m, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m.toString());
            row.put("count", v[0]);
            row.put("amountVnd", v[1]);
            monthlyRows.add(row);
        });
        out.put("monthly", monthlyRows);

        Map<String, Object> np = new LinkedHashMap<>();
        np.put("jobAcceptanceFeesNp", jobFees);
        np.put("portfolioExportFeesNp", exportFees);
        np.put("premiumNp", premium);
        np.put("otherSpendNp", otherSpend);
        np.put("totalFeesNp", jobFees + exportFees + premium + otherSpend);
        np.put("rewardsIssuedNp", rewards);
        np.put("adjustmentsNp", adjustments);
        np.put("outstandingNp", outstandingNp);
        out.put("np", np);
        return out;
    }
}
