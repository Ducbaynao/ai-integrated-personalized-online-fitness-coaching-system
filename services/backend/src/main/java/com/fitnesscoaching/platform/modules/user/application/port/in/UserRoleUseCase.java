package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;

import java.util.UUID;

public interface UserRoleUseCase {

    RoleActivationResult activateRole(UUID userId, String roleCode, UUID assignedBy);
}
