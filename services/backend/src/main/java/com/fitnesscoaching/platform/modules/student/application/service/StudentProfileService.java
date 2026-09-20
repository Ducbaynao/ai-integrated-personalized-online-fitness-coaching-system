package com.fitnesscoaching.platform.modules.student.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.StudentProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.out.StudentProfilePort;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleUseCase;
import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional
public class StudentProfileService implements CreateStudentProfileUseCase, GetStudentProfileUseCase, UpdateStudentProfileUseCase {

    private final StudentProfilePort studentProfilePort;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleUseCase userRoleUseCase;
    private final UserRoleQuery userRoleQuery;
    private final AuditService auditService;
    private final Clock clock;

    public StudentProfileService(
            StudentProfilePort studentProfilePort,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleUseCase userRoleUseCase,
            UserRoleQuery userRoleQuery,
            AuditService auditService,
            Clock clock
    ) {
        this.studentProfilePort = studentProfilePort;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleUseCase = userRoleUseCase;
        this.userRoleQuery = userRoleQuery;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public StudentProfile createStudentProfile(CreateStudentProfileCommand command) {
        validateCreateCommand(command);

        UUID userId = command.userId();

        // 1. Account existence and status check
        verifyActiveAccount(userId);

        // 2. Profile existence check
        if (studentProfilePort.existsByUserId(userId)) {
            throw new StudentProfileAlreadyExistsException(
                    "Student profile already exists for user: " + userId);
        }

        // 3. Role assignment verification & activation
        RoleActivationResult activationResult = userRoleUseCase.activateRole(userId, "STUDENT", userId);
        if (activationResult == RoleActivationResult.REVOKED) {
            throw new StudentCapabilityRevokedException(
                    "Student capability has been revoked for this account and cannot be self-reactivated.");
        }

        Instant now = Instant.now(clock);
        Instant onboardingCompletedAt = Boolean.TRUE.equals(command.onboardingCompleted()) ? now : null;

        // 4. Save profile in DB
        StudentProfile newProfile = new StudentProfile(
                userId,
                command.dateOfBirth(),
                command.gender(),
                command.trainingExperienceLevel(),
                command.trainingExperienceMonths(),
                command.availableDaysPerWeek(),
                command.preferredSessionMinutes(),
                onboardingCompletedAt,
                now,
                now
        );
        StudentProfile saved = studentProfilePort.save(newProfile);

        // 5. Record audit record
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(userId)
                .actorRole("USER")
                .action("STUDENT_PROFILE_CREATED")
                .targetType("STUDENT_PROFILE")
                .targetId(userId)
                .requestId(RequestIdHolder.getAsUuid())
                .occurredAt(now)
                .metadataJson("{\"onboardingCompleted\":" + Boolean.TRUE.equals(command.onboardingCompleted()) + "}")
                .build());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public StudentProfile getStudentProfile(UUID userId) {
        if (userId == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        verifyActiveAccount(userId);

        StudentProfile profile = studentProfilePort.findByUserId(userId)
                .orElseThrow(() -> new StudentProfileNotFoundException(
                        "Student profile not found for user: " + userId));

        if (!userRoleQuery.hasActiveRole(userId, "STUDENT")) {
            throw new StudentCapabilityUnavailableException(
                    "Student capability is not active for user: " + userId);
        }

        return profile;
    }

    @Override
    public StudentProfile updateStudentProfile(UpdateStudentProfileCommand command) {
        validateUpdateCommand(command);

        UUID userId = command.userId();
        verifyActiveAccount(userId);

        StudentProfile existing = studentProfilePort.findByUserId(userId)
                .orElseThrow(() -> new StudentProfileNotFoundException(
                        "Student profile not found for user: " + userId));

        if (!userRoleQuery.hasActiveRole(userId, "STUDENT")) {
            throw new StudentCapabilityUnavailableException(
                    "Student capability is not active for user: " + userId);
        }

        Instant now = Instant.now(clock);

        LocalDate dateOfBirth = command.dateOfBirth().isSpecified()
                ? command.dateOfBirth().value()
                : existing.dateOfBirth();

        Gender gender = command.gender().isSpecified()
                ? command.gender().value()
                : existing.gender();

        TrainingExperienceLevel level = command.trainingExperienceLevel().isSpecified()
                ? command.trainingExperienceLevel().value()
                : existing.trainingExperienceLevel();

        BigDecimal months = command.trainingExperienceMonths().isSpecified()
                ? command.trainingExperienceMonths().value()
                : existing.trainingExperienceMonths();

        Integer days = command.availableDaysPerWeek().isSpecified()
                ? command.availableDaysPerWeek().value()
                : existing.availableDaysPerWeek();

        Integer minutes = command.preferredSessionMinutes().isSpecified()
                ? command.preferredSessionMinutes().value()
                : existing.preferredSessionMinutes();

        validateTargetState(dateOfBirth, months, days, minutes);

        Instant onboardingCompletedAt;
        if (command.onboardingCompleted().isSpecified()) {
            boolean newCompleted = Boolean.TRUE.equals(command.onboardingCompleted().value());
            boolean wasCompleted = existing.isOnboardingCompleted();
            if (!wasCompleted && newCompleted) {
                onboardingCompletedAt = now;
            } else if (wasCompleted && !newCompleted) {
                onboardingCompletedAt = null;
            } else {
                onboardingCompletedAt = existing.onboardingCompletedAt();
            }
        } else {
            onboardingCompletedAt = existing.onboardingCompletedAt();
        }

        StudentProfile updated = new StudentProfile(
                existing.userId(),
                dateOfBirth,
                gender,
                level,
                months,
                days,
                minutes,
                onboardingCompletedAt,
                existing.createdAt(),
                now
        );

        return studentProfilePort.update(updated);
    }

    private void validateCreateCommand(CreateStudentProfileCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.userId() == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        if (command.onboardingCompleted() == null) {
            throw new ApplicationValidationException("onboardingCompleted cannot be null");
        }
        if (command.dateOfBirth() != null && command.dateOfBirth().isAfter(LocalDate.now(clock))) {
            throw new ApplicationValidationException("Date of birth cannot be in the future");
        }
        if (command.trainingExperienceMonths() != null && command.trainingExperienceMonths().compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationValidationException("Training experience months cannot be negative");
        }
        if (command.availableDaysPerWeek() != null && (command.availableDaysPerWeek() < 1 || command.availableDaysPerWeek() > 7)) {
            throw new ApplicationValidationException("Available days per week must be between 1 and 7");
        }
        if (command.preferredSessionMinutes() != null && (command.preferredSessionMinutes() < 5 || command.preferredSessionMinutes() > 480)) {
            throw new ApplicationValidationException("Preferred session minutes must be between 5 and 480");
        }
    }

    private void validateUpdateCommand(UpdateStudentProfileCommand command) {
        if (command == null) {
            throw new ApplicationValidationException("Command cannot be null");
        }
        if (command.userId() == null) {
            throw new ApplicationValidationException("User ID cannot be null");
        }
        if (command.dateOfBirth() == null
                || command.gender() == null
                || command.trainingExperienceLevel() == null
                || command.trainingExperienceMonths() == null
                || command.availableDaysPerWeek() == null
                || command.preferredSessionMinutes() == null
                || command.onboardingCompleted() == null) {
            throw new ApplicationValidationException("Patch fields cannot be null");
        }
        boolean hasAtLeastOneSpecified = command.dateOfBirth().isSpecified()
                || command.gender().isSpecified()
                || command.trainingExperienceLevel().isSpecified()
                || command.trainingExperienceMonths().isSpecified()
                || command.availableDaysPerWeek().isSpecified()
                || command.preferredSessionMinutes().isSpecified()
                || command.onboardingCompleted().isSpecified();
        if (!hasAtLeastOneSpecified) {
            throw new ApplicationValidationException("At least one property must be provided");
        }
        if (command.onboardingCompleted().isSpecified() && command.onboardingCompleted().value() == null) {
            throw new ApplicationValidationException("onboardingCompleted cannot be null");
        }
    }

    private void validateTargetState(LocalDate dateOfBirth, BigDecimal months, Integer days, Integer minutes) {
        if (dateOfBirth != null && dateOfBirth.isAfter(LocalDate.now(clock))) {
            throw new ApplicationValidationException("Date of birth cannot be in the future");
        }
        if (months != null && months.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationValidationException("Training experience months cannot be negative");
        }
        if (days != null && (days < 1 || days > 7)) {
            throw new ApplicationValidationException("Available days per week must be between 1 and 7");
        }
        if (minutes != null && (minutes < 5 || minutes > 480)) {
            throw new ApplicationValidationException("Preferred session minutes must be between 5 and 480");
        }
    }

    private void verifyActiveAccount(UUID userId) {
        AccountStatus status = userAccountStatusQuery.getAccountStatus(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account status " + status + " does not permit this operation.");
        }
    }
}
