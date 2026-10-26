package com.nextplease.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailDeliveryServiceTest {

    @Test
    @DisplayName("Disabled or mock mode handles sendNotificationEmail gracefully without throwing")
    void testSendNotificationEmailGraceful() {
        EmailDeliveryService service = new EmailDeliveryService(
                false,
                "no-reply@fonlio.vn",
                "Fonlio",
                "",
                null,
                null,
                null
        );

        assertDoesNotThrow(() -> {
            service.sendNotificationEmail(
                    UUID.randomUUID(),
                    "candidate@example.com",
                    "Nguyễn Văn A",
                    "🎉 Đơn ứng tuyển đã được chấp nhận!",
                    "Nhà tuyển dụng đã chấp nhận đơn của bạn.",
                    "http://localhost:5173/candidates/dashboard/applications",
                    "APPLICATION_STATUS"
            );
        });
    }

    @Test
    @DisplayName("Blank recipient email is safely skipped")
    void testBlankRecipient() {
        EmailDeliveryService service = new EmailDeliveryService(
                true,
                "no-reply@fonlio.vn",
                "Fonlio",
                "",
                null,
                null,
                null
        );

        assertDoesNotThrow(() -> {
            service.sendNotificationEmail(null, "", "Test", "Title", "Body", "/link", "TEST");
        });
    }
}
