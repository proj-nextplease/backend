package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nextplease.backend.service.FinanceSummary.Payment;
import com.nextplease.backend.service.FinanceSummary.WalletTxn;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Số liệu tài chính của trang admin.
 *
 * Con số "tiền thật đã nhận" sẽ được đọc như doanh thu, nên phải loại đúng
 * tiền nạp thử (MOCK) và đơn chưa trả, và phải xếp tiền vào đúng ngày theo giờ VN.
 */
class FinanceSummaryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    private static Instant vn(int y, int m, int d, int h) {
        return LocalDateTime.of(y, m, d, h, 0).atZone(FinanceSummary.VN).toInstant();
    }

    private static Payment paid(String user, String provider, long amount, Instant at) {
        return new Payment(user, provider, "PAID", amount, at, at);
    }

    private static Payment status(String status, long amount) {
        Instant at = vn(2026, 11, 20, 10);
        return new Payment("u-x", "PAYOS", status, amount, at, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) {
        return (List<Map<String, Object>>) out.get(key);
    }

    @Test
    @DisplayName("Tiền thật chỉ gồm đơn PAID qua cổng thật; MOCK tách riêng")
    void tienThatLoaiMock() {
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, vn(2026, 11, 27, 9)),
                paid("u2", "GOOGLE_PLAY", 69_000, vn(2026, 11, 26, 9)),
                paid("u3", "MOCK", 50_000, vn(2026, 11, 25, 9)),
                status("PENDING", 99_000)
        ), List.of(), 0, TODAY);

        assertEquals(108_000L, out.get("realRevenueVnd"));
        assertEquals(2, out.get("paidCount"));
        assertEquals(1, out.get("mockCount"));
        assertEquals(50_000L, out.get("mockAmountVnd"));
        assertEquals(1, out.get("pendingCount"));
        assertEquals(99_000L, out.get("pendingAmountVnd"));
    }

    @Test
    @DisplayName("Tỉ lệ thành công tính trên lượt tạo mã thật, không tính MOCK")
    void tiLeThanhCong() {
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, vn(2026, 11, 27, 9)),
                status("CANCELLED", 39_000),
                status("EXPIRED", 39_000),
                status("FAILED", 39_000),
                paid("u9", "MOCK", 50_000, vn(2026, 11, 27, 9))
        ), List.of(), 0, TODAY);

        assertEquals(4, out.get("requestCount"));
        assertEquals(25.0, out.get("successRatePct"));
        assertEquals(1, out.get("cancelledCount"));
        assertEquals(1, out.get("expiredCount"));
        assertEquals(1, out.get("failedCount"));
    }

    @Test
    @DisplayName("Một người nạp hai lần vẫn là một người nạp; trung bình mỗi lần đúng")
    void nguoiNapKhacNhau() {
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, vn(2026, 11, 27, 9)),
                paid("u1", "PAYOS", 99_000, vn(2026, 11, 28, 9))
        ), List.of(), 0, TODAY);

        assertEquals(1, out.get("payingUsers"));
        assertEquals(69_000L, out.get("avgTopupVnd"));
    }

    @Test
    @DisplayName("Không có giao dịch: mọi số bằng 0, không chia cho 0")
    void rong() {
        Map<String, Object> out = FinanceSummary.build(List.of(), List.of(), 0, TODAY);
        assertEquals(0L, out.get("realRevenueVnd"));
        assertEquals(0.0, out.get("successRatePct"));
        assertEquals(0L, out.get("avgTopupVnd"));
        assertNull(out.get("monthDeltaPct"));
        assertEquals(30, list(out, "daily").size());
        assertEquals(6, list(out, "monthly").size());
    }

    @Test
    @DisplayName("Tiền về lúc 0h30 giờ VN ngày 01/11 thuộc tháng 11, không phải tháng 10")
    void ngayTheoGioVietNam() {
        Instant earlyNov = vn(2026, 11, 1, 0).plusSeconds(30 * 60);
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, earlyNov),
                paid("u2", "PAYOS", 20_000, vn(2026, 10, 15, 12))
        ), List.of(), 0, TODAY);

        assertEquals(39_000L, out.get("revenueThisMonthVnd"));
        assertEquals(20_000L, out.get("revenuePrevMonthVnd"));
        assertEquals(95, out.get("monthDeltaPct"));
        List<Map<String, Object>> monthly = list(out, "monthly");
        assertEquals("2026-11", monthly.get(5).get("month"));
        assertEquals(39_000L, monthly.get(5).get("amountVnd"));
        assertEquals("2026-10", monthly.get(4).get("month"));
    }

    @Test
    @DisplayName("Biểu đồ ngày phủ đúng 30 ngày gần nhất, tiền cũ hơn không lọt vào")
    void bieuDoNgay() {
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, vn(2026, 11, 28, 8)),
                paid("u2", "PAYOS", 69_000, vn(2026, 10, 1, 8))
        ), List.of(), 0, TODAY);

        List<Map<String, Object>> daily = list(out, "daily");
        assertEquals("2026-10-30", daily.get(0).get("date"));
        assertEquals("2026-11-28", daily.get(29).get("date"));
        long sum = daily.stream().mapToLong(d -> (Long) d.get("amountVnd")).sum();
        assertEquals(39_000L, sum);
    }

    @Test
    @DisplayName("Theo cổng thanh toán: luôn có PayOS, Google Play, VietQR")
    void theoCong() {
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("u1", "PAYOS", 39_000, vn(2026, 11, 27, 9))
        ), List.of(), 0, TODAY);

        List<Map<String, Object>> providers = list(out, "byProvider");
        assertEquals(3, providers.size());
        assertEquals("PAYOS", providers.get(0).get("provider"));
        assertEquals(39_000L, providers.get(0).get("amountVnd"));
        assertEquals(0L, providers.get(1).get("amountVnd"));
    }

    @Test
    @DisplayName("Phí NP đã thu cộng theo trị tuyệt đối, thưởng và điều chỉnh tách riêng")
    @SuppressWarnings("unchecked")
    void phiNp() {
        Instant at = vn(2026, 11, 20, 9);
        Map<String, Object> out = FinanceSummary.build(List.of(), List.of(
                new WalletTxn("u1", "JOB_ACCEPTANCE_FEE", -19_000, at),
                new WalletTxn("u1", "JOB_ACCEPTANCE_FEE", -19_000, at),
                new WalletTxn("u1", "PORTFOLIO_EXPORT_FEE", -1_000, at),
                new WalletTxn("u1", "PREMIUM_PURCHASE", -40_000, at),
                new WalletTxn("u1", "REWARD", 1_000, at),
                new WalletTxn("u1", "ADJUSTMENT", -21_000, at)
        ), 150_000, TODAY);

        Map<String, Object> np = (Map<String, Object>) out.get("np");
        assertEquals(38_000L, np.get("jobAcceptanceFeesNp"));
        assertEquals(1_000L, np.get("portfolioExportFeesNp"));
        assertEquals(40_000L, np.get("premiumNp"));
        assertEquals(79_000L, np.get("totalFeesNp"));
        assertEquals(1_000L, np.get("rewardsIssuedNp"));
        assertEquals(-21_000L, np.get("adjustmentsNp"));
        assertEquals(150_000L, np.get("outstandingNp"));
    }

    @Test
    @DisplayName("Tài khoản test: tiền nạp và phí NP của họ không tính vào số liệu")
    @SuppressWarnings("unchecked")
    void taiKhoanTest() {
        Instant at = vn(2026, 11, 20, 9);
        Map<String, Object> out = FinanceSummary.build(List.of(
                paid("team", "PAYOS", 20_000, vn(2026, 9, 29, 10)),
                paid("team", "PAYOS", 39_000, vn(2026, 10, 27, 10)),
                new Payment("team", "PAYOS", "CANCELLED", 39_000, at, null),
                paid("real", "PAYOS", 39_000, vn(2026, 11, 27, 10))
        ), List.of(
                new WalletTxn("team", "JOB_ACCEPTANCE_FEE", -19_000, at),
                new WalletTxn("real", "PORTFOLIO_EXPORT_FEE", -1_000, at)
        ), 0, TODAY, Set.of("team"));

        assertEquals(39_000L, out.get("realRevenueVnd"));
        assertEquals(1, out.get("paidCount"));
        assertEquals(1, out.get("payingUsers"));
        assertEquals(1, out.get("requestCount"), "lượt huỷ của tài khoản test cũng không tính");
        assertEquals(100.0, out.get("successRatePct"));
        assertEquals(2, out.get("testPaidCount"));
        assertEquals(59_000L, out.get("testAmountVnd"));
        assertEquals(1, out.get("testAccountCount"));
        Map<String, Object> np = (Map<String, Object>) out.get("np");
        assertEquals(0L, np.get("jobAcceptanceFeesNp"));
        assertEquals(1_000L, np.get("totalFeesNp"));
    }

    @Test
    @DisplayName("Không có tài khoản test thì kết quả giống bản cũ")
    void khongCoTaiKhoanTest() {
        List<Payment> ps = List.of(paid("u1", "PAYOS", 39_000, vn(2026, 11, 27, 9)));
        assertEquals(FinanceSummary.build(ps, List.of(), 0, TODAY).get("realRevenueVnd"),
                FinanceSummary.build(ps, List.of(), 0, TODAY, null).get("realRevenueVnd"));
    }
}
