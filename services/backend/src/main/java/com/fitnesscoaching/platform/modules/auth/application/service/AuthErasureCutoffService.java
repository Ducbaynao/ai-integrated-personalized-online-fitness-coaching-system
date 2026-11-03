package com.fitnesscoaching.platform.modules.auth.application.service;

import com.fitnesscoaching.platform.modules.auth.application.port.in.AccountErasureSessionCutoff;
import com.fitnesscoaching.platform.modules.auth.application.port.in.AccountErasureCredentialMinimizer;
import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthErasureCutoffService implements AccountErasureSessionCutoff, AccountErasureCredentialMinimizer {
    private final RefreshTokenPort tokens;
    private final JdbcTemplate jdbc;

    public AuthErasureCutoffService(RefreshTokenPort tokens, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeForErasure(UUID userId, Instant at) {
        tokens.revokeActiveForUser(userId, at, "ACCOUNT_ERASURE");
        // A previously verified step-up must not remain usable after the
        // account is disabled. Keep its chronology and FK-linked audit row.
        jdbc.update("""
                UPDATE fitness.step_up_authentications
                SET status='EXPIRED',expires_at=GREATEST(initiated_at + interval '1 microsecond', ?)
                WHERE user_id=? AND status IN ('PENDING','VERIFIED')
                """, java.sql.Timestamp.from(at), userId);
        jdbc.update("""
                UPDATE fitness.mfa_factors SET status='REVOKED',revoked_at=COALESCE(revoked_at,?)
                WHERE user_id=? AND status<>'REVOKED'
                """, java.sql.Timestamp.from(at), userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeAfterCutoff(UUID userId) {
        // These nullable, user-scoped labels and request metadata have no
        // counterpart data. The factor/action/time rows remain for review.
        jdbc.update("UPDATE fitness.mfa_factors SET label=NULL WHERE user_id=? AND label IS NOT NULL", userId);
        jdbc.update("""
                UPDATE fitness.step_up_authentications SET ip_address=NULL,user_agent=NULL
                WHERE user_id=? AND (ip_address IS NOT NULL OR user_agent IS NOT NULL)
                """, userId);
    }
}
