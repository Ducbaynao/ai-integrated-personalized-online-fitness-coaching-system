package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyDecidedException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationDetailUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AdminTrainerVerificationService implements
        GetAdminTrainerApplicationsUseCase,
        GetAdminTrainerApplicationDetailUseCase,
        DecideTrainerApplicationUseCase {

    private final TrainerApplicationPort trainerApplicationPort;
    private final TrainerProfilePort trainerProfilePort;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final Clock clock;

    public AdminTrainerVerificationService(
            TrainerApplicationPort trainerApplicationPort,
            TrainerProfilePort trainerProfilePort,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            Clock clock
    ) {
        this.trainerApplicationPort = trainerApplicationPort;
        this.trainerProfilePort = trainerProfilePort;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminTrainerApplicationPage getApplications(GetAdminTrainerApplicationsQuery query) {
        verifyActiveAdmin(query.adminUserId());

        int page = query.page();
        int size = query.size();

        if (page < 0) {
            throw new ApplicationValidationException("Page index cannot be negative",
                    List.of(new FieldErrorDto("page", "Min", "Page index cannot be negative")));
        }
        if (size < 1 || size > 100) {
            throw new ApplicationValidationException("Page size must be between 1 and 100",
                    List.of(new FieldErrorDto("size", "Range", "Page size must be between 1 and 100")));
        }

        TrainerVerificationStatus status = query.status() != null
                ? query.status()
                : TrainerVerificationStatus.PENDING;

        long offset;
        try {
            offset = Math.multiplyExact((long) page, (long) size);
        } catch (ArithmeticException e) {
            throw new ApplicationValidationException("Page index is too large",
                    List.of(new FieldErrorDto("page", "Max", "Page index produces an offset that exceeds supported limits")));
        }

        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new ApplicationValidationException("Page index is too large",
                    List.of(new FieldErrorDto("page", "Max", "Page index produces an offset that exceeds supported limits")));
        }

        List<TrainerApplication> items = trainerApplicationPort.findApplications(status, offset, size);
        long totalItems = trainerApplicationPort.countApplications(status);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);

        return new AdminTrainerApplicationPage(items, page, size, totalItems, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public TrainerApplication getApplicationDetail(UUID adminUserId, UUID applicationId) {
        verifyActiveAdmin(adminUserId);

        if (applicationId == null) {
            throw new ApplicationValidationException("Application ID cannot be null");
        }

        return trainerApplicationPort.findById(applicationId)
                .orElseThrow(() -> new TrainerApplicationNotFoundException(
                        "Trainer application not found: " + applicationId));
    }

    @Override
    public TrainerApplication decideApplication(DecideTrainerApplicationCommand command) {
        verifyActiveAdmin(command.adminUserId());

        String decision = command.decision();
        if (decision == null || (!decision.equals("APPROVE") && !decision.equals("REJECT"))) {
            throw new ApplicationValidationException("Decision must be either APPROVE or REJECT",
                    List.of(new FieldErrorDto("decision", "Invalid", "Decision must be either APPROVE or REJECT")));
        }

        String rejectionReason = command.rejectionReason();
        if (decision.equals("REJECT")) {
            if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
                throw new ApplicationValidationException("Rejection reason is required when rejecting an application",
                        List.of(new FieldErrorDto("rejectionReason", "NotBlank", "Rejection reason is required when rejecting an application")));
            }
            if (rejectionReason.length() > 2000) {
                throw new ApplicationValidationException("Rejection reason cannot exceed 2000 characters",
                        List.of(new FieldErrorDto("rejectionReason", "Size", "Rejection reason cannot exceed 2000 characters")));
            }
        } else {
            if (rejectionReason != null && !rejectionReason.trim().isEmpty()) {
                throw new ApplicationValidationException("Rejection reason must not be provided when approving an application",
                        List.of(new FieldErrorDto("rejectionReason", "Pattern", "Rejection reason must not be provided when approving an application")));
            }
        }

        String reviewNotes = command.reviewNotes();
        if (reviewNotes != null && reviewNotes.length() > 2000) {
            throw new ApplicationValidationException("Review notes cannot exceed 2000 characters",
                    List.of(new FieldErrorDto("reviewNotes", "Size", "Review notes cannot exceed 2000 characters")));
        }

        TrainerApplication app = trainerApplicationPort.findByIdForUpdate(command.applicationId())
                .orElseThrow(() -> new TrainerApplicationNotFoundException(
                        "Trainer application not found: " + command.applicationId()));

        if (app.status() != TrainerVerificationStatus.PENDING) {
            throw new TrainerApplicationAlreadyDecidedException(
                    "Trainer application " + command.applicationId() + " has already been decided with status: " + app.status());
        }

        Instant now = Instant.now(clock);
        TrainerVerificationStatus newStatus;
        String finalRejectionReason;
        Instant verifiedAt;
        UUID verifiedBy;
        String historyReason;
        String auditAction;

        if (decision.equals("APPROVE")) {
            newStatus = TrainerVerificationStatus.VERIFIED;
            finalRejectionReason = null;
            verifiedAt = now;
            verifiedBy = command.adminUserId();
            historyReason = "Application approved by admin";
            auditAction = "TRAINER_APPLICATION_APPROVED";
        } else {
            newStatus = TrainerVerificationStatus.REJECTED;
            finalRejectionReason = rejectionReason.trim();
            verifiedAt = null;
            verifiedBy = null;
            historyReason = finalRejectionReason;
            auditAction = "TRAINER_APPLICATION_REJECTED";
        }

        String cleanReviewNotes = reviewNotes != null && !reviewNotes.trim().isEmpty() ? reviewNotes.trim() : null;

        int updatedRows = trainerApplicationPort.updateDecision(
                app.id(),
                newStatus,
                now,
                command.adminUserId(),
                finalRejectionReason,
                cleanReviewNotes,
                now
        );

        if (updatedRows == 0) {
            throw new TrainerApplicationAlreadyDecidedException(
                    "Trainer application " + command.applicationId() + " has already been decided");
        }

        int profileUpdated = trainerProfilePort.updateVerificationDetails(
                app.trainerId(),
                newStatus,
                verifiedAt,
                verifiedBy,
                now
        );

        if (profileUpdated == 0) {
            throw new InvalidLifecycleTransitionException(
                    "Trainer profile is not in PENDING state or has already been modified.");
        }

        trainerApplicationPort.recordStatusHistory(
                app.id(),
                TrainerVerificationStatus.PENDING,
                newStatus,
                command.adminUserId(),
                historyReason,
                now
        );

        String beforeData = "{\"status\":\"" + app.status().name() + "\"}";
        String afterData = "{\"status\":\"" + newStatus.name() + "\"}";
        String metadata = String.format(
                "{\"decision\":\"%s\",\"trainerId\":\"%s\"}",
                decision,
                app.trainerId()
        );

        AuditRecord auditRecord = AuditRecord.builder()
                .actorUserId(command.adminUserId())
                .actorRole("ADMIN")
                .action(auditAction)
                .targetType("TRAINER_APPLICATION")
                .targetId(app.id())
                .requestId(RequestIdHolder.getAsUuid())
                .beforeDataJson(beforeData)
                .afterDataJson(afterData)
                .metadataJson(metadata)
                .occurredAt(now)
                .build();

        auditService.recordAudit(auditRecord);

        return trainerApplicationPort.findById(app.id())
                .orElseThrow(() -> new TrainerApplicationNotFoundException(
                        "Trainer application not found: " + app.id()));
    }

    private void verifyActiveAdmin(UUID adminUserId) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Admin user ID is required");
        }

        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User does not exist"));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account is not in ACTIVE state (current status: " + status + "). Operation not permitted.");
        }

        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("User does not possess an active ADMIN role");
        }
    }
}
