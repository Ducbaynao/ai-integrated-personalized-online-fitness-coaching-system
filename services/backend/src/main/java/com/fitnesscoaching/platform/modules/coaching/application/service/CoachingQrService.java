package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingQrUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingQrStore;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.*;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Receipt;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerPublicPreview;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerAvailabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerPublicPreviewQuery;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CoachingQrService implements CoachingQrUseCase {
    private static final int PAYLOAD_VERSION = 1;
    private static final int CODE_BYTES = 24;
    private static final String QR_URI_PREFIX = "ai-fitness-coaching://coaching/qr?v=1&c=";
    private static final String ROTATE_COMMAND = "QR_ROTATE";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CoachingQrStore qrStore;
    private final CoachingStore coachingStore;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final UserDirectoryQuery users;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final TrainerAvailabilityQuery availability;
    private final TrainerPublicPreviewQuery trainerPreviews;
    private final CoachingQrRateLimiter rateLimiter;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CoachingQrService(
            CoachingQrStore qrStore,
            CoachingStore coachingStore,
            UserAccountStatusQuery accounts,
            UserRoleQuery roles,
            UserDirectoryQuery users,
            StudentCapabilityQuery students,
            TrainerCoachingEligibilityQuery trainers,
            TrainerAvailabilityQuery availability,
            TrainerPublicPreviewQuery trainerPreviews,
            CoachingQrRateLimiter rateLimiter,
            AuditService audit,
            ObjectMapper mapper,
            Clock clock) {
        this.qrStore = qrStore;
        this.coachingStore = coachingStore;
        this.accounts = accounts;
        this.roles = roles;
        this.users = users;
        this.students = students;
        this.trainers = trainers;
        this.availability = availability;
        this.trainerPreviews = trainerPreviews;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public QrIdentity getMyQr(UUID actorId, QrRole role) {
        verifyOwnerCapability(actorId, role);
        QrCodeRecord record = qrStore.findActiveByOwnerAndRole(actorId, role)
                .orElseThrow(() -> new CoachingFailure(HttpStatus.NOT_FOUND, "COACHING_QR_NOT_FOUND"));
        return toIdentity(record, requireDisplayName(actorId));
    }

    @Override
    @Transactional
    public IssueResult issueMyQr(UUID actorId, QrRole role) {
        verifyOwnerCapability(actorId, role);
        Optional<QrCodeRecord> existing = qrStore.findActiveByOwnerAndRole(actorId, role);
        String displayName = requireDisplayName(actorId);
        if (existing.isPresent()) {
            return new IssueResult(toIdentity(existing.get(), displayName), false);
        }
        Instant now = now();
        String code = generatePublicCode();
        Optional<QrCodeRecord> inserted = qrStore.insertIfAbsentActive(actorId, role, code, PAYLOAD_VERSION, now);
        if (inserted.isPresent()) {
            QrCodeRecord record = inserted.get();
            auditQr(actorId, record, "COACHING_QR_ISSUED", null, record.version(), now);
            return new IssueResult(toIdentity(record, displayName), true);
        }
        QrCodeRecord concurrent = qrStore.findActiveByOwnerAndRole(actorId, role)
                .orElseThrow(() -> new IllegalStateException("Active QR record missing after concurrent conflict"));
        return new IssueResult(toIdentity(concurrent, displayName), false);
    }

    @Override
    @Transactional
    public QrIdentity rotateMyQr(UUID actorId, QrRole role, long expectedVersion, UUID commandKey) {
        if (commandKey == null || role == null || expectedVersion < 1) {
            throw new CoachingFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        verifyOwnerCapability(actorId, role);
        qrStore.lockOwner(actorId);

        String payloadHash = hash(ROTATE_COMMAND + ":" + role.name() + ":" + expectedVersion);
        Receipt prior = coachingStore.receipt(actorId, commandKey).orElse(null);
        String displayName = requireDisplayName(actorId);
        if (prior != null) {
            if (!ROTATE_COMMAND.equals(prior.commandName()) || !payloadHash.equals(prior.payloadHash())) {
                throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_IDEMPOTENCY_CONFLICT");
            }
            QrIdentity saved = mapper.readValue(prior.responseJson(), QrIdentity.class);
            QrCodeRecord currentRecord = qrStore.findById(saved.qrId()).orElse(null);
            if (currentRecord == null || !"ACTIVE".equals(currentRecord.status())) {
                throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_QR_VERSION_CONFLICT");
            }
            return toIdentity(currentRecord, displayName);
        }

        QrCodeRecord current = qrStore.findActiveByOwnerAndRole(actorId, role)
                .orElseThrow(() -> new CoachingFailure(HttpStatus.NOT_FOUND, "COACHING_QR_NOT_FOUND"));
        if (current.version() != expectedVersion) {
            throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_QR_VERSION_CONFLICT");
        }

        Instant now = now();
        if (!qrStore.revokeActive(current.id(), now, "ROTATED")) {
            throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_QR_VERSION_CONFLICT");
        }
        QrCodeRecord next = qrStore.insertRotatedActive(
                actorId, role, generatePublicCode(), PAYLOAD_VERSION, current.version() + 1, now);
        auditQr(actorId, next, "COACHING_QR_ROTATED", current.version(), next.version(), now);

        QrIdentity outcome = toIdentity(next, displayName);
        coachingStore.saveReceipt(actorId, commandKey, ROTATE_COMMAND, payloadHash, mapper.writeValueAsString(outcome));
        return outcome;
    }

    @Override
    @Transactional(readOnly = true)
    public QrProfilePreview resolveQr(UUID actorId, String publicCode, QrRole expectedTargetRole, int payloadVersion) {
        if (expectedTargetRole == null || payloadVersion != PAYLOAD_VERSION || publicCode == null) {
            throw new CoachingFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        verifyResolverActorCapability(actorId, expectedTargetRole);
        rateLimiter.checkAndConsume(actorId);

        QrCodeRecord qr = qrStore.findActiveByPublicCode(publicCode)
                .orElseThrow(() -> new CoachingFailure(HttpStatus.NOT_FOUND, "COACHING_QR_NOT_FOUND"));

        // Concealment check MUST run before self-scan or role-mismatch so unavailable accounts
        // never leak existence or bound capability role.
        if (!isTargetAccountAndCapabilityAvailable(qr.userId(), qr.role())) {
            throw new CoachingFailure(HttpStatus.NOT_FOUND, "COACHING_QR_NOT_FOUND");
        }
        if (qr.userId().equals(actorId)) {
            throw new CoachingFailure(HttpStatus.BAD_REQUEST, "COACHING_QR_SELF_SCAN");
        }
        if (qr.role() != expectedTargetRole) {
            throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_QR_ROLE_MISMATCH");
        }
        if (expectedTargetRole == QrRole.TRAINER && !availability.isAcceptingStudents(qr.userId())) {
            throw new CoachingFailure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
        }

        String displayName = requireDisplayName(qr.userId());
        TrainerSummary trainerSummary = null;
        if (expectedTargetRole == QrRole.TRAINER) {
            TrainerPublicPreview preview = trainerPreviews.findPublicPreview(qr.userId())
                    .orElseThrow(() -> new CoachingFailure(HttpStatus.NOT_FOUND, "COACHING_QR_NOT_FOUND"));
            trainerSummary = new TrainerSummary(
                    preview.verificationStatus(),
                    preview.specialties(),
                    preview.yearsExperience(),
                    preview.isAcceptingStudents()
            );
        }

        UUID studentId = expectedTargetRole == QrRole.STUDENT ? qr.userId() : actorId;
        UUID trainerId = expectedTargetRole == QrRole.TRAINER ? qr.userId() : actorId;
        Relationship pair = coachingStore.currentPairRelationship(studentId, trainerId).orElse(null);
        RelationshipState relationshipState = pair == null
                ? new RelationshipState("NONE", null, null, null, true)
                : new RelationshipState(pair.status(), pair.id(), pair.directionFor(actorId), pair.version(), false);

        return new QrProfilePreview(
                qr.userId(),
                expectedTargetRole,
                displayName,
                null,
                trainerSummary,
                relationshipState
        );
    }

    private void verifyAccount(UUID userId) {
        if (accounts.getAccountStatus(userId).orElse(null) != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Account unavailable");
        }
    }

    private void verifyOwnerCapability(UUID actorId, QrRole role) {
        verifyAccount(actorId);
        if (role == QrRole.STUDENT) {
            if (!roles.hasActiveRole(actorId, "STUDENT") || !students.hasProfile(actorId)) {
                throw new StudentCapabilityUnavailableException("Student capability unavailable");
            }
            return;
        }
        if (!roles.hasActiveRole(actorId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability unavailable");
        }
        if (!trainers.canCoach(actorId)) {
            throw new CoachingFailure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
        }
    }

    private void verifyResolverActorCapability(UUID actorId, QrRole expectedTargetRole) {
        verifyAccount(actorId);
        if (expectedTargetRole == QrRole.TRAINER) {
            if (!roles.hasActiveRole(actorId, "STUDENT") || !students.hasProfile(actorId)) {
                throw new StudentCapabilityUnavailableException("Student capability unavailable");
            }
            return;
        }
        if (!roles.hasActiveRole(actorId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability unavailable");
        }
        if (!availability.isAcceptingStudents(actorId)) {
            throw new CoachingFailure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
        }
    }

    private boolean isTargetAccountAndCapabilityAvailable(UUID targetUserId, QrRole boundRole) {
        if (accounts.getAccountStatus(targetUserId).orElse(null) != AccountStatus.ACTIVE) {
            return false;
        }
        if (boundRole == QrRole.STUDENT) {
            return roles.hasActiveRole(targetUserId, "STUDENT") && students.hasProfile(targetUserId);
        }
        return roles.hasActiveRole(targetUserId, "TRAINER") && trainerPreviews.findPublicPreview(targetUserId).isPresent();
    }

    private String requireDisplayName(UUID userId) {
        UserDisplaySummary summary = users.findDisplaySummaries(List.of(userId)).get(userId);
        if (summary == null || summary.displayName() == null) {
            throw new IllegalStateException("Coaching participant display summary is unavailable");
        }
        return summary.displayName();
    }

    private QrIdentity toIdentity(QrCodeRecord record, String displayName) {
        return new QrIdentity(
                record.id(),
                record.role(),
                record.publicCode(),
                QR_URI_PREFIX + record.publicCode(),
                record.payloadVersion(),
                displayName,
                record.issuedAt(),
                record.version()
        );
    }

    private void auditQr(UUID actorId, QrCodeRecord record, String action, Long beforeVersion, long afterVersion, Instant at) {
        String beforeJson = beforeVersion == null ? "{}" : "{\"version\":" + beforeVersion + "}";
        String afterJson = "{\"version\":" + afterVersion + ",\"role\":\"" + record.role().name() + "\"}";
        audit.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(record.role().name())
                .action(action)
                .targetType("COACHING_QR_CODE")
                .targetId(record.id())
                .beforeDataJson(beforeJson)
                .afterDataJson(afterJson)
                .metadataJson("{}")
                .occurredAt(at)
                .build());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String generatePublicCode() {
        byte[] bytes = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
