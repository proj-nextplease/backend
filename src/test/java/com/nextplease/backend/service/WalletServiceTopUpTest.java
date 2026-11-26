package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nextplease.backend.exception.AppException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Mức nạp tối thiểu là 39.000đ — bằng gói nhỏ nhất (np_39k).
 *
 * Mức này đọc từ system_configs (khoá min_topup_vnd, migration V74 đặt 39000);
 * hằng số trong WalletService chỉ là dự phòng khi DB không có khoá. Test cả hai
 * đường để đổi một bên mà quên bên kia là đỏ ngay.
 */
class WalletServiceTopUpTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ConfigService config = mock(ConfigService.class);
    private final UUID userId = UUID.randomUUID();
    private WalletService service;

    @BeforeEach
    void setUp() {
        // Mặc định: DB không có khoá → ConfigService trả về giá trị dự phòng.
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        service = new WalletService(jdbc, config, null, true);
    }

    private void assertRejected(int amount, String messagePart) {
        AppException ex = assertThrows(AppException.class, () -> service.createTopUpRequest(userId, amount));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @ParameterizedTest(name = "{0}đ bị từ chối")
    @ValueSource(ints = {38_999, 30_000, 10_000, 1_000, 0, -39_000})
    @DisplayName("PayOS: dưới 39.000đ bị từ chối")
    void duoiMucToiThieu(int amount) {
        assertRejected(amount, "tối thiểu");
    }

    @Test
    @DisplayName("Thông báo lỗi ghi rõ mức 39.000")
    void thongBaoGhiMuc() {
        AppException ex = assertThrows(AppException.class, () -> service.createTopUpRequest(userId, 10_000));
        assertTrue(ex.getMessage().replaceAll("[^0-9]", "").contains("39000"), ex.getMessage());
    }

    @ParameterizedTest(name = "{0}đ được tạo đơn")
    @ValueSource(ints = {39_000, 39_001, 69_000, 199_000, 10_000_000})
    @DisplayName("PayOS: từ 39.000đ đến 10 triệu được tạo đơn")
    void trongKhoang(int amount) {
        long orderCode = service.createTopUpRequest(userId, amount);
        assertTrue(orderCode > 0);
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("Trên 10 triệu vẫn bị từ chối")
    void vuotMucToiDa() {
        assertRejected(10_000_001, "tối đa");
    }

    @Test
    @DisplayName("Giá trị trong DB (V74 = 39000) được dùng")
    void docTuDb() {
        when(config.getInt(eq("min_topup_vnd"), anyInt())).thenReturn(39_000);
        assertRejected(38_999, "tối thiểu");
        assertTrue(service.createTopUpRequest(userId, 39_000) > 0);
    }

    @Test
    @DisplayName("Nạp thử (mock) cũng chặn dưới 39.000đ")
    void napThuCungChan() {
        AppException ex = assertThrows(AppException.class, () -> service.topUp(userId, 38_999));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("tối thiểu"), ex.getMessage());
    }
}
