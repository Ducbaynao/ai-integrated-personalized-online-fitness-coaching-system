package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;

import java.util.UUID;

public interface GetCurrentUserUseCase {

    CurrentUserView getCurrentUser(UUID userId);
}
