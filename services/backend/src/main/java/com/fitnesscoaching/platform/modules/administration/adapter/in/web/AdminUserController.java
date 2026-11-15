package com.fitnesscoaching.platform.modules.administration.adapter.in.web;

import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.AdminUserActionRequest;
import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.AdminUserDetailResponse;
import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.AdminUserPageResponse;
import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.ChangeUserRoleRequest;
import com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto.ForceLogoutResponse;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserPage;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminUserLifecycleUseCase;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminUserQueryUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserQueryUseCase queryUseCase;
    private final AdminUserLifecycleUseCase lifecycleUseCase;

    public AdminUserController(
            AdminUserQueryUseCase queryUseCase,
            AdminUserLifecycleUseCase lifecycleUseCase
    ) {
        this.queryUseCase = queryUseCase;
        this.lifecycleUseCase = lifecycleUseCase;
    }

    @GetMapping
    public ResponseEntity<AdminUserPageResponse> listUsers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserPage resultPage = queryUseCase.searchUsers(adminUserId, search, status, role, page, size);
        return ResponseEntity.ok(AdminUserPageResponse.fromDomain(resultPage));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserDetailResponse> getUserDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserDetail detail = queryUseCase.getUserDetail(adminUserId, userId);
        return ResponseEntity.ok(AdminUserDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{userId}/suspend")
    public ResponseEntity<AdminUserDetailResponse> suspendUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserActionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserDetail detail = lifecycleUseCase.suspendUser(
                adminUserId,
                userId,
                request.reason(),
                request.stepUpPassword(),
                request.stepUpAuthenticationId()
        );
        return ResponseEntity.ok(AdminUserDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{userId}/restore")
    public ResponseEntity<AdminUserDetailResponse> restoreUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserActionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserDetail detail = lifecycleUseCase.restoreUser(
                adminUserId,
                userId,
                request.reason(),
                request.stepUpPassword(),
                request.stepUpAuthenticationId()
        );
        return ResponseEntity.ok(AdminUserDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{userId}/disable")
    public ResponseEntity<AdminUserDetailResponse> disableUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserActionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserDetail detail = lifecycleUseCase.disableUser(
                adminUserId,
                userId,
                request.reason(),
                request.stepUpPassword(),
                request.stepUpAuthenticationId()
        );
        return ResponseEntity.ok(AdminUserDetailResponse.fromDomain(detail));
    }

    @PostMapping("/{userId}/force-logout")
    public ResponseEntity<ForceLogoutResponse> forceLogoutUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserActionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        int revokedCount = lifecycleUseCase.forceLogoutUser(
                adminUserId,
                userId,
                request.reason(),
                request.stepUpPassword(),
                request.stepUpAuthenticationId()
        );
        return ResponseEntity.ok(new ForceLogoutResponse(
                revokedCount,
                "Toàn bộ phiên đăng nhập của người dùng đã bị thu hồi."
        ));
    }

    @PostMapping("/{userId}/roles")
    public ResponseEntity<AdminUserDetailResponse> changeUserRole(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeUserRoleRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        AdminUserDetail detail = lifecycleUseCase.changeUserRole(
                adminUserId,
                userId,
                request.roleCode(),
                request.action(),
                request.reason(),
                request.stepUpPassword(),
                request.stepUpAuthenticationId()
        );
        return ResponseEntity.ok(AdminUserDetailResponse.fromDomain(detail));
    }
}
