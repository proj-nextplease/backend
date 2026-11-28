package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nextplease.backend.exception.AppException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Danh sách tài khoản test của tab Tài chính. */
class AdminFinanceServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdminFinanceService service = new AdminFinanceService(jdbc);
    private final UUID admin = UUID.randomUUID();

    @Test
    @DisplayName("Đọc danh sách id: bỏ khoảng trắng, phần tử rỗng và giá trị hỏng, không trùng")
    void parseIds() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        Set<String> ids = AdminFinanceService.parseIds(" " + a + ",, khong-phai-uuid ," + b + "," + a);
        assertEquals(List.of(a, b), List.copyOf(ids));
        assertTrue(AdminFinanceService.parseIds(null).isEmpty());
        assertTrue(AdminFinanceService.parseIds("  ").isEmpty());
    }

    @Test
    @DisplayName("Thêm theo email không tồn tại: báo 404, không ghi gì")
    void emailKhongTonTai() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
        AppException ex = assertThrows(AppException.class,
                () -> service.addTestAccount("khongco@x.vn", null, admin));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("Không nhập email lẫn id: báo 400")
    void thieuThongTin() {
        AppException ex = assertThrows(AppException.class, () -> service.addTestAccount(" ", null, admin));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("Thêm tài khoản đã có trong danh sách: không ghi lại")
    void daCoTrongDanhSach() {
        String id = UUID.randomUUID().toString();
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenReturn(List.of(id))      // tìm theo email
                .thenReturn(List.of(id));     // danh sách đang lưu
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
        service.addTestAccount("team@x.vn", null, admin);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("Thêm tài khoản mới: lưu cấu hình và ghi nhật ký")
    void themMoi() {
        String id = UUID.randomUUID().toString();
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenReturn(List.of(id))      // tìm theo email
                .thenReturn(List.of());       // chưa có dòng cấu hình
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
        service.addTestAccount("team@x.vn", null, admin);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(SqlParameterSource.class));
    }
}
