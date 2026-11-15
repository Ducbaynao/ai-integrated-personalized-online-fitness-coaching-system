package com.fitnesscoaching.platform.modules.audit.application.port.in;

import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogEntry;
import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogPage;

import java.time.Instant;
import java.util.UUID;

public interface AuditQueryUseCase {

    AuditLogPage searchAuditLogs(UUID adminUserId, UUID actorUserId, String action, String targetType,
                                 UUID targetId, Instant from, Instant to, int page, int size);

    AuditLogEntry getAuditLogEntry(UUID adminUserId, long id);
}
