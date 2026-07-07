package com.nextplease.backend.dto.request;

public record UpdatePrivacySettingsRequest(boolean isPublic, boolean openToWork) {
}
