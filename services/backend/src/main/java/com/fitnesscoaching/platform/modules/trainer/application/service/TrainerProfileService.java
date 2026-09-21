package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerSlugAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleUseCase;
import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class TrainerProfileService implements CreateTrainerProfileUseCase, GetTrainerProfileUseCase, UpdateTrainerProfileUseCase {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final TrainerProfilePort trainerProfilePort;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleUseCase userRoleUseCase;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final Clock clock;

    public TrainerProfileService(
            TrainerProfilePort trainerProfilePort,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleUseCase userRoleUseCase,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            Clock clock
    ) {
        this.trainerProfilePort = trainerProfilePort;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleUseCase = userRoleUseCase;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public TrainerProfile createTrainerProfile(CreateTrainerProfileCommand command) {
        validateCreateCommand(command);

        UUID userId = command.userId();

        // 1. Account status verification
        verifyActiveAccount(userId);

        // 2. Profile existence check
        if (trainerProfilePort.existsByUserId(userId)) {
            throw new TrainerProfileAlreadyExistsException(
                    "Trainer profile already exists for user: " + userId);
        }

        // 3. Unique slug check if provided
        if (command.publicSlug() != null && trainerProfilePort.existsByPublicSlug(command.publicSlug())) {
            throw new TrainerSlugAlreadyExistsException(
                    "Public slug '" + command.publicSlug() + "' is already in use by another trainer.");
        }

        // 4. Role assignment verification & activation
        RoleActivationResult activationResult = userRoleUseCase.activateRole(userId, "TRAINER", userId);
        if (activationResult == RoleActivationResult.REVOKED) {
            throw new TrainerCapabilityRevokedException(
                    "Trainer capability has been revoked for this account and cannot be self-reactivated.");
        }

        Instant now = Instant.now(clock);
        boolean accepting = Boolean.TRUE.equals(command.acceptingStudents());

        // 5. Save profile in DB
        TrainerProfile newProfile = new TrainerProfile(
                userId,
                command.publicSlug(),
                command.bio(),
                command.yearsExperience(),
                accepting,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                now,
                now
        );
        TrainerProfile saved = trainerProfilePort.save(newProfile);

        // 6. Record audit event
        String slugJson = command.publicSlug() != null ? "\"" + command.publicSlug() + "\"" : "null";
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(userId)
                .actorRole("USER")
                .action("TRAINER_PROFILE_CREATED")
                .targetType("TRAINER_PROFILE")
                .targetId(userId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"publicSlug\":" + slugJson + ",\"acceptingStudents\":" + accepting + "}")
                .build());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public TrainerProfile getTrainerProfile(UUID userId) {
        if (userId == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        verifyActiveAccount(userId);

        TrainerProfile profile = trainerProfilePort.findByUserId(userId)
                .orElseThrow(() -> new TrainerProfileNotFoundException(
                        "Trainer profile not found for user: " + userId));

        if (!userRoleQuery.hasActiveRole(userId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException(
                    "Trainer capability is not active for user: " + userId);
        }

        return profile;
    }

    @Override
    public TrainerProfile updateTrainerProfile(UpdateTrainerProfileCommand command) {
        validateUpdateCommand(command);

        UUID userId = command.userId();
        verifyActiveAccount(userId);

        TrainerProfile current = trainerProfilePort.findByUserId(userId)
                .orElseThrow(() -> new TrainerProfileNotFoundException(
                        "Trainer profile not found for user: " + userId));

        if (!userRoleQuery.hasActiveRole(userId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException(
                    "Trainer capability is not active for user: " + userId);
        }

        // Unique slug check if updated to non-null value
        if (command.publicSlug().isSpecified() && command.publicSlug().value() != null) {
            String newSlug = command.publicSlug().value();
            if (trainerProfilePort.existsByPublicSlugAndUserIdNot(newSlug, userId)) {
                throw new TrainerSlugAlreadyExistsException(
                        "Public slug '" + newSlug + "' is already in use by another trainer.");
            }
        }

        String updatedSlug = command.publicSlug().isSpecified() ? command.publicSlug().value() : current.publicSlug();
        String updatedBio = command.bio().isSpecified() ? command.bio().value() : current.bio();
        BigDecimal updatedYears = command.yearsExperience().isSpecified() ? command.yearsExperience().value() : current.yearsExperience();
        boolean updatedAccepting = command.acceptingStudents().isSpecified()
                ? Boolean.TRUE.equals(command.acceptingStudents().value())
                : current.isAcceptingStudents();

        Instant now = Instant.now(clock);

        TrainerProfile updated = new TrainerProfile(
                userId,
                updatedSlug,
                updatedBio,
                updatedYears,
                updatedAccepting,
                current.verificationStatus(),
                current.activityStatus(),
                current.verifiedAt(),
                current.verifiedBy(),
                current.isActive(),
                current.createdAt(),
                now
        );

        return trainerProfilePort.update(updated);
    }

    private void verifyActiveAccount(UUID userId) {
        AccountStatus status = userAccountStatusQuery.getAccountStatus(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account is not in ACTIVE state (current status: " + status + "). Operation not permitted.");
        }
    }

    private void validateCreateCommand(CreateTrainerProfileCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.userId() == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        if (command.publicSlug() != null) {
            if (command.publicSlug().length() > 100) {
                throw new ApplicationValidationException(
                        "Public slug cannot exceed 100 characters",
                        List.of(new FieldErrorDto("publicSlug", "MAX_LENGTH", "Public slug cannot exceed 100 characters"))
                );
            }
            if (!SLUG_PATTERN.matcher(command.publicSlug()).matches()) {
                throw new ApplicationValidationException(
                        "Public slug must contain only lowercase alphanumeric characters and single hyphens",
                        List.of(new FieldErrorDto("publicSlug", "PATTERN", "Public slug must contain only lowercase alphanumeric characters and single hyphens"))
                );
            }
        }
        if (command.bio() != null && command.bio().length() > 4000) {
            throw new ApplicationValidationException(
                    "Bio cannot exceed 4000 characters",
                    List.of(new FieldErrorDto("bio", "MAX_LENGTH", "Bio cannot exceed 4000 characters"))
            );
        }
        if (command.yearsExperience() != null) {
            validateYearsExperience(command.yearsExperience());
        }
    }

    private void validateUpdateCommand(UpdateTrainerProfileCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.userId() == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        if (command.publicSlug() == null || command.bio() == null
                || command.yearsExperience() == null || command.acceptingStudents() == null) {
            throw new ApplicationValidationException("Patch fields cannot be null");
        }
        if (!command.publicSlug().isSpecified()
                && !command.bio().isSpecified()
                && !command.yearsExperience().isSpecified()
                && !command.acceptingStudents().isSpecified()) {
            throw new ApplicationValidationException("At least one field must be specified for update");
        }
        if (command.acceptingStudents().isSpecified() && command.acceptingStudents().value() == null) {
            throw new ApplicationValidationException(
                    "acceptingStudents cannot be null",
                    List.of(new FieldErrorDto("acceptingStudents", "NOT_NULL", "acceptingStudents cannot be null"))
            );
        }
        if (command.publicSlug().isSpecified() && command.publicSlug().value() != null) {
            String slug = command.publicSlug().value();
            if (slug.length() > 100) {
                throw new ApplicationValidationException(
                        "Public slug cannot exceed 100 characters",
                        List.of(new FieldErrorDto("publicSlug", "MAX_LENGTH", "Public slug cannot exceed 100 characters"))
                );
            }
            if (!SLUG_PATTERN.matcher(slug).matches()) {
                throw new ApplicationValidationException(
                        "Public slug must contain only lowercase alphanumeric characters and single hyphens",
                        List.of(new FieldErrorDto("publicSlug", "PATTERN", "Public slug must contain only lowercase alphanumeric characters and single hyphens"))
                );
            }
        }
        if (command.bio().isSpecified() && command.bio().value() != null && command.bio().value().length() > 4000) {
            throw new ApplicationValidationException(
                    "Bio cannot exceed 4000 characters",
                    List.of(new FieldErrorDto("bio", "MAX_LENGTH", "Bio cannot exceed 4000 characters"))
            );
        }
        if (command.yearsExperience().isSpecified() && command.yearsExperience().value() != null) {
            validateYearsExperience(command.yearsExperience().value());
        }
    }

    private void validateYearsExperience(BigDecimal val) {
        if (val.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationValidationException(
                    "Years of experience cannot be negative",
                    List.of(new FieldErrorDto("yearsExperience", "DECIMAL_MIN", "Years of experience cannot be negative"))
            );
        }
        if (val.compareTo(new BigDecimal("99.99")) > 0) {
            throw new ApplicationValidationException(
                    "Years of experience cannot exceed 99.99",
                    List.of(new FieldErrorDto("yearsExperience", "DECIMAL_MAX", "Years of experience cannot exceed 99.99"))
            );
        }
        if (val.scale() > 2) {
            throw new ApplicationValidationException(
                    "Years of experience must have at most 2 decimal places",
                    List.of(new FieldErrorDto("yearsExperience", "DIGITS", "Years of experience must have at most 2 decimal places"))
            );
        }
    }
}
