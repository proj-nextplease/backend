package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.service.CurrentUserService;
import com.nextplease.backend.service.WalletService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;
    private final CurrentUserService currentUserService;

    public WalletController(WalletService walletService, CurrentUserService currentUserService) {
        this.walletService = walletService;
        this.currentUserService = currentUserService;
    }

    /** GET /api/v1/wallet — current balance, premium status, last 20 transactions */
    @GetMapping
    public ApiResponse<Map<String, Object>> getWallet() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        return ApiResponse.success(walletService.getWallet(userId));
    }

    /** POST /api/v1/wallet/topup — MOCK top-up: instant NP credit */
    @PostMapping("/topup")
    public ApiResponse<Map<String, Object>> topUp(@RequestBody TopUpRequest body) {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        Map<String, Object> result = walletService.topUp(userId, body.amountVnd());
        return ApiResponse.success(result);
    }

    /** POST /api/v1/wallet/subscribe — buy Premium Pass (40,000 NP / 30 days) */
    @PostMapping("/subscribe")
    public ApiResponse<Map<String, Object>> buyPremium() {
        UUID userId = currentUserService.getCurrentUser().appUserId();
        Map<String, Object> result = walletService.buyPremium(userId);
        return ApiResponse.success(result);
    }

    record TopUpRequest(int amountVnd) {}
}
