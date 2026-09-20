package com.fitnesscoaching.platform.modules.user.application.port.in;

import java.util.UUID;

public interface UserRoleQuery {

    boolean hasActiveRole(UUID userId, String roleCode);
}
