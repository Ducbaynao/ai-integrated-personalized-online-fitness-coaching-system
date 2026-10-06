package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingSharingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Receipt;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CoachingSharingService implements CoachingSharingUseCase {
    private final CoachingSharingStore store;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final StudentCapabilityQuery students;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public CoachingSharingService(CoachingSharingStore store, UserAccountStatusQuery accounts,
                                  UserRoleQuery roles, StudentCapabilityQuery students,
                                  AuditService audit, ObjectMapper mapper) {
        this.store = store;
        this.accounts = accounts;
        this.roles = roles;
        this.students = students;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> list(UUID actorId, UUID relationshipId, int page, int size) {
        Relationship relationship = visibleParticipant(actorId, relationshipId);
        if (relationship.trainerId().equals(actorId) && relationship.status().equals("ENDED")) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND");
        }
        return store.permissions(relationshipId, size, page * size);
    }

    @Override
    @Transactional
    public Permission grantOrReplace(UUID actorId, UUID relationshipId, DataScope dataScope,
                                     SharingDecision decision, DataAccessLevel accessLevel,
                                     Instant historyFrom, Instant historyUntil, Instant validUntil,
                                     Long expectedPermissionVersion, UUID commandKey) {
        requireCommandKey(commandKey);
        validateGrant(dataScope, decision, accessLevel, historyFrom, historyUntil);
        Relationship relationship = studentOwned(actorId, relationshipId);
        store.lockStudent(relationship.studentId());
        relationship = studentOwned(actorId, relationshipId);
        Relationship current = relationship;
        String payload = relationshipId + ":" + dataScope + ":" + decision + ":" + accessLevel + ":"
                + value(historyFrom) + ":" + value(historyUntil) + ":" + value(validUntil) + ":"
                + expectedPermissionVersion;
        return replay(actorId, commandKey, "SHARING_GRANT_OR_REPLACE", payload, () -> {
            if (!current.status().equals("ACTIVE") && !current.status().equals("PAUSED")) {
                throw failure(HttpStatus.CONFLICT, "COACHING_RELATIONSHIP_STATE_CONFLICT");
            }
            Instant at = store.transitionTime();
            if (validUntil != null && !validUntil.isAfter(at)) {
                throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            }
            Permission previous = store.effectivePermission(current.id(), dataScope, at).orElse(null);
            if (previous == null && expectedPermissionVersion != null) {
                throw failure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
            }
            if (previous != null) {
                if (expectedPermissionVersion == null || previous.version() != expectedPermissionVersion) {
                    throw failure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
                }
                if (sameDecision(previous, decision, accessLevel, historyFrom, historyUntil, validUntil)) {
                    throw failure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
                }
                store.revokePermission(previous, actorId, at, "REPLACED");
            }
            Permission created = store.createPermission(current, dataScope, decision, accessLevel,
                    historyFrom, historyUntil, at, validUntil, actorId,
                    previous == null ? 0 : previous.version() + 1);
            auditPermission(actorId, created, previous == null ? "GRANT" : "REPLACE",
                    previous == null ? null : previous.decision().name(), created.decision().name(), at);
            return created;
        });
    }

    @Override
    @Transactional
    public Permission revoke(UUID actorId, UUID relationshipId, UUID permissionId,
                             long expectedPermissionVersion, String reason, UUID commandKey) {
        requireCommandKey(commandKey);
        if (permissionId == null || expectedPermissionVersion < 0 || (reason != null && reason.length() > 500)) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        Relationship relationship = studentOwned(actorId, relationshipId);
        store.lockStudent(relationship.studentId());
        studentOwned(actorId, relationshipId);
        String payload = relationshipId + ":" + permissionId + ":" + expectedPermissionVersion + ":"
                + normalized(reason);
        return replay(actorId, commandKey, "SHARING_REVOKE", payload, () -> {
            Permission permission = store.permission(permissionId)
                    .filter(value -> value.relationshipId().equals(relationshipId)
                            && value.studentId().equals(actorId))
                    .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "DATA_SHARING_PERMISSION_NOT_FOUND"));
            if (permission.version() != expectedPermissionVersion || permission.revokedAt() != null) {
                throw failure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
            }
            Instant at = store.transitionTime();
            if (permission.validUntil() != null && !permission.validUntil().isAfter(at)) {
                throw failure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
            }
            Permission revoked = store.revokePermission(permission, actorId, at, normalized(reason));
            auditPermission(actorId, revoked, "REVOKE", permission.decision().name(), "REVOKED", at);
            return revoked;
        });
    }

    private Relationship visibleParticipant(UUID actorId, UUID relationshipId) {
        verifyAccount(actorId);
        Relationship relationship = store.relationship(relationshipId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND"));
        if (!relationship.participant(actorId)) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND");
        }
        if (relationship.studentId().equals(actorId)) {
            verifyStudent(actorId);
        } else if (!roles.hasActiveRole(actorId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability unavailable");
        }
        return relationship;
    }

    private Relationship studentOwned(UUID actorId, UUID relationshipId) {
        Relationship relationship = visibleParticipant(actorId, relationshipId);
        if (!relationship.studentId().equals(actorId)) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND");
        }
        return relationship;
    }

    private void verifyAccount(UUID actorId) {
        if (actorId == null || accounts.getAccountStatus(actorId).orElse(null) != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Account unavailable");
        }
    }

    private void verifyStudent(UUID actorId) {
        if (!roles.hasActiveRole(actorId, "STUDENT") || !students.hasProfile(actorId)) {
            throw new StudentCapabilityUnavailableException("Student capability unavailable");
        }
    }

    private static void validateGrant(DataScope scope, SharingDecision decision, DataAccessLevel level,
                                      Instant historyFrom, Instant historyUntil) {
        if (scope == null || decision == null || level == null
                || (historyUntil != null && historyFrom == null)
                || (historyFrom != null && historyUntil != null && !historyUntil.isAfter(historyFrom))) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
    }

    private static boolean sameDecision(Permission previous, SharingDecision decision, DataAccessLevel level,
                                        Instant historyFrom, Instant historyUntil, Instant validUntil) {
        return previous.decision() == decision && previous.accessLevel() == level
                && Objects.equals(previous.historyFrom(), historyFrom)
                && Objects.equals(previous.historyUntil(), historyUntil)
                && Objects.equals(previous.validUntil(), validUntil);
    }

    private Permission replay(UUID actorId, UUID commandKey, String commandName,
                              String payload, Supplier<Permission> work) {
        String payloadHash = hash(commandName + ":" + payload);
        Receipt prior = store.receipt(actorId, commandKey).orElse(null);
        if (prior != null) {
            if (!prior.commandName().equals(commandName) || !prior.payloadHash().equals(payloadHash)) {
                throw failure(HttpStatus.CONFLICT, "COACHING_IDEMPOTENCY_CONFLICT");
            }
            return mapper.readValue(prior.responseJson(), Permission.class);
        }
        Permission permission = work.get();
        store.saveReceipt(actorId, commandKey, commandName, payloadHash, mapper.writeValueAsString(permission));
        return permission;
    }

    private void auditPermission(UUID actorId, Permission permission, String action,
                                 String before, String after, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actorId).actorRole("STUDENT")
                .action("COACHING_SHARING_" + action).targetType("DATA_SHARING_PERMISSION")
                .targetId(permission.id())
                .beforeDataJson("{\"decision\":\"" + (before == null ? "NONE" : before) + "\"}")
                .afterDataJson("{\"decision\":\"" + after + "\"}")
                .metadataJson("{\"relationshipId\":\"" + permission.relationshipId()
                        + "\",\"scope\":\"" + permission.dataScope() + "\"}")
                .occurredAt(at).build());
    }

    private static void requireCommandKey(UUID commandKey) {
        if (commandKey == null) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String value(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static CoachingFailure failure(HttpStatus status, String code) {
        return new CoachingFailure(status, code);
    }
}
