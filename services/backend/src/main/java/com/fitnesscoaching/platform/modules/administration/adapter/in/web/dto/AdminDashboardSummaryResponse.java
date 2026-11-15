package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminDashboardSummary;

public record AdminDashboardSummaryResponse(
        long pendingTrainerApplications,
        long totalUsers,
        long activeUsers,
        long suspendedUsers,
        long disabledUsers,
        long pendingVerificationUsers
) {
    public static AdminDashboardSummaryResponse fromDomain(AdminDashboardSummary summary) {
        return new AdminDashboardSummaryResponse(
                summary.pendingTrainerApplications(),
                summary.totalUsers(),
                summary.activeUsers(),
                summary.suspendedUsers(),
                summary.disabledUsers(),
                summary.pendingVerificationUsers()
        );
    }
}
