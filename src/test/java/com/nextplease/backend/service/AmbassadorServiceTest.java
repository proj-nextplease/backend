package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phần tính phí của Đại sứ thương hiệu.
 *
 * Mọi chỗ trừ NP của ứng viên đều gọi {@link AmbassadorService#waiveFee}, nên
 * đây là chỗ đáng khoá bằng test nhất: sai một dòng ở đây là hoặc đại sứ bị
 * trừ tiền, hoặc ứng viên thường được dùng không mất phí.
 * Luồng đầy đủ (API, DB, email) được kiểm ở scripts/ambassador-e2e.
 */
class AmbassadorServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AmbassadorService service = new AmbassadorService(jdbc, null, null, "http://localhost");
    private final UUID userId = UUID.randomUUID();

    private void ambassador(boolean active) {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenReturn(active);
    }

    @Test
    @DisplayName("Đại sứ: mọi khoản phí thành 0")
    void daiSuMienPhi() {
        ambassador(true);
        assertEquals(0, service.waiveFee(userId, 19_000));
        assertEquals(0, service.waiveFee(userId, 1_000));
    }

    @Test
    @DisplayName("Ứng viên thường: phí giữ nguyên")
    void thuongTinhPhi() {
        ambassador(false);
        assertEquals(19_000, service.waiveFee(userId, 19_000));
    }

    @Test
    @DisplayName("Phí 0 sẵn (lần xuất website đầu) không cần hỏi DB")
    void phiKhongKhongHoiDb() {
        assertEquals(0, service.waiveFee(userId, 0));
        verify(jdbc, never()).queryForObject(anyString(), anyMap(), any(Class.class));
    }

    @Test
    @DisplayName("Không có người dùng thì không phải đại sứ")
    void khongCoNguoiDung() {
        assertEquals(false, service.isActive(null));
    }

    @Test
    @DisplayName("Lý do giao dịch ghi rõ miễn phí chỉ khi phí thật sự được miễn")
    void lyDo() {
        assertEquals(AmbassadorService.WAIVED_REASON_PREFIX + "Phí nhận việc",
                AmbassadorService.reason("Phí nhận việc", 19_000, 0));
        assertEquals("Phí nhận việc", AmbassadorService.reason("Phí nhận việc", 19_000, 19_000));
        // Lần xuất website đầu vốn miễn phí cho mọi người: không gắn nhãn đại sứ.
        assertEquals("Xuất website", AmbassadorService.reason("Xuất website", 0, 0));
    }
}
