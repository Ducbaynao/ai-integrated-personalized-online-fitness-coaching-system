package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyActiveException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.media.application.port.in.MediaQueryPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetCurrentTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerCertificatePort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class TrainerApplicationService implements SubmitTrainerApplicationUseCase, GetCurrentTrainerApplicationUseCase {

    private final TrainerProfilePort trainerProfilePort;
    private final TrainerApplicationPort trainerApplicationPort;
    private final TrainerCertificatePort trainerCertificatePort;
    private final MediaQueryPort mediaQueryPort;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final Clock clock;

    public TrainerApplicationService(
            TrainerProfilePort trainerProfilePort,
            TrainerApplicationPort trainerApplicationPort,
            TrainerCertificatePort trainerCertificatePort,
            MediaQueryPort mediaQueryPort,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            Clock clock
    ) {
        this.trainerProfilePort = trainerProfilePort;
        this.trainerApplicationPort = trainerApplicationPort;
        this.trainerCertificatePort = trainerCertificatePort;
        this.mediaQueryPort = mediaQueryPort;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public TrainerApplication submitApplication(SubmitTrainerApplicationCommand command) {
        validateCommand(command);

        UUID trainerId = command.trainerId();
        verifyActiveAccount(trainerId);

        if (!userRoleQuery.hasActiveRole(trainerId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability is not active for user: " + trainerId);
        }

        TrainerProfile profile = trainerProfilePort.findByUserId(trainerId)
                .orElseThrow(() -> new TrainerProfileNotFoundException("Trainer profile not found for user: " + trainerId));

        if (!profile.isActive()) {
            throw new TrainerCapabilityUnavailableException("Trainer profile is inactive for user: " + trainerId);
        }

        if (trainerApplicationPort.hasActiveApplication(trainerId)
                || profile.verificationStatus() == TrainerVerificationStatus.PENDING) {
            throw new TrainerApplicationAlreadyActiveException("An active verification application already exists for this trainer.");
        }

        if (profile.verificationStatus() != TrainerVerificationStatus.NOT_SUBMITTED) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot submit application when trainer profile verification status is " + profile.verificationStatus());
        }

        if (!command.certificateIds().isEmpty()) {
            boolean validCerts = trainerCertificatePort.allCertificatesExistAndBelongToTrainer(command.certificateIds(), trainerId);
            if (!validCerts) {
                throw new ApplicationValidationException(
                        "One or more certificates do not exist or do not belong to the trainer",
                        List.of(new FieldErrorDto("certificateIds", "INVALID_CERTIFICATE", "One or more certificates do not exist or do not belong to the trainer"))
                );
            }
        }

        if (!command.documentMediaIds().isEmpty()) {
            boolean validDocs = mediaQueryPort.allMediaExistAndOwnedBy(command.documentMediaIds(), trainerId);
            if (!validDocs) {
                throw new ApplicationValidationException(
                        "One or more document media files do not exist or are not authorized",
                        List.of(new FieldErrorDto("documentMediaIds", "INVALID_MEDIA", "One or more document media files do not exist or are not authorized"))
                );
            }
        }

        Instant now = Instant.now(clock);
        UUID applicationId = UUID.randomUUID();

        TrainerApplication application = new TrainerApplication(
                applicationId,
                trainerId,
                TrainerVerificationStatus.PENDING,
                now,
                null,
                null,
                null,
                command.applicantNote(),
                command.certificateIds(),
                command.documentMediaIds(),
                now,
                now
        );

        TrainerApplication saved = trainerApplicationPort.save(application);
        trainerProfilePort.updateVerificationStatus(trainerId, TrainerVerificationStatus.PENDING, now);

        String metadataJson = String.format(
                "{\"certificateCount\":%d,\"documentCount\":%d}",
                command.certificateIds().size(),
                command.documentMediaIds().size()
        );
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(trainerId)
                .actorRole("TRAINER")
                .action("TRAINER_APPLICATION_SUBMITTED")
                .targetType("TRAINER_APPLICATION")
                .targetId(applicationId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson(metadataJson)
                .build());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public TrainerApplication getCurrentApplication(UUID trainerId) {
        if (trainerId == null) {
            throw new ApplicationValidationException("Trainer ID cannot be null");
        }
        verifyActiveAccount(trainerId);

        if (!userRoleQuery.hasActiveRole(trainerId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability is not active for user: " + trainerId);
        }

        TrainerProfile profile = trainerProfilePort.findByUserId(trainerId)
                .orElseThrow(() -> new TrainerProfileNotFoundException("Trainer profile not found for user: " + trainerId));

        if (!profile.isActive()) {
            throw new TrainerCapabilityUnavailableException("Trainer profile is inactive for user: " + trainerId);
        }

        return trainerApplicationPort.findCurrentByTrainerId(trainerId)
                .orElseThrow(() -> new TrainerApplicationNotFoundException("No trainer application found for user: " + trainerId));
    }

    private void verifyActiveAccount(UUID userId) {
        AccountStatus status = userAccountStatusQuery.getAccountStatus(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account is not in ACTIVE state (current status: " + status + "). Operation not permitted.");
        }
    }

    private void validateCommand(SubmitTrainerApplicationCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.trainerId() == null) {
            throw new ApplicationValidationException("Trainer ID cannot be null");
        }
        if (command.applicantNote() != null && command.applicantNote().length() > 2000) {
            throw new ApplicationValidationException(
                    "Applicant note cannot exceed 2000 characters",
                    List.of(new FieldErrorDto("applicantNote", "MAX_LENGTH", "Applicant note cannot exceed 2000 characters"))
            );
        }

        if (hasDuplicates(command.certificateIds())) {
            throw new ApplicationValidationException(
                    "Duplicate certificate IDs are not allowed",
                    List.of(new FieldErrorDto("certificateIds", "DUPLICATE", "Duplicate certificate IDs are not allowed"))
            );
        }

        if (hasDuplicates(command.documentMediaIds())) {
            throw new ApplicationValidationException(
                    "Duplicate document media IDs are not allowed",
                    List.of(new FieldErrorDto("documentMediaIds", "DUPLICATE", "Duplicate document media IDs are not allowed"))
            );
        }
    }

    private boolean hasDuplicates(List<UUID> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        Set<UUID> set = new HashSet<>();
        for (UUID item : list) {
            if (!set.add(item)) {
                return true;
            }
        }
        return false;
    }
}
