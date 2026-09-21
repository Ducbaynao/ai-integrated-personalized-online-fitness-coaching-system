package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerSlugAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.model.PatchField;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleUseCase;
import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainerProfileServiceUnitTest {

    @Mock
    private TrainerProfilePort trainerProfilePort;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleUseCase userRoleUseCase;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private AuditService auditService;

    private final Instant fixedNow = Instant.parse("2026-09-21T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    private TrainerProfileService service;
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        service = new TrainerProfileService(
                trainerProfilePort,
                userAccountStatusQuery,
                userRoleUseCase,
                userRoleQuery,
                auditService,
                clock
        );
    }

    @Test
    @DisplayName("createTrainerProfile: activates TRAINER role, saves profile, and records audit with actorRole USER")
    void create_activatesRoleAndAuditsWithUserActorRole() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(trainerProfilePort.existsByPublicSlug("coach-john")).thenReturn(false);
        when(userRoleUseCase.activateRole(USER_ID, "TRAINER", USER_ID)).thenReturn(RoleActivationResult.ASSIGNED);
        when(trainerProfilePort.save(any(TrainerProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID,
                "coach-john",
                "Certified trainer with 5 years experience.",
                new BigDecimal("5.50"),
                true
        );

        TrainerProfile created = service.createTrainerProfile(command);

        assertThat(created.userId()).isEqualTo(USER_ID);
        assertThat(created.publicSlug()).isEqualTo("coach-john");
        assertThat(created.bio()).isEqualTo("Certified trainer with 5 years experience.");
        assertThat(created.yearsExperience()).isEqualTo(new BigDecimal("5.50"));
        assertThat(created.isAcceptingStudents()).isTrue();
        assertThat(created.verificationStatus()).isEqualTo(TrainerVerificationStatus.NOT_SUBMITTED);
        assertThat(created.activityStatus()).isEqualTo(TrainerActivityStatus.ACTIVE);
        assertThat(created.isActive()).isTrue();
        assertThat(created.createdAt()).isEqualTo(fixedNow);
        assertThat(created.updatedAt()).isEqualTo(fixedNow);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());
        AuditRecord audit = auditCaptor.getValue();
        assertThat(audit.actorUserId()).isEqualTo(USER_ID);
        assertThat(audit.actorRole()).isEqualTo("USER");
        assertThat(audit.action()).isEqualTo("TRAINER_PROFILE_CREATED");
        assertThat(audit.targetType()).isEqualTo("TRAINER_PROFILE");
        assertThat(audit.targetId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("createTrainerProfile: idempotent role assignment when TRAINER role already active")
    void create_alreadyActiveRole_idempotent() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(userRoleUseCase.activateRole(USER_ID, "TRAINER", USER_ID)).thenReturn(RoleActivationResult.ALREADY_ACTIVE);
        when(trainerProfilePort.save(any(TrainerProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID,
                null,
                null,
                null,
                false
        );

        TrainerProfile created = service.createTrainerProfile(command);
        assertThat(created.userId()).isEqualTo(USER_ID);
        assertThat(created.isAcceptingStudents()).isFalse();
    }

    @Test
    @DisplayName("createTrainerProfile: duplicate profile throws TrainerProfileAlreadyExistsException")
    void create_duplicateProfile_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.existsByUserId(USER_ID)).thenReturn(true);

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, "my-slug", null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(TrainerProfileAlreadyExistsException.class)
                .hasMessageContaining("Trainer profile already exists");

        verify(trainerProfilePort, never()).save(any());
        verify(userRoleUseCase, never()).activateRole(any(), any(), any());
    }

    @Test
    @DisplayName("createTrainerProfile: duplicate slug throws TrainerSlugAlreadyExistsException")
    void create_duplicateSlug_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(trainerProfilePort.existsByPublicSlug("taken-slug")).thenReturn(true);

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, "taken-slug", null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(TrainerSlugAlreadyExistsException.class)
                .hasMessageContaining("taken-slug");

        verify(userRoleUseCase, never()).activateRole(any(), any(), any());
    }

    @Test
    @DisplayName("createTrainerProfile: revoked capability throws TrainerCapabilityRevokedException")
    void create_revokedCapability_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(userRoleUseCase.activateRole(USER_ID, "TRAINER", USER_ID)).thenReturn(RoleActivationResult.REVOKED);

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(TrainerCapabilityRevokedException.class)
                .hasMessageContaining("revoked");

        verify(trainerProfilePort, never()).save(any());
    }

    @Test
    @DisplayName("createTrainerProfile: suspended account throws AccountUnavailableException")
    void create_suspendedAccount_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(AccountUnavailableException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("createTrainerProfile: user not found throws UserNotFoundException")
    void create_userNotFound_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.empty());

        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("createTrainerProfile: invalid slug pattern throws ApplicationValidationException")
    void create_invalidSlug_throwsValidationException() {
        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, "-invalid-slug", null, null, false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("createTrainerProfile: negative years experience throws ApplicationValidationException")
    void create_negativeYears_throwsValidationException() {
        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, new BigDecimal("-1.0"), false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("createTrainerProfile: years experience > 99.99 throws ApplicationValidationException")
    void create_excessiveYears_throwsValidationException() {
        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, new BigDecimal("100.00"), false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("createTrainerProfile: years experience with scale > 2 throws ApplicationValidationException")
    void create_excessiveScale_throwsValidationException() {
        CreateTrainerProfileCommand command = new CreateTrainerProfileCommand(
                USER_ID, null, null, new BigDecimal("5.555"), false);

        assertThatThrownBy(() -> service.createTrainerProfile(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("getTrainerProfile: success when active account and active TRAINER role")
    void get_success() {
        TrainerProfile sample = new TrainerProfile(
                USER_ID,
                "coach-pro",
                "Bio",
                new BigDecimal("3.0"),
                true,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                fixedNow,
                fixedNow
        );
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(sample));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);

        TrainerProfile result = service.getTrainerProfile(USER_ID);
        assertThat(result).isEqualTo(sample);
        assertThat(result.getCoachingEligibility().eligible()).isFalse();
        assertThat(result.getCoachingEligibility().blockingReasons()).contains("APPLICATION_NOT_SUBMITTED");
    }

    @Test
    @DisplayName("getTrainerProfile: not found throws TrainerProfileNotFoundException")
    void get_notFound_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTrainerProfile(USER_ID))
                .isInstanceOf(TrainerProfileNotFoundException.class);
    }

    @Test
    @DisplayName("getTrainerProfile: role not active throws TrainerCapabilityUnavailableException")
    void get_roleInactive_throwsException() {
        TrainerProfile sample = new TrainerProfile(
                USER_ID, null, null, null, false,
                TrainerVerificationStatus.NOT_SUBMITTED, TrainerActivityStatus.ACTIVE,
                null, null, true, fixedNow, fixedNow
        );
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(sample));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(false);

        assertThatThrownBy(() -> service.getTrainerProfile(USER_ID))
                .isInstanceOf(TrainerCapabilityUnavailableException.class);
    }

    @Test
    @DisplayName("updateTrainerProfile: partial update only modifies specified fields and leaves omitted untouched")
    void update_partialSuccess() {
        TrainerProfile existing = new TrainerProfile(
                USER_ID,
                "coach-old",
                "Old bio",
                new BigDecimal("4.00"),
                true,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                fixedNow.minusSeconds(3600),
                fixedNow.minusSeconds(3600)
        );

        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.update(any(TrainerProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        // Update bio and acceptingStudents, omit publicSlug and yearsExperience
        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.of("Updated bio description"),
                PatchField.omitted(),
                PatchField.of(false)
        );

        TrainerProfile updated = service.updateTrainerProfile(command);

        assertThat(updated.publicSlug()).isEqualTo("coach-old");
        assertThat(updated.bio()).isEqualTo("Updated bio description");
        assertThat(updated.yearsExperience()).isEqualTo(new BigDecimal("4.00"));
        assertThat(updated.isAcceptingStudents()).isFalse();
        assertThat(updated.updatedAt()).isEqualTo(fixedNow);
    }

    @Test
    @DisplayName("updateTrainerProfile: nullable fields set to null when null is specified")
    void update_clearsNullableFields_whenNullSpecified() {
        TrainerProfile existing = new TrainerProfile(
                USER_ID,
                "coach-john",
                "Old bio",
                new BigDecimal("4.00"),
                true,
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                true,
                fixedNow.minusSeconds(3600),
                fixedNow.minusSeconds(3600)
        );

        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.update(any(TrainerProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                USER_ID,
                PatchField.ofNull(),
                PatchField.ofNull(),
                PatchField.ofNull(),
                PatchField.omitted()
        );

        TrainerProfile updated = service.updateTrainerProfile(command);

        assertThat(updated.publicSlug()).isNull();
        assertThat(updated.bio()).isNull();
        assertThat(updated.yearsExperience()).isNull();
        assertThat(updated.isAcceptingStudents()).isTrue(); // omitted remains untouched!
    }

    @Test
    @DisplayName("updateTrainerProfile: duplicate slug on update throws TrainerSlugAlreadyExistsException")
    void update_duplicateSlug_throwsException() {
        TrainerProfile existing = new TrainerProfile(
                USER_ID, "my-old-slug", null, null, false,
                TrainerVerificationStatus.NOT_SUBMITTED, TrainerActivityStatus.ACTIVE,
                null, null, true, fixedNow, fixedNow
        );

        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.existsByPublicSlugAndUserIdNot("other-coach", USER_ID)).thenReturn(true);

        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                USER_ID,
                PatchField.of("other-coach"),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateTrainerProfile(command))
                .isInstanceOf(TrainerSlugAlreadyExistsException.class)
                .hasMessageContaining("other-coach");
    }

    @Test
    @DisplayName("updateTrainerProfile: keeping same slug for own user is allowed")
    void update_sameSlug_allowed() {
        TrainerProfile existing = new TrainerProfile(
                USER_ID, "my-slug", null, null, false,
                TrainerVerificationStatus.NOT_SUBMITTED, TrainerActivityStatus.ACTIVE,
                null, null, true, fixedNow, fixedNow
        );

        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.existsByPublicSlugAndUserIdNot("my-slug", USER_ID)).thenReturn(false);
        when(trainerProfilePort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                USER_ID,
                PatchField.of("my-slug"),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        TrainerProfile updated = service.updateTrainerProfile(command);
        assertThat(updated.publicSlug()).isEqualTo("my-slug");
    }

    @Test
    @DisplayName("updateTrainerProfile: no fields specified throws ApplicationValidationException")
    void update_noFieldsSpecified_throwsException() {
        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateTrainerProfile(command))
                .isInstanceOf(ApplicationValidationException.class);
    }
}
