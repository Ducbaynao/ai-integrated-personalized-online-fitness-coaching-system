package com.fitnesscoaching.platform.modules.administration.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.modules.administration.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminDashboardSummary;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminDashboardUseCase;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminDashboardService implements AdminDashboardUseCase {

    private final AdminUserPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;

    public AdminDashboardService(
            AdminUserPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
    }

    @Override
    public AdminDashboardSummary getDashboardSummary(UUID adminUserId) {
        verifyActiveAdmin(adminUserId);
        return persistence.getDashboardSummary();
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
