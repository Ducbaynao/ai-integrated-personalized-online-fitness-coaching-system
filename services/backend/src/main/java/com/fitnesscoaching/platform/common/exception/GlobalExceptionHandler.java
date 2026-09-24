package com.fitnesscoaching.platform.common.exception;

import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldErrorDto> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorDto(
                    fieldError.getField(),
                    fieldError.getCode() != null ? fieldError.getCode() : "Invalid",
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Invalid value"
            ));
        }

        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                "Malformed request body",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        String message = "Parameter '" + name + "' is invalid";
        List<FieldErrorDto> fieldErrors = List.of(new FieldErrorDto(
                name,
                "TypeMismatch",
                "Failed to convert value of type '" + (ex.getValue() != null ? ex.getValue().getClass().getSimpleName() : "null") + "' to required type '" + (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown") + "'"
        ));
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                message,
                Instant.now(clock),
                RequestIdHolder.get(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        ErrorResponse response = ErrorResponse.of(
                "EMAIL_ALREADY_REGISTERED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InvalidOrExpiredTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrExpiredToken(InvalidOrExpiredTokenException ex) {
        ErrorResponse response = ErrorResponse.of(
                "INVALID_OR_EXPIRED_TOKEN",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "INVALID_CREDENTIALS", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(AccountUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAccountUnavailable(AccountUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "ACCOUNT_UNAVAILABLE", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "UNAUTHORIZED",
                "Authenticated user does not exist.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                "INVALID_REFRESH_TOKEN", ex.getMessage(), Instant.now(clock), RequestIdHolder.get()));
    }

    @ExceptionHandler(StudentProfileAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleStudentProfileAlreadyExists(StudentProfileAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STUDENT_PROFILE_ALREADY_EXISTS",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentCapabilityRevokedException.class)
    public ResponseEntity<ErrorResponse> handleStudentCapabilityRevoked(StudentCapabilityRevokedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STUDENT_CAPABILITY_REVOKED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentCapabilityUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStudentCapabilityUnavailable(StudentCapabilityUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "STUDENT_CAPABILITY_UNAVAILABLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StudentProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStudentProfileNotFound(StudentProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "STUDENT_PROFILE_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerProfileAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTrainerProfileAlreadyExists(TrainerProfileAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "TRAINER_PROFILE_ALREADY_EXISTS",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerCapabilityRevokedException.class)
    public ResponseEntity<ErrorResponse> handleTrainerCapabilityRevoked(TrainerCapabilityRevokedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "TRAINER_CAPABILITY_REVOKED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerCapabilityUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleTrainerCapabilityUnavailable(TrainerCapabilityUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "TRAINER_CAPABILITY_UNAVAILABLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTrainerProfileNotFound(TrainerProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "TRAINER_PROFILE_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerSlugAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTrainerSlugAlreadyExists(TrainerSlugAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "TRAINER_SLUG_ALREADY_EXISTS",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerApplicationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTrainerApplicationNotFound(TrainerApplicationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "TRAINER_APPLICATION_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerApplicationAlreadyActiveException.class)
    public ResponseEntity<ErrorResponse> handleTrainerApplicationAlreadyActive(TrainerApplicationAlreadyActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "TRAINER_APPLICATION_ALREADY_ACTIVE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerApplicationAlreadyDecidedException.class)
    public ResponseEntity<ErrorResponse> handleTrainerApplicationAlreadyDecided(TrainerApplicationAlreadyDecidedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "TRAINER_APPLICATION_ALREADY_DECIDED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(InvalidLifecycleTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLifecycleTransition(InvalidLifecycleTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "INVALID_LIFECYCLE_TRANSITION",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(ActiveFitnessGoalAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleActiveFitnessGoalAlreadyExists(ActiveFitnessGoalAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "ACTIVE_FITNESS_GOAL_ALREADY_EXISTS",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(FitnessGoalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleFitnessGoalNotFound(FitnessGoalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "FITNESS_GOAL_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(FitnessGoalAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleFitnessGoalAccessDenied(FitnessGoalAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "ACCESS_DENIED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalVersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGoalVersionNotFound(GoalVersionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "GOAL_VERSION_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalVersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleGoalVersionConflict(GoalVersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "GOAL_VERSION_CONFLICT",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(NewGoalJourneyRequiredException.class)
    public ResponseEntity<ErrorResponse> handleNewGoalJourneyRequired(NewGoalJourneyRequiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "NEW_GOAL_JOURNEY_REQUIRED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalVersionNoChangesException.class)
    public ResponseEntity<ErrorResponse> handleGoalVersionNoChanges(GoalVersionNoChangesException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "GOAL_VERSION_NO_CHANGES",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(SameGoalJourneyTransitionException.class)
    public ResponseEntity<ErrorResponse> handleSameGoalJourneyTransition(SameGoalJourneyTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "SAME_GOAL_JOURNEY_NOT_PERMITTED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalTransitionConflictException.class)
    public ResponseEntity<ErrorResponse> handleGoalTransitionConflict(GoalTransitionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "GOAL_TRANSITION_CONFLICT",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalTransitionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGoalTransitionNotFound(GoalTransitionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "GOAL_TRANSITION_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalProposalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGoalProposalNotFound(GoalProposalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "GOAL_PROPOSAL_NOT_FOUND",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalProposalAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleGoalProposalAccessDenied(GoalProposalAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "GOAL_PROPOSAL_ACCESS_DENIED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(InvalidGoalProposalDecisionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidGoalProposalDecision(InvalidGoalProposalDecisionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(
                "INVALID_GOAL_PROPOSAL_DECISION",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalProposalAlreadyDecidedException.class)
    public ResponseEntity<ErrorResponse> handleGoalProposalAlreadyDecided(GoalProposalAlreadyDecidedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "GOAL_PROPOSAL_ALREADY_DECIDED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(GoalProposalExpiredException.class)
    public ResponseEntity<ErrorResponse> handleGoalProposalExpired(GoalProposalExpiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "GOAL_PROPOSAL_EXPIRED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(StaleGoalProposalException.class)
    public ResponseEntity<ErrorResponse> handleStaleGoalProposal(StaleGoalProposalException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                "STALE_GOAL_PROPOSAL",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(TrainerNotEligibleException.class)
    public ResponseEntity<ErrorResponse> handleTrainerNotEligible(TrainerNotEligibleException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "TRAINER_NOT_ELIGIBLE",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(CoachingRelationshipRequiredException.class)
    public ResponseEntity<ErrorResponse> handleCoachingRelationshipRequired(CoachingRelationshipRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "COACHING_RELATIONSHIP_REQUIRED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(DataSharingPermissionRequiredException.class)
    public ResponseEntity<ErrorResponse> handleDataSharingPermissionRequired(DataSharingPermissionRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                "DATA_SHARING_PERMISSION_REQUIRED",
                ex.getMessage(),
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String constraintName = extractConstraintName(ex);
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (matchesConstraint(constraintName, msg, "uq_trainer_pending_application")) {
            ErrorResponse response = ErrorResponse.of(
                    "TRAINER_APPLICATION_ALREADY_ACTIVE",
                    "An active verification application already exists for this trainer.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "users_email_key") || msg.contains("email")) {
            ErrorResponse response = ErrorResponse.of(
                    "EMAIL_ALREADY_REGISTERED",
                    "Email is already registered.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "student_profiles_pkey") || msg.contains("student_profiles")) {
            ErrorResponse response = ErrorResponse.of(
                    "STUDENT_PROFILE_ALREADY_EXISTS",
                    "Student profile already exists for this account.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "trainer_profiles_public_slug_key")) {
            ErrorResponse response = ErrorResponse.of(
                    "TRAINER_SLUG_ALREADY_EXISTS",
                    "Public slug is already in use by another trainer profile.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "trainer_profiles_pkey")) {
            ErrorResponse response = ErrorResponse.of(
                    "TRAINER_PROFILE_ALREADY_EXISTS",
                    "Trainer profile already exists for this account.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_student_active_fitness_goal")) {
            ErrorResponse response = ErrorResponse.of(
                    "ACTIVE_FITNESS_GOAL_ALREADY_EXISTS",
                    "An active fitness goal already exists for this student.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_current_version")) {
            ErrorResponse response = ErrorResponse.of(
                    "GOAL_VERSION_CONFLICT",
                    "A current goal version already exists or was modified concurrently.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_target_metric")) {
            ErrorResponse response = ErrorResponse.of(
                    "VALIDATION_FAILED",
                    "Duplicate target metric definition specified for this goal version.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_proposal_target_metric")) {
            ErrorResponse response = ErrorResponse.of(
                    "VALIDATION_FAILED",
                    "Duplicate target metric definition specified for this goal proposal.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_proposal_primary_objective")) {
            ErrorResponse response = ErrorResponse.of(
                    "VALIDATION_FAILED",
                    "Only one PRIMARY objective is allowed for a goal proposal.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_transitions_previous_goal")) {
            ErrorResponse response = ErrorResponse.of(
                    "GOAL_TRANSITION_CONFLICT",
                    "A transition has already been created for this previous goal.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_transitions_new_goal")) {
            ErrorResponse response = ErrorResponse.of(
                    "GOAL_TRANSITION_CONFLICT",
                    "The target goal is already the result of an existing transition.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        if (matchesConstraint(constraintName, msg, "uq_goal_transitions_proposal")) {
            ErrorResponse response = ErrorResponse.of(
                    "GOAL_TRANSITION_CONFLICT",
                    "A transition has already been created for this proposal.",
                    Instant.now(clock),
                    RequestIdHolder.get(),
                    Collections.emptyList()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        log.error("Data integrity violation: constraintName={}, message={}", constraintName, ex.getMessage(), ex);
        ErrorResponse response = ErrorResponse.of(
                "DATA_INTEGRITY_VIOLATION",
                "A database constraint was violated.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    private String extractConstraintName(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null) {
                    return cve.getConstraintName().toLowerCase();
                }
            }
            try {
                java.lang.reflect.Method getServerErrorMessage = current.getClass().getMethod("getServerErrorMessage");
                Object serverError = getServerErrorMessage.invoke(current);
                if (serverError != null) {
                    java.lang.reflect.Method getConstraint = serverError.getClass().getMethod("getConstraint");
                    Object constraint = getConstraint.invoke(serverError);
                    if (constraint instanceof String s && !s.isBlank()) {
                        return s.toLowerCase();
                    }
                }
            } catch (Exception ignored) {
                // Not a PostgreSQL ServerErrorMessage provider or method unavailable
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean matchesConstraint(String constraintName, String msg, String targetConstraint) {
        if (constraintName != null && constraintName.equals(targetConstraint)) {
            return true;
        }
        return msg.contains(targetConstraint);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ErrorResponse response = ErrorResponse.of(
                "ACCESS_DENIED",
                "Access is denied.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(ApplicationValidationException.class)
    public ResponseEntity<ErrorResponse> handleApplicationValidation(ApplicationValidationException ex) {
        ErrorResponse response = ErrorResponse.of(
                "VALIDATION_FAILED",
                ex.getMessage() != null ? ex.getMessage() : "Request validation failed",
                Instant.now(clock),
                RequestIdHolder.get(),
                ex.getFieldErrors()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(SystemRoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSystemRoleNotFound(SystemRoleNotFoundException ex) {
        log.error("System role missing from database: {}", ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "System configuration error: required role does not exist.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception caught in controller advice", ex);
        ErrorResponse response = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "An unexpected server error occurred.",
                Instant.now(clock),
                RequestIdHolder.get(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
