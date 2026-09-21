package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyActiveException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.media.application.port.in.MediaQueryPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerCertificatePort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainerApplicationServiceUnitTest {

    @Mock
    private TrainerProfilePort trainerProfilePort;

    @Mock
    private TrainerApplicationPort trainerApplicationPort;

    @Mock
    private TrainerCertificatePort trainerCertificatePort;

    @Mock
    private MediaQueryPort mediaQueryPort;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private AuditService auditService;

    private final Instant fixedNow = Instant.parse("2026-09-22T08:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    private TrainerApplicationService service;

    private static final UUID TRAINER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CERT_ID_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEDIA_ID_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        service = new TrainerApplicationService(
                trainerProfilePort,
                trainerApplicationPort,
                trainerCertificatePort,
                mediaQueryPort,
                userAccountStatusQuery,
                userRoleQuery,
                auditService,
                clock
        );
    }

    private TrainerProfile createTestProfile(TrainerVerificationStatus status, boolean active) {
        return new TrainerProfile(
                TRAINER_ID,
                "john-trainer",
                "Trainer bio",
                BigDecimal.valueOf(5),
                true,
                status,
                TrainerActivityStatus.ACTIVE,
                null,
                null,
                active,
                fixedNow.minusSeconds(3600),
                fixedNow.minusSeconds(3600)
        );
    }

    @Test
    @DisplayName("Submit: success creates pending application, updates profile verification status, and audits")
    void submitApplication_success() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));
        when(trainerApplicationPort.hasActiveApplication(TRAINER_ID)).thenReturn(false);
        when(trainerCertificatePort.allCertificatesExistAndBelongToTrainer(List.of(CERT_ID_1), TRAINER_ID)).thenReturn(true);
        when(mediaQueryPort.allMediaExistAndOwnedBy(List.of(MEDIA_ID_1), TRAINER_ID)).thenReturn(true);
        when(trainerApplicationPort.save(any(TrainerApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(CERT_ID_1),
                List.of(MEDIA_ID_1),
                "Application note for admin."
        );

        TrainerApplication app = service.submitApplication(command);

        assertThat(app.status()).isEqualTo(TrainerVerificationStatus.PENDING);
        assertThat(app.trainerId()).isEqualTo(TRAINER_ID);
        assertThat(app.submittedAt()).isEqualTo(fixedNow);
        assertThat(app.certificateIds()).containsExactly(CERT_ID_1);
        assertThat(app.documentMediaIds()).containsExactly(MEDIA_ID_1);
        assertThat(app.applicantNote()).isEqualTo("Application note for admin.");

        // Invariant: canCoach remains false
        CoachingEligibility eligibility = CoachingEligibility.evaluate(app.status(), TrainerActivityStatus.ACTIVE);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.blockingReasons()).contains("VERIFICATION_PENDING");

        verify(trainerProfilePort).updateVerificationStatus(TRAINER_ID, TrainerVerificationStatus.PENDING, fixedNow);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());
        AuditRecord audit = auditCaptor.getValue();
        assertThat(audit.actorUserId()).isEqualTo(TRAINER_ID);
        assertThat(audit.actorRole()).isEqualTo("TRAINER");
        assertThat(audit.action()).isEqualTo("TRAINER_APPLICATION_SUBMITTED");
        assertThat(audit.targetType()).isEqualTo("TRAINER_APPLICATION");
        assertThat(audit.targetId()).isEqualTo(app.id());
    }

    @Test
    @DisplayName("Submit: throws TrainerProfileNotFoundException when trainer profile does not exist")
    void submitApplication_missingTrainerProfile() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.empty());

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(TrainerProfileNotFoundException.class)
                .hasMessageContaining("Trainer profile not found");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws TrainerApplicationAlreadyActiveException when active application already exists")
    void submitApplication_alreadyActive() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));
        when(trainerApplicationPort.hasActiveApplication(TRAINER_ID)).thenReturn(true);

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(TrainerApplicationAlreadyActiveException.class)
                .hasMessageContaining("already exists");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws TrainerApplicationAlreadyActiveException when profile verification is already PENDING")
    void submitApplication_profilePending() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.PENDING, true)));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(TrainerApplicationAlreadyActiveException.class);

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws InvalidLifecycleTransitionException when profile is REJECTED")
    void submitApplication_rejectedProfile_throwsInvalidLifecycleTransition() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.REJECTED, true)));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Cannot submit application when trainer profile verification status is REJECTED");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws InvalidLifecycleTransitionException when profile is VERIFIED")
    void submitApplication_verifiedProfile_throwsInvalidLifecycleTransition() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.VERIFIED, true)));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Cannot submit application when trainer profile verification status is VERIFIED");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws InvalidLifecycleTransitionException when profile is SUSPENDED")
    void submitApplication_suspendedProfile_throwsInvalidLifecycleTransition() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.SUSPENDED, true)));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Cannot submit application when trainer profile verification status is SUSPENDED");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws ApplicationValidationException when applicantNote exceeds 2000 chars")
    void submitApplication_applicantNoteTooLong() {
        String longNote = "a".repeat(2001);
        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                longNote
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("2000 characters");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws ApplicationValidationException when duplicate certificateIds provided")
    void submitApplication_duplicateCertificateIds() {
        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(CERT_ID_1, CERT_ID_1),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duplicate certificate IDs");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws ApplicationValidationException when duplicate documentMediaIds provided")
    void submitApplication_duplicateDocumentMediaIds() {
        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(MEDIA_ID_1, MEDIA_ID_1),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duplicate document media IDs");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws ApplicationValidationException when certificate does not exist or belong to trainer")
    void submitApplication_certificateNotFoundOrNotOwned() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));
        when(trainerApplicationPort.hasActiveApplication(TRAINER_ID)).thenReturn(false);
        when(trainerCertificatePort.allCertificatesExistAndBelongToTrainer(List.of(CERT_ID_1), TRAINER_ID)).thenReturn(false);

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(CERT_ID_1),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("certificates do not exist or do not belong");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws ApplicationValidationException when media does not exist or is unauthorized")
    void submitApplication_mediaNotFoundOrUnauthorized() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));
        when(trainerApplicationPort.hasActiveApplication(TRAINER_ID)).thenReturn(false);
        when(mediaQueryPort.allMediaExistAndOwnedBy(List.of(MEDIA_ID_1), TRAINER_ID)).thenReturn(false);

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(MEDIA_ID_1),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("media files do not exist or are not authorized");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws TrainerCapabilityUnavailableException when trainer profile is inactive")
    void submitApplication_inactiveProfile() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, false)));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(TrainerCapabilityUnavailableException.class)
                .hasMessageContaining("inactive");

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws AccountUnavailableException when user account is not active")
    void submitApplication_accountSuspended() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(AccountUnavailableException.class);

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("Submit: throws UserNotFoundException when user does not exist")
    void submitApplication_userNotFound() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.empty());

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                List.of(),
                List.of(),
                null
        );

        assertThatThrownBy(() -> service.submitApplication(command))
                .isInstanceOf(UserNotFoundException.class);

        verify(trainerApplicationPort, never()).save(any());
    }

    @Test
    @DisplayName("getCurrentApplication: returns current application when found")
    void getCurrentApplication_found() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));

        TrainerApplication expected = new TrainerApplication(
                UUID.randomUUID(),
                TRAINER_ID,
                TrainerVerificationStatus.PENDING,
                fixedNow,
                null,
                null,
                null,
                "Note",
                List.of(),
                List.of(),
                fixedNow,
                fixedNow
        );
        when(trainerApplicationPort.findCurrentByTrainerId(TRAINER_ID)).thenReturn(Optional.of(expected));

        TrainerApplication result = service.getCurrentApplication(TRAINER_ID);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(expected.id());
        assertThat(result.status()).isEqualTo(TrainerVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("getCurrentApplication: throws TrainerApplicationNotFoundException when no application exists")
    void getCurrentApplication_notFound() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, true)));
        when(trainerApplicationPort.findCurrentByTrainerId(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentApplication(TRAINER_ID))
                .isInstanceOf(TrainerApplicationNotFoundException.class)
                .hasMessageContaining("No trainer application found");
    }

    @Test
    @DisplayName("getCurrentApplication: throws TrainerProfileNotFoundException when trainer profile does not exist")
    void getCurrentApplication_missingTrainerProfile() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentApplication(TRAINER_ID))
                .isInstanceOf(TrainerProfileNotFoundException.class)
                .hasMessageContaining("Trainer profile not found");
    }

    @Test
    @DisplayName("getCurrentApplication: throws TrainerCapabilityUnavailableException when trainer profile is inactive")
    void getCurrentApplication_inactiveTrainerProfile() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(TRAINER_ID)).thenReturn(Optional.of(createTestProfile(TrainerVerificationStatus.NOT_SUBMITTED, false)));

        assertThatThrownBy(() -> service.getCurrentApplication(TRAINER_ID))
                .isInstanceOf(TrainerCapabilityUnavailableException.class)
                .hasMessageContaining("Trainer profile is inactive");
    }

    @Test
    @DisplayName("getCurrentApplication: throws TrainerCapabilityUnavailableException when user has no TRAINER role")
    void getCurrentApplication_missingTrainerRole() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(TRAINER_ID, "TRAINER")).thenReturn(false);

        assertThatThrownBy(() -> service.getCurrentApplication(TRAINER_ID))
                .isInstanceOf(TrainerCapabilityUnavailableException.class)
                .hasMessageContaining("Trainer capability is not active");
    }

    @Test
    @DisplayName("getCurrentApplication: throws AccountUnavailableException when user account is suspended")
    void getCurrentApplication_accountSuspended() {
        when(userAccountStatusQuery.getAccountStatus(TRAINER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.getCurrentApplication(TRAINER_ID))
                .isInstanceOf(AccountUnavailableException.class);
    }

    @Test
    @DisplayName("Record immutability: SubmitTrainerApplicationCommand defensively copies and is unmodifiable")
    void submitTrainerApplicationCommand_isImmutable() {
        java.util.List<UUID> mutableCerts = new java.util.ArrayList<>();
        mutableCerts.add(CERT_ID_1);
        java.util.List<UUID> mutableDocs = new java.util.ArrayList<>();
        mutableDocs.add(MEDIA_ID_1);

        SubmitTrainerApplicationCommand command = new SubmitTrainerApplicationCommand(
                TRAINER_ID,
                mutableCerts,
                mutableDocs,
                "Note"
        );

        // Mutating caller list does not affect command
        UUID extraCert = UUID.randomUUID();
        mutableCerts.add(extraCert);
        assertThat(command.certificateIds()).hasSize(1).doesNotContain(extraCert);

        // Mutating returned list directly throws UnsupportedOperationException
        assertThatThrownBy(() -> command.certificateIds().add(extraCert))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.documentMediaIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Record immutability: TrainerApplication defensively copies and is unmodifiable")
    void trainerApplication_isImmutable() {
        java.util.List<UUID> mutableCerts = new java.util.ArrayList<>();
        mutableCerts.add(CERT_ID_1);
        java.util.List<UUID> mutableDocs = new java.util.ArrayList<>();
        mutableDocs.add(MEDIA_ID_1);

        TrainerApplication app = new TrainerApplication(
                UUID.randomUUID(),
                TRAINER_ID,
                TrainerVerificationStatus.PENDING,
                fixedNow,
                null,
                null,
                null,
                "Note",
                mutableCerts,
                mutableDocs,
                fixedNow,
                fixedNow
        );

        // Mutating caller list does not affect application
        UUID extraCert = UUID.randomUUID();
        mutableCerts.add(extraCert);
        assertThat(app.certificateIds()).hasSize(1).doesNotContain(extraCert);

        // Mutating returned list directly throws UnsupportedOperationException
        assertThatThrownBy(() -> app.certificateIds().add(extraCert))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> app.documentMediaIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
