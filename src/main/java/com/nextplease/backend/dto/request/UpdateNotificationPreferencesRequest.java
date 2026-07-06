package com.nextplease.backend.dto.request;

public record UpdateNotificationPreferencesRequest(
        boolean emailEnabled,
        boolean pushEnabled,
        boolean inAppEnabled
) {
}
