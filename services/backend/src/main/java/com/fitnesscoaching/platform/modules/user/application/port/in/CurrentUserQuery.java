package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;

import java.util.Optional;
import java.util.UUID;

public interface CurrentUserQuery {
    Optional<CurrentUserView> findCurrentUserById(UUID userId);
}
