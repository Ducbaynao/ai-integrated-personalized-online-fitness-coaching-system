package com.fitnesscoaching.platform.modules.administration.application.port.in;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminDashboardSummary;

import java.util.UUID;

public interface AdminDashboardUseCase {

    AdminDashboardSummary getDashboardSummary(UUID adminUserId);
}
