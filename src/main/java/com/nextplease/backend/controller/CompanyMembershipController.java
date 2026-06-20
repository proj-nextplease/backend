package com.nextplease.backend.controller;

import com.nextplease.backend.dto.response.ApiResponse;
import com.nextplease.backend.dto.response.LoginResponse;
import com.nextplease.backend.dto.response.MeResponse;
import com.nextplease.backend.service.CompanyMembershipService;
import com.nextplease.backend.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service membership & delegation endpoints for company/club owners and managers.
 */
@RestController
@RequestMapping("/api/v1/company")
public class CompanyMembershipController {

    private final CompanyMembershipService membershipService;
    private final CurrentUserService currentUserService;

    public CompanyMembershipController(
            CompanyMembershipService membershipService,
            CurrentUserService currentUserService
    ) {
        this.membershipService = membershipService;
        this.currentUserService = currentUserService;
    }

    private UUID actorId() {
        MeResponse me = currentUserService.getCurrentUser();
        return me.appUserId();
    }

    @GetMapping("/members")
    public ApiResponse<List<Map<String, Object>>> listMembers() {
        return ApiResponse.success(membershipService.listMembers(actorId()));
    }

    @DeleteMapping("/members/{userId}")
    public ApiResponse<String> removeMember(@PathVariable UUID userId) {
        membershipService.removeMember(actorId(), userId);
        return ApiResponse.success("Đã gỡ thành viên khỏi tổ chức.");
    }

    @PatchMapping("/members/{userId}/role")
    public ApiResponse<String> changeMemberRole(@PathVariable UUID userId, @RequestBody Map<String, String> body) {
        membershipService.changeMemberRole(actorId(), userId, body.get("role"));
        return ApiResponse.success("Đã cập nhật vai trò thành viên.");
    }

    @PostMapping("/transfer-ownership")
    public ApiResponse<String> transferOwnership(@RequestBody Map<String, String> body) {
        UUID targetUserId = UUID.fromString(body.get("targetUserId"));
        membershipService.transferOwnership(actorId(), targetUserId);
        return ApiResponse.success("Đã chuyển quyền sở hữu tổ chức.");
    }

    @GetMapping("/invitations")
    public ApiResponse<List<Map<String, Object>>> listInvitations() {
        return ApiResponse.success(membershipService.listInvitations(actorId()));
    }

    @PostMapping("/invitations")
    public ApiResponse<Map<String, Object>> inviteMember(@RequestBody Map<String, String> body) {
        Map<String, Object> result = membershipService.inviteMember(actorId(), body.get("email"), body.get("role"));
        return ApiResponse.success(result);
    }

    @DeleteMapping("/invitations/{invitationId}")
    public ApiResponse<String> revokeInvitation(@PathVariable UUID invitationId) {
        membershipService.revokeInvitation(actorId(), invitationId);
        return ApiResponse.success("Đã thu hồi lời mời.");
    }

    @PostMapping("/invitations/{invitationId}/resend")
    public ApiResponse<Map<String, Object>> resendInvitation(@PathVariable UUID invitationId) {
        return ApiResponse.success(membershipService.resendInvitation(actorId(), invitationId));
    }

    @PostMapping("/leave")
    public ApiResponse<String> leaveCompany() {
        membershipService.leaveCompany(actorId());
        return ApiResponse.success("Bạn đã rời khỏi tổ chức.");
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<Map<String, Object>> acceptInvitation(@RequestBody Map<String, String> body) {
        Map<String, Object> result = membershipService.acceptInvitation(actorId(), body.get("token"));
        return ApiResponse.success(result);
    }

    // ── Public endpoints (no auth) for brand-new invitees ──

    @GetMapping("/invitations/preview")
    public ApiResponse<Map<String, Object>> previewInvitation(@RequestParam("token") String token) {
        return ApiResponse.success(membershipService.previewInvitation(token));
    }

    @PostMapping("/invitations/register")
    public ApiResponse<LoginResponse> registerInvitation(@RequestBody Map<String, String> body) {
        LoginResponse result = membershipService.registerAndAccept(body.get("token"), body.get("password"));
        return ApiResponse.success(result);
    }
}
