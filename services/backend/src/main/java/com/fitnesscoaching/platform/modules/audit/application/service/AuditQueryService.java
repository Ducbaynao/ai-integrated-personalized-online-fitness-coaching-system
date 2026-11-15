package com.fitnesscoaching.platform.modules.audit.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.modules.audit.adapter.out.persistence.AuditQueryPersistenceAdapter;
import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogEntry;
import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogPage;
import com.fitnesscoaching.platform.modules.audit.application.port.in.AuditQueryUseCase;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuditQueryService implements AuditQueryUseCase {

    private final AuditQueryPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final ObjectMapper objectMapper;

    public AuditQueryService(
            AuditQueryPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            ObjectMapper objectMapper
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.objectMapper = objectMapper;
    }

    @Override
    public AuditLogPage searchAuditLogs(UUID adminUserId, UUID actorUserId, String action, String targetType,
                                         UUID targetId, Instant from, Instant to, int page, int size) {
        verifyAdminAccess(adminUserId, "AUDIT_VIEW");
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        long offset = (long) boundedPage * boundedSize;

        List<AuditLogEntry> rawEntries = persistence.searchAuditLogs(actorUserId, action, targetType, targetId, from, to, boundedSize, offset);
        List<AuditLogEntry> sanitizedEntries = rawEntries.stream().map(this::sanitizeEntry).toList();
        long total = persistence.countAuditLogs(actorUserId, action, targetType, targetId, from, to);
        int totalPages = (int) Math.ceil((double) total / boundedSize);

        return new AuditLogPage(sanitizedEntries, total, totalPages, boundedPage, boundedSize);
    }

    @Override
    public AuditLogEntry getAuditLogEntry(UUID adminUserId, long id) {
        verifyAdminAccess(adminUserId, "AUDIT_VIEW");
        AuditLogEntry entry = persistence.findById(id)
                .orElseThrow(() -> new ApplicationValidationException("Audit log entry not found: " + id));
        return sanitizeEntry(entry);
    }

    private AuditLogEntry sanitizeEntry(AuditLogEntry entry) {
        if (entry == null) return null;
        String sanitizedBefore = redactSecrets(entry.beforeDataJson());
        String sanitizedAfter = redactSecrets(entry.afterDataJson());
        String sanitizedMeta = redactSecrets(entry.metadataJson());
        return new AuditLogEntry(
                entry.id(),
                entry.actorUserId(),
                entry.actorUserName(),
                entry.actorRole(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                entry.requestId(),
                entry.ipAddress(),
                entry.userAgent(),
                sanitizedBefore,
                sanitizedAfter,
                sanitizedMeta,
                entry.occurredAt()
        );
    }

    private String redactSecrets(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            Object redacted = redactObject(parsed);
            return objectMapper.writeValueAsString(redacted);
        } catch (Exception e) {
            return json
                    .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"[REDACTED]\"")
                    .replaceAll("(?i)\"passwordHash\"\\s*:\\s*\"[^\"]*\"", "\"passwordHash\":\"[REDACTED]\"")
                    .replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"[REDACTED]\"")
                    .replaceAll("(?i)\"secret\"\\s*:\\s*\"[^\"]*\"", "\"secret\":\"[REDACTED]\"");
        }
    }

    private Object redactObject(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    copy.put(key, redactObject(value));
                } else if (isSensitiveField(key)) {
                    copy.put(key, "[REDACTED]");
                } else {
                    copy.put(key, value);
                }
            }
            return copy;
        } else if (obj instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(redactObject(item));
            }
            return copy;
        }
        return obj;
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) return false;
        String lower = fieldName.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("apikey") || lower.contains("privatekey");
    }

    private void verifyAdminAccess(UUID adminUserId, String permissionCode) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Authentication required");
        }
        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccessDeniedException("Account is not active");
        }
        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("Admin role required");
        }
        List<String> permissions = userPermissionQuery.getEffectivePermissions(adminUserId);
        if (!permissions.contains(permissionCode)) {
            throw new AccessDeniedException("Missing permission: " + permissionCode);
        }
    }
}
