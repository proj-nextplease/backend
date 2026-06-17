package com.nextplease.backend.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String errorCode,
        OffsetDateTime timestamp
) {
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null, OffsetDateTime.now(VIETNAM_ZONE));
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null, OffsetDateTime.now(VIETNAM_ZONE));
    }

    public static <T> ApiResponse<T> errorWithCode(String message, String errorCode) {
        return new ApiResponse<>(false, null, message, errorCode, OffsetDateTime.now(VIETNAM_ZONE));
    }
}
