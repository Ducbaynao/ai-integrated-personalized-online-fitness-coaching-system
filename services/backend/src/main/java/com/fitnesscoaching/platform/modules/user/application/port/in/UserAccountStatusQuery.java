package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountStatusQuery {

    Optional<AccountStatus> getAccountStatus(UUID userId);
}
