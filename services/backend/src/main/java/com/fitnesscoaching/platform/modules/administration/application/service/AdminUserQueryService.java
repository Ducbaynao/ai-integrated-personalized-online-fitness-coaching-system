package com.fitnesscoaching.platform.modules.administration.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.TargetUserNotFoundException;
import com.fitnesscoaching.platform.modules.administration.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserPage;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserSummary;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminUserQueryUseCase;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminUserQueryService implements AdminUserQueryUseCase {

    private final AdminUserPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;

    public AdminUserQueryService(
            AdminUserPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
    }

    @Override
    public AdminUserPage searchUsers(UUID adminUserId, String search, String status, String role, int page, int size) {
        verifyActiveAdmin(adminUserId);

        if (page < 0) {
            throw new ApplicationValidationException("Page index cannot be negative",
                    List.of(new FieldErrorDto("page", "Min", "Page index cannot be negative")));
        }
        if (size < 1 || size > 100) {
            throw new ApplicationValidationException("Page size must be between 1 and 100",
                    List.of(new FieldErrorDto("size", "Range", "Page size must be between 1 and 100")));
        }

        if (status != null && !status.isBlank()) {
            try {
                AccountStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApplicationValidationException("Invalid status parameter: " + status,
                        List.of(new FieldErrorDto("status", "Invalid", "Status must be a valid account status")));
            }
        }

        long offset;
        try {
            offset = Math.multiplyExact((long) page, (long) size);
        } catch (ArithmeticException e) {
            throw new ApplicationValidationException("Page index produces an offset that exceeds supported limits",
                    List.of(new FieldErrorDto("page", "Max", "Page index produces an offset that exceeds supported limits")));
        }

        List<AdminUserSummary> content = persistence.searchUsers(search, status, role, size, offset);
        long totalElements = persistence.countUsers(search, status, role);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);

        return new AdminUserPage(content, page, size, totalElements, totalPages);
    }

    @Override
    public AdminUserDetail getUserDetail(UUID adminUserId, UUID targetUserId) {
        verifyActiveAdmin(adminUserId);

        if (targetUserId == null) {
            throw new ApplicationValidationException("Target user ID cannot be null");
        }

        return persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));
    }

    private void verifyActiveAdmin(UUID adminUserId) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Admin user ID is required");
        }
        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User does not exist"));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Administrator account is not active");
        }

        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("User does not possess an active ADMIN role");
        }
    }
}
