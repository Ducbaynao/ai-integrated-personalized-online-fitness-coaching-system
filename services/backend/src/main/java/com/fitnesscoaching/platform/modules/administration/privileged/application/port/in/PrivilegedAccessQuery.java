package com.fitnesscoaching.platform.modules.administration.privileged.application.port.in;

import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;

import java.util.Optional;
import java.util.UUID;

public interface PrivilegedAccessQuery {

    Optional<PrivilegedAccessModel> findById(UUID requestId);
}
