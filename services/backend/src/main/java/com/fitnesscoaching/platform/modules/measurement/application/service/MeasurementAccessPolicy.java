package com.fitnesscoaching.platform.modules.measurement.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class MeasurementAccessPolicy {

    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;
    private final CoachingAuthorityQuery coaching;

    public MeasurementAccessPolicy(
            UserAccountStatusQuery accounts,
            StudentCapabilityQuery students,
            CoachingAuthorityQuery coaching
    ) {
        this.accounts = accounts;
        this.students = students;
        this.coaching = coaching;
    }

    public MeasurementAccessDecision evaluateAccess(UUID actorId, UUID studentId) {
        return evaluateAccess(actorId, studentId, DataScope.BODY_METRICS);
    }

    public MeasurementAccessDecision evaluateAccess(UUID actorId, UUID studentId, DataScope dataScope) {
        if (actorId == null || studentId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID and Student ID must not be null");
        }

        DataScope scope = dataScope != null ? dataScope : DataScope.BODY_METRICS;

        AccountStatus actorStatus = accounts.getAccountStatus(actorId).orElse(null);
        if (actorStatus != AccountStatus.ACTIVE) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Actor account is not active");
        }

        if (!students.hasProfile(studentId)) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_NOT_FOUND, "Student profile not found for ID: " + studentId);
        }

        boolean isSelf = actorId.equals(studentId);
        if (isSelf) {
            return new MeasurementAccessDecision(true, true, null, null);
        }

        AuthorityDecision decision = coaching.evaluateCurrent(
                new AuthorityRequest(actorId, studentId, scope, DataAccessLevel.VIEW)
        );

        if (!decision.allowed()) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED,
                    "Access denied to " + scope.name().toLowerCase() + " data for student " + studentId);
        }

        return new MeasurementAccessDecision(true, false, decision.historyFrom(), decision.historyUntil());
    }

    public MeasurementAccessDecision evaluateWriteAccess(UUID actorId, UUID studentId) {
        return evaluateWriteAccess(actorId, studentId, DataScope.BODY_METRICS);
    }

    public MeasurementAccessDecision evaluateWriteAccess(UUID actorId, UUID studentId, DataScope dataScope) {
        if (actorId == null || studentId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID and Student ID must not be null");
        }

        DataScope scope = dataScope != null ? dataScope : DataScope.BODY_METRICS;

        AccountStatus actorStatus = accounts.getAccountStatus(actorId).orElse(null);
        if (actorStatus != AccountStatus.ACTIVE) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Actor account is not active");
        }

        if (!students.hasProfile(studentId)) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_NOT_FOUND, "Student profile not found for ID: " + studentId);
        }

        boolean isSelf = actorId.equals(studentId);
        if (isSelf) {
            return new MeasurementAccessDecision(true, true, null, null);
        }

        AuthorityDecision decision = coaching.evaluateCurrent(
                new AuthorityRequest(actorId, studentId, scope, DataAccessLevel.CONTRIBUTE)
        );

        if (!decision.allowed()) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED,
                    "Write access denied to " + scope.name().toLowerCase() + " data for student " + studentId);
        }

        return new MeasurementAccessDecision(true, false, decision.historyFrom(), decision.historyUntil());
    }

    public MeasurementAccessDecision evaluatePhotoAccess(UUID actorId, UUID studentId) {
        return evaluateAccess(actorId, studentId, DataScope.PROGRESS_PHOTOS);
    }

    public MeasurementAccessDecision evaluatePhotoWriteAccess(UUID actorId, UUID studentId) {
        return evaluateWriteAccess(actorId, studentId, DataScope.PROGRESS_PHOTOS);
    }

    public record MeasurementAccessDecision(
            boolean allowed,
            boolean isSelf,
            Instant historyFrom,
            Instant historyUntil
    ) {
        public boolean isTimeRestricted() {
            return historyFrom != null || historyUntil != null;
        }
    }
}
