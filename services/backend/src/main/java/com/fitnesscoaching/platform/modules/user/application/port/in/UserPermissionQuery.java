package com.fitnesscoaching.platform.modules.user.application.port.in;

import java.util.UUID;

public interface UserPermissionQuery {

    boolean hasActivePermission(UUID userId, String permissionCode);
}
