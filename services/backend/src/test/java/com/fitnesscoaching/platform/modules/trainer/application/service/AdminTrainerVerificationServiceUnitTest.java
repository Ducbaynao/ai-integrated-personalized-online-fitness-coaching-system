package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationAlreadyDecidedException;
import com.fitnesscoaching.platform.common.exception.TrainerApplicationNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
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
import org.springframework.security.access.AccessDeniedException;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTrainerVerificationServiceUnitTest {

    @Mock
    private TrainerApplicationPort trainerApplicationPort;

    @Mock
    private TrainerProfilePort trainerProfilePort;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private AuditService auditService;

    private final Instant fixedNow = Instant.parse("2026-09-23T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    private AdminTrainerVerificationService service;

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TRAINER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID APP_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void setUp() {
        service = new AdminTrainerVerificationService(
                trainerApplicationPort,
                trainerProfilePort,
                userAccountStatusQuery,
                userRoleQuery,
                auditService,
                clock
        );
    }

    private void mockActiveAdmin(UUID adminId) {
        when(userAccountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);
    }

    private TrainerApplication createPendingApp() {
        return new TrainerApplication(
                APP_ID,
                TRAINER_ID,
                TrainerVerificationStatus.PENDING,
                fixedNow.minusSeconds(3600),
                null,
                null,
                null,
                null,
                "Please verify me",
                List.of(UUID.randomUUID()),
                List.of(UUID.randomUUID()),
                fixedNow.minusSeconds(3600),
                fixedNow.minusSeconds(3600)
        );
    }

    // --- Authorization Tests ---

    @Test
    @DisplayName("Admin check: throws AccessDeniedException when admin account does not exist")
    void authorization_adminNotFound_throwsAccessDenied() {
        when(userAccountStatusQuery.getAccountStatus(ADMIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApplicationDetail(ADMIN_ID, APP_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User does not exist");
    }

    @Test
    @DisplayName("Admin check: throws AccountUnavailableException when admin account is not ACTIVE")
    void authorization_adminNotActive_throwsAccountUnavailable() {
        when(userAccountStatusQuery.getAccountStatus(ADMIN_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.getApplicationDetail(ADMIN_ID, APP_ID))
                .isInstanceOf(AccountUnavailableException.class)
                .hasMessageContaining("Account is not in ACTIVE state");
    }

    @Test
    @DisplayName("Admin check: throws AccessDeniedException when ADMIN role is not active in PostgreSQL")
    void authorization_adminRoleRevoked_throwsAccessDenied() {
        when(userAccountStatusQuery.getAccountStatus(ADMIN_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(ADMIN_ID, "ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> service.getApplicationDetail(ADMIN_ID, APP_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("active ADMIN role");
    }

    // --- Query Tests ---

    @Test
    @DisplayName("getApplications: defaults to PENDING when status is null, returns paginated applications")
    void getApplications_defaultPending_success() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication app = createPendingApp();

        when(trainerApplicationPort.findApplications(TrainerVerificationStatus.PENDING, 0L, 20))
                .thenReturn(List.of(app));
        when(trainerApplicationPort.countApplications(TrainerVerificationStatus.PENDING))
                .thenReturn(1L);

        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                ADMIN_ID, null, 0, 20);
        AdminTrainerApplicationPage result = service.getApplications(query);

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalItems()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("getApplications: rejects negative page index")
    void getApplications_negativePage_throwsValidationException() {
        mockActiveAdmin(ADMIN_ID);
        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                ADMIN_ID, TrainerVerificationStatus.PENDING, -1, 20);

        assertThatThrownBy(() -> service.getApplications(query))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("getApplications: rejects size greater than 100")
    void getApplications_excessiveSize_throwsValidationException() {
        mockActiveAdmin(ADMIN_ID);
        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                ADMIN_ID, TrainerVerificationStatus.PENDING, 0, 101);

        assertThatThrownBy(() -> service.getApplications(query))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("getApplications: page index producing offset overflow throws ApplicationValidationException")
    void getApplications_offsetOverflow_throwsValidationException() {
        mockActiveAdmin(ADMIN_ID);
        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                ADMIN_ID, TrainerVerificationStatus.PENDING, Integer.MAX_VALUE, 20
        );

        assertThatThrownBy(() -> service.getApplications(query))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Page index is too large");

        verify(trainerApplicationPort, never()).findApplications(any(), any(Long.class), any(Integer.class));
    }

    @Test
    @DisplayName("getApplicationDetail: returns application when found")
    void getApplicationDetail_success() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication app = createPendingApp();
        when(trainerApplicationPort.findById(APP_ID)).thenReturn(Optional.of(app));

        TrainerApplication result = service.getApplicationDetail(ADMIN_ID, APP_ID);
        assertThat(result.id()).isEqualTo(APP_ID);
        assertThat(result.status()).isEqualTo(TrainerVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("getApplicationDetail: throws 404 when application not found")
    void getApplicationDetail_notFound_throwsNotFound() {
        mockActiveAdmin(ADMIN_ID);
        when(trainerApplicationPort.findById(APP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApplicationDetail(ADMIN_ID, APP_ID))
                .isInstanceOf(TrainerApplicationNotFoundException.class)
                .hasMessageContaining("Trainer application not found");
    }

    // --- Decision: Approve Tests ---

    @Test
    @DisplayName("decideApplication: APPROVE updates application to VERIFIED, updates profile, records history, audits")
    void decide_approve_success() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication pendingApp = createPendingApp();
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(pendingApp));
        when(trainerApplicationPort.updateDecision(
                eq(APP_ID), eq(TrainerVerificationStatus.VERIFIED), eq(fixedNow), eq(ADMIN_ID),
                eq(null), eq("All credentials verified"), eq(fixedNow)
        )).thenReturn(1);

        TrainerApplication verifiedApp = new TrainerApplication(
                APP_ID, TRAINER_ID, TrainerVerificationStatus.VERIFIED,
                pendingApp.submittedAt(), fixedNow, ADMIN_ID, null, "All credentials verified",
                pendingApp.applicantNote(), pendingApp.certificateIds(), pendingApp.documentMediaIds(),
                pendingApp.createdAt(), fixedNow
        );
        when(trainerApplicationPort.findById(APP_ID)).thenReturn(Optional.of(verifiedApp));

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", null, "All credentials verified"
        );
        when(trainerProfilePort.updateVerificationDetails(any(), any(), any(), any(), any())).thenReturn(1);

        TrainerApplication result = service.decideApplication(command);

        assertThat(result.status()).isEqualTo(TrainerVerificationStatus.VERIFIED);
        assertThat(result.reviewedAt()).isEqualTo(fixedNow);
        assertThat(result.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.reviewNotes()).isEqualTo("All credentials verified");

        // Verify profile updated with verifiedAt and verifiedBy
        verify(trainerProfilePort).updateVerificationDetails(
                TRAINER_ID, TrainerVerificationStatus.VERIFIED, fixedNow, ADMIN_ID, fixedNow);

        // Verify status history recorded
        verify(trainerApplicationPort).recordStatusHistory(
                APP_ID, TrainerVerificationStatus.PENDING, TrainerVerificationStatus.VERIFIED,
                ADMIN_ID, "Application approved by admin", fixedNow);

        // Verify audit logged
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(captor.capture());
        AuditRecord audit = captor.getValue();
        assertThat(audit.action()).isEqualTo("TRAINER_APPLICATION_APPROVED");
        assertThat(audit.actorRole()).isEqualTo("ADMIN");
        assertThat(audit.actorUserId()).isEqualTo(ADMIN_ID);
        assertThat(audit.targetType()).isEqualTo("TRAINER_APPLICATION");
        assertThat(audit.targetId()).isEqualTo(APP_ID);
    }

    @Test
    @DisplayName("decideApplication: APPROVE with non-empty rejectionReason throws validation exception")
    void decide_approveWithRejectionReason_throwsValidationException() {
        mockActiveAdmin(ADMIN_ID);
        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", "Some reason", null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Rejection reason must not be provided");

        verify(trainerApplicationPort, never()).updateDecision(any(), any(), any(), any(), any(), any(), any());
    }

    // --- Decision: Reject Tests ---

    @Test
    @DisplayName("decideApplication: REJECT updates application to REJECTED, profile to REJECTED without verifiedAt, records history, audits")
    void decide_reject_success() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication pendingApp = createPendingApp();
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(pendingApp));
        when(trainerApplicationPort.updateDecision(
                eq(APP_ID), eq(TrainerVerificationStatus.REJECTED), eq(fixedNow), eq(ADMIN_ID),
                eq("Certificate expired"), eq("Internal note"), eq(fixedNow)
        )).thenReturn(1);

        TrainerApplication rejectedApp = new TrainerApplication(
                APP_ID, TRAINER_ID, TrainerVerificationStatus.REJECTED,
                pendingApp.submittedAt(), fixedNow, ADMIN_ID, "Certificate expired", "Internal note",
                pendingApp.applicantNote(), pendingApp.certificateIds(), pendingApp.documentMediaIds(),
                pendingApp.createdAt(), fixedNow
        );
        when(trainerApplicationPort.findById(APP_ID)).thenReturn(Optional.of(rejectedApp));

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "REJECT", "Certificate expired", "Internal note"
        );
        when(trainerProfilePort.updateVerificationDetails(any(), any(), any(), any(), any())).thenReturn(1);

        TrainerApplication result = service.decideApplication(command);

        assertThat(result.status()).isEqualTo(TrainerVerificationStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("Certificate expired");
        assertThat(result.reviewNotes()).isEqualTo("Internal note");

        // Verify profile updated without verifiedAt / verifiedBy
        verify(trainerProfilePort).updateVerificationDetails(
                TRAINER_ID, TrainerVerificationStatus.REJECTED, null, null, fixedNow);

        // Verify status history recorded with rejection reason
        verify(trainerApplicationPort).recordStatusHistory(
                APP_ID, TrainerVerificationStatus.PENDING, TrainerVerificationStatus.REJECTED,
                ADMIN_ID, "Certificate expired", fixedNow);

        // Verify audit logged
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(captor.capture());
        AuditRecord audit = captor.getValue();
        assertThat(audit.action()).isEqualTo("TRAINER_APPLICATION_REJECTED");
    }

    @Test
    @DisplayName("decideApplication: REJECT with missing or blank rejectionReason throws validation exception")
    void decide_rejectMissingReason_throwsValidationException() {
        mockActiveAdmin(ADMIN_ID);
        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "REJECT", "   ", null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Rejection reason is required");
    }

    // --- Decision: Lifecycle & Concurrency Tests ---

    @Test
    @DisplayName("decideApplication: application not in PENDING status throws TrainerApplicationAlreadyDecidedException")
    void decide_notPending_throwsAlreadyDecided() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication verifiedApp = new TrainerApplication(
                APP_ID, TRAINER_ID, TrainerVerificationStatus.VERIFIED,
                fixedNow.minusSeconds(3600), fixedNow.minusSeconds(100), ADMIN_ID, null, null,
                null, List.of(), List.of(), fixedNow.minusSeconds(3600), fixedNow.minusSeconds(100)
        );
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(verifiedApp));

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", null, null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(TrainerApplicationAlreadyDecidedException.class)
                .hasMessageContaining("already been decided");

        verify(trainerApplicationPort, never()).updateDecision(any(), any(), any(), any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("decideApplication: updateDecision returns 0 (concurrent decision) throws TrainerApplicationAlreadyDecidedException")
    void decide_concurrentDecisionZeroRows_throwsAlreadyDecided() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication pendingApp = createPendingApp();
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(pendingApp));
        when(trainerApplicationPort.updateDecision(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", null, null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(TrainerApplicationAlreadyDecidedException.class);

        verify(trainerProfilePort, never()).updateVerificationDetails(any(), any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("decideApplication: audit service failure propagates exception (triggering transaction rollback)")
    void decide_auditFailure_propagatesException() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication pendingApp = createPendingApp();
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(pendingApp));
        when(trainerApplicationPort.updateDecision(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(trainerProfilePort.updateVerificationDetails(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new RuntimeException("Audit DB unreachable")).when(auditService).recordAudit(any());

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", null, null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Audit DB unreachable");
    }

    @Test
    @DisplayName("decideApplication: profile update returns 0 (profile modified/not PENDING) throws InvalidLifecycleTransitionException")
    void decide_profileNotPendingZeroRows_throwsInvalidLifecycleTransition() {
        mockActiveAdmin(ADMIN_ID);
        TrainerApplication pendingApp = createPendingApp();
        when(trainerApplicationPort.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(pendingApp));
        when(trainerApplicationPort.updateDecision(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(trainerProfilePort.updateVerificationDetails(any(), any(), any(), any(), any()))
                .thenReturn(0);

        DecideTrainerApplicationCommand command = new DecideTrainerApplicationCommand(
                ADMIN_ID, APP_ID, "APPROVE", null, null
        );

        assertThatThrownBy(() -> service.decideApplication(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Trainer profile is not in PENDING state");

        verify(trainerApplicationPort, never()).recordStatusHistory(any(), any(), any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }
}
