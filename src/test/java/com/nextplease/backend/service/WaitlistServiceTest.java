package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nextplease.backend.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Cổng của endpoint xuất danh sách chờ.
 *
 * Đây là thứ duy nhất đứng giữa Internet và một bảng chứa tên + email người
 * thật, nên nó đáng được kiểm bằng test chứ không chỉ bằng mắt. Không cần
 * database: `assertExportAllowed` chỉ so chuỗi.
 */
class WaitlistServiceTest {

    private static WaitlistService withToken(String token) {
        return new WaitlistService(null, token);
    }

    @Test
    @DisplayName("Chưa cấu hình token thì endpoint coi như không tồn tại")
    void chuaCauHinh() {
        AppException ex = assertThrows(AppException.class, () -> withToken("").assertExportAllowed("bat-ky"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Chưa cấu hình thì gửi token rỗng cũng KHÔNG qua")
    void rongKhongMoCua() {
        // Bẫy kinh điển: chuỗi rỗng so với chuỗi rỗng là bằng nhau, nên quên
        // chặn trường hợp chưa cấu hình là ai gửi header rỗng cũng vào được.
        assertThrows(AppException.class, () -> withToken("").assertExportAllowed(""));
        assertThrows(AppException.class, () -> withToken("").assertExportAllowed(null));
    }

    @Test
    @DisplayName("Sai token thì trả 404, không phải 403")
    void saiToken() {
        AppException ex = assertThrows(AppException.class,
                () -> withToken("dung-token").assertExportAllowed("sai-token"));
        // 403 là lời xác nhận "có endpoint ở đây"; 404 không dạy người dò điều gì.
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Tiền tố đúng vẫn bị từ chối")
    void tienToDung() {
        assertThrows(AppException.class, () -> withToken("abcdef123456").assertExportAllowed("abcdef"));
    }

    @Test
    @DisplayName("Thiếu hẳn header cũng bị từ chối")
    void thieuHeader() {
        assertThrows(AppException.class, () -> withToken("dung-token").assertExportAllowed(null));
    }

    @Test
    @DisplayName("Đúng token thì qua, kể cả khi header dính khoảng trắng thừa")
    void dungToken() {
        assertDoesNotThrow(() -> withToken("dung-token").assertExportAllowed("dung-token"));
        assertDoesNotThrow(() -> withToken("dung-token").assertExportAllowed("  dung-token  "));
    }
}
