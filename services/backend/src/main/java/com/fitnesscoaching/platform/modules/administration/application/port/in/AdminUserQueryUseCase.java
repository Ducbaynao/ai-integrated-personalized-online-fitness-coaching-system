package com.fitnesscoaching.platform.modules.administration.application.port.in;

import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserPage;

import java.util.UUID;

public interface AdminUserQueryUseCase {

    AdminUserPage searchUsers(UUID adminUserId, String search, String status, String role, int page, int size);

    AdminUserDetail getUserDetail(UUID adminUserId, UUID targetUserId);
}
