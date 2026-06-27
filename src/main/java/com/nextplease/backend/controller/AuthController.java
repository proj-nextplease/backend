package com.nextplease.backend.controller;

import com.nextplease.backend.dto.request.LoginRequest;
import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.LoginResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.service.AuthService;
import com.nextplease.backend.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        MeResponse currentUser = currentUserService.getCurrentUser();
        String accessToken = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }
        authService.logout(currentUser.appUserId(), accessToken);
        return ApiResponse.success(null);
    }
}
