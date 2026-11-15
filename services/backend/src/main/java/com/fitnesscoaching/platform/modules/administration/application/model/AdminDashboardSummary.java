package com.fitnesscoaching.platform.modules.administration.application.model;

public record AdminDashboardSummary(
        long pendingTrainerApplications,
        long totalUsers,
        long activeUsers,
        long suspendedUsers,
        long disabledUsers,
        long pendingVerificationUsers
) {
}
