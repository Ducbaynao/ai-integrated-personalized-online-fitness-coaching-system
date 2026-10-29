package com.fitnesscoaching.platform.modules.auth.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Auth-owned durable revocation; joins the Account request transaction. */
public interface AccountErasureSessionCutoff {
    void revokeForErasure(UUID userId, Instant at);
}
