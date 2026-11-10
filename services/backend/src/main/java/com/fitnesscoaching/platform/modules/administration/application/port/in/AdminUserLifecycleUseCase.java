package com.fitnesscoaching.platform.modules.administration.application.port.in;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;

import java.util.UUID;

public interface AdminUserLifecycleUseCase {

    AdminUserDetail suspendUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId);

    AdminUserDetail restoreUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId);

    AdminUserDetail disableUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId);

    int forceLogoutUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId);

    AdminUserDetail changeUserRole(UUID adminUserId, UUID targetUserId, String roleCode, String action, String reason, String stepUpPassword, UUID stepUpAuthId);
}
