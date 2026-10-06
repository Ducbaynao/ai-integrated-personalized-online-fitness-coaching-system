package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingMobileReadUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerAvailabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerDirectoryQuery;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CoachingMobileReadService implements CoachingMobileReadUseCase {
    private final CoachingStore store;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final UserDirectoryQuery users;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final TrainerAvailabilityQuery availability;
    private final TrainerDirectoryQuery directory;

    public CoachingMobileReadService(CoachingStore store, UserAccountStatusQuery accounts,
                                     UserRoleQuery roles, UserDirectoryQuery users,
                                     StudentCapabilityQuery students,
                                     TrainerCoachingEligibilityQuery trainers,
                                     TrainerAvailabilityQuery availability,
                                     TrainerDirectoryQuery directory) {
        this.store = store;
        this.accounts = accounts;
        this.roles = roles;
        this.users = users;
        this.students = students;
        this.trainers = trainers;
        this.availability = availability;
        this.directory = directory;
    }

    @Override
    @Transactional(readOnly = true)
    public TrainerDirectoryPage discoverTrainers(UUID actorId, String displayNameQuery, int page, int size) {
        verifyStudent(actorId);
        return directory.searchDiscoverableTrainers(displayNameQuery, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDisplaySummary lookupStudent(UUID actorId, String email) {
        verifyTrainer(actorId);
        if (!availability.isAcceptingStudents(actorId)) {
            throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        UserDisplaySummary candidate = users.findActiveRoleMemberByEmail(normalizedEmail, "STUDENT")
                .filter(value -> students.hasProfile(value.userId()))
                .filter(value -> !value.userId().equals(actorId))
                .filter(value -> !store.currentPair(value.userId(), actorId))
                .orElseThrow(CoachingMobileReadService::counterpartyNotFound);
        return candidate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Relationship> relationships(UUID actorId, int page, int size) {
        verifyAccount(actorId);
        boolean studentCapability = roles.hasActiveRole(actorId, "STUDENT") && students.hasProfile(actorId);
        boolean trainerCapability = roles.hasActiveRole(actorId, "TRAINER");
        boolean trainerEligible = trainerCapability && trainers.canCoach(actorId);
        if (!studentCapability && !trainerCapability) {
            throw failure(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return store.relationships(actorId, studentCapability, trainerCapability, trainerEligible,
                size, page * size);
    }

    private void verifyAccount(UUID actorId) {
        if (accounts.getAccountStatus(actorId).orElse(null) != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Account unavailable");
        }
    }

    private void verifyStudent(UUID actorId) {
        verifyAccount(actorId);
        if (!roles.hasActiveRole(actorId, "STUDENT") || !students.hasProfile(actorId)) {
            throw new StudentCapabilityUnavailableException("Student capability unavailable");
        }
    }

    private void verifyTrainer(UUID actorId) {
        verifyAccount(actorId);
        if (!roles.hasActiveRole(actorId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability unavailable");
        }
        if (!trainers.canCoach(actorId)) {
            throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
        }
    }

    private static CoachingFailure counterpartyNotFound() {
        return failure(HttpStatus.NOT_FOUND, "COACHING_COUNTERPARTY_NOT_FOUND");
    }

    private static CoachingFailure failure(HttpStatus status, String code) {
        return new CoachingFailure(status, code);
    }
}
