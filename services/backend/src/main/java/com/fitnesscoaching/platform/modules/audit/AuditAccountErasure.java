package com.fitnesscoaching.platform.modules.audit;

import java.util.UUID;

/** Audit-owned evidenced, field-restricted correction; no raw row deletion. */
public interface AuditAccountErasure {
    void redactForAccount(UUID userId, UUID requestId);
}
