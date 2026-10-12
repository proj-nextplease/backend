package com.nextplease.backend.dto.request;

/**
 * Đăng ký danh sách chờ Early Access.
 *
 * Chỉ `fullName` và `email` là bắt buộc. Năm trường còn lại là khảo sát —
 * bắt buộc chúng chỉ làm người ta bỏ dở giữa chừng, mà thứ nhóm thật sự cần
 * là cái email.
 */
public record WaitlistRequest(
        String fullName,
        String email,
        String careerStage,
        String specialization,
        String projectCount,
        String mainChallenge,
        String betaInterest,
        Boolean emailConsent,
        String source,
        /**
         * Bẫy bot: ô ẩn mà người thật không bao giờ thấy nên không bao giờ
         * điền. Có chữ trong đây thì coi như bot — xem WaitlistService.
         */
        String botField
) {}
