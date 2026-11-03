package com.fitnesscoaching.platform.modules.auth.application.port.in;

import java.util.UUID;

/** Auth-owned, fenced by the caller's erasure request transaction. */
public interface AccountErasureCredentialMinimizer {
    void minimizeAfterCutoff(UUID userId);
}
