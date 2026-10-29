package com.fitnesscoaching.platform.modules.auth.application.service;

import com.fitnesscoaching.platform.modules.auth.application.port.in.AccountErasureSessionCutoff;
import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthErasureCutoffService implements AccountErasureSessionCutoff {
    private final RefreshTokenPort tokens;

    public AuthErasureCutoffService(RefreshTokenPort tokens) { this.tokens = tokens; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeForErasure(UUID userId, Instant at) {
        tokens.revokeActiveForUser(userId, at, "ACCOUNT_ERASURE");
    }
}
