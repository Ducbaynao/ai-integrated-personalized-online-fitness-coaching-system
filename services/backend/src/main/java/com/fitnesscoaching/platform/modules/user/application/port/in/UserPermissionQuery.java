package com.fitnesscoaching.platform.modules.user.application.port.in;

import java.util.List;
import java.util.UUID;

public interface UserPermissionQuery {

    List<String> getEffectivePermissions(UUID userId);

    default boolean hasActivePermission(UUID userId, String permissionCode) {
        return getEffectivePermissions(userId).contains(permissionCode);
    }
}
