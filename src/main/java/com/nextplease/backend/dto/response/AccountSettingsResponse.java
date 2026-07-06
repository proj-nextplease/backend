package com.nextplease.backend.dto.response;

public record AccountSettingsResponse(
        String displayName,
        String email,
        String status,
        boolean emailNotificationsEnabled,
        boolean pushNotificationsEnabled,
        boolean inAppNotificationsEnabled
) {
}
