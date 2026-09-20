package com.fitnesscoaching.platform.modules.student.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityRevokedException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentProfileAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.StudentProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.SystemRoleNotFoundException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.student.application.model.PatchField;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.out.StudentProfilePort;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
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
import java.time.LocalDate;
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
class StudentProfileServiceUnitTest {

    @Mock
    private StudentProfilePort studentProfilePort;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleUseCase userRoleUseCase;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private AuditService auditService;

    private final Instant fixedNow = Instant.parse("2026-09-20T12:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    private StudentProfileService service;
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        service = new StudentProfileService(
                studentProfilePort,
                userAccountStatusQuery,
                userRoleUseCase,
                userRoleQuery,
                auditService,
                clock
        );
    }

    @Test
    @DisplayName("createStudentProfile: activates STUDENT role and logs audit with actorRole USER")
    void create_activatesRoleAndAuditsWithUserActorRole() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleUseCase.activateRole(USER_ID, "STUDENT", USER_ID)).thenReturn(RoleActivationResult.ASSIGNED);
        when(studentProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(studentProfilePort.save(any(StudentProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID,
                LocalDate.of(1998, 3, 15),
                Gender.MALE,
                TrainingExperienceLevel.BEGINNER,
                BigDecimal.ZERO,
                3,
                45,
                true
        );

        StudentProfile result = service.createStudentProfile(command);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.onboardingCompletedAt()).isEqualTo(fixedNow);

        verify(userRoleUseCase).activateRole(USER_ID, "STUDENT", USER_ID);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());
        AuditRecord record = auditCaptor.getValue();
        assertThat(record.actorRole()).isEqualTo("USER");
        assertThat(record.actorUserId()).isEqualTo(USER_ID);
        assertThat(record.action()).isEqualTo("STUDENT_PROFILE_CREATED");
    }

    @Test
    @DisplayName("createStudentProfile: default onboardingCompleted false leaves onboardingCompletedAt null")
    void create_onboardingFalse_leavesTimestampNull() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleUseCase.activateRole(USER_ID, "STUDENT", USER_ID)).thenReturn(RoleActivationResult.ALREADY_ACTIVE);
        when(studentProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(studentProfilePort.save(any(StudentProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        StudentProfile result = service.createStudentProfile(command);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.onboardingCompletedAt()).isNull();

        verify(userRoleUseCase).activateRole(USER_ID, "STUDENT", USER_ID);
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("createStudentProfile: maps RoleActivationResult.REVOKED to StudentCapabilityRevokedException and halts")
    void create_revokedRole_translatesToStudentCapabilityRevokedException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(studentProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(userRoleUseCase.activateRole(USER_ID, "STUDENT", USER_ID))
                .thenReturn(RoleActivationResult.REVOKED);

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(StudentCapabilityRevokedException.class)
                .hasMessageContaining("revoked");

        verify(studentProfilePort, never()).save(any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("createStudentProfile: system role missing throws SystemRoleNotFoundException and halts")
    void create_systemRoleMissing_throwsExceptionAndRollsBack() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(studentProfilePort.existsByUserId(USER_ID)).thenReturn(false);
        when(userRoleUseCase.activateRole(USER_ID, "STUDENT", USER_ID))
                .thenThrow(new SystemRoleNotFoundException("System role 'STUDENT' does not exist."));

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(SystemRoleNotFoundException.class)
                .hasMessageContaining("STUDENT");

        verify(studentProfilePort, never()).save(any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("createStudentProfile: suspended account throws AccountUnavailableException")
    void create_suspendedAccount_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(AccountUnavailableException.class);

        verify(studentProfilePort, never()).save(any());
        verify(userRoleUseCase, never()).activateRole(any(), any(), any());
    }

    @Test
    @DisplayName("createStudentProfile: duplicate profile throws StudentProfileAlreadyExistsException")
    void create_duplicate_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(studentProfilePort.existsByUserId(USER_ID)).thenReturn(true);

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(StudentProfileAlreadyExistsException.class);

        verify(studentProfilePort, never()).save(any());
        verify(userRoleUseCase, never()).activateRole(any(), any(), any());
    }

    @Test
    @DisplayName("createStudentProfile: user not found throws UserNotFoundException")
    void create_userNotFound_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.empty());

        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects future dateOfBirth")
    void create_futureDob_throwsValidationException() {
        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID,
                LocalDate.now(clock).plusDays(1),
                null, null, null, null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Date of birth cannot be in the future");
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects negative trainingExperienceMonths")
    void create_negativeMonths_throwsValidationException() {
        CreateStudentProfileCommand command = new CreateStudentProfileCommand(
                USER_ID,
                null, null, null,
                new BigDecimal("-0.1"),
                null, null, false
        );

        assertThatThrownBy(() -> service.createStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Training experience months cannot be negative");
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects invalid availableDaysPerWeek")
    void create_invalidDays_throwsValidationException() {
        CreateStudentProfileCommand cmdLow = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, 0, null, false
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmdLow))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Available days per week must be between 1 and 7");

        CreateStudentProfileCommand cmdHigh = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, 8, null, false
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmdHigh))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Available days per week must be between 1 and 7");
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects invalid preferredSessionMinutes")
    void create_invalidMinutes_throwsValidationException() {
        CreateStudentProfileCommand cmdLow = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, 4, false
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmdLow))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Preferred session minutes must be between 5 and 480");

        CreateStudentProfileCommand cmdHigh = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, 481, false
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmdHigh))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Preferred session minutes must be between 5 and 480");
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects null onboardingCompleted")
    void create_nullOnboardingCompleted_throwsValidationException() {
        CreateStudentProfileCommand cmd = new CreateStudentProfileCommand(
                USER_ID, null, null, null, null, null, null, null
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmd))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("onboardingCompleted cannot be null");
    }

    @Test
    @DisplayName("createStudentProfile: application validation rejects null command or null userId")
    void create_nullCommandOrUserId_throwsValidationException() {
        assertThatThrownBy(() -> service.createStudentProfile(null))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Command cannot be null");

        CreateStudentProfileCommand cmdNullUser = new CreateStudentProfileCommand(
                null, null, null, null, null, null, null, false
        );
        assertThatThrownBy(() -> service.createStudentProfile(cmdNullUser))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("User ID cannot be null");
    }

    @Test
    @DisplayName("getStudentProfile: account unavailable throws AccountUnavailableException")
    void get_accountUnavailable_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.getStudentProfile(USER_ID))
                .isInstanceOf(AccountUnavailableException.class);
    }

    @Test
    @DisplayName("getStudentProfile: non-existent profile throws StudentProfileNotFoundException")
    void get_notFound_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStudentProfile(USER_ID))
                .isInstanceOf(StudentProfileNotFoundException.class);
    }

    @Test
    @DisplayName("getStudentProfile: profile exists but STUDENT role absent throws StudentCapabilityUnavailableException")
    void get_profileExists_roleAbsent_throwsStudentCapabilityUnavailableException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                null, fixedNow.minusSeconds(3600), fixedNow.minusSeconds(3600)
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(false);

        assertThatThrownBy(() -> service.getStudentProfile(USER_ID))
                .isInstanceOf(StudentCapabilityUnavailableException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("getStudentProfile: profile exists and STUDENT role active returns profile")
    void get_profileExists_roleActive_returnsProfile() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                null, fixedNow.minusSeconds(3600), fixedNow.minusSeconds(3600)
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(true);

        StudentProfile profile = service.getStudentProfile(USER_ID);
        assertThat(profile).isNotNull();
        assertThat(profile.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("updateStudentProfile: account unavailable throws AccountUnavailableException")
    void update_accountUnavailable_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(), PatchField.omitted(), PatchField.omitted(),
                PatchField.omitted(), PatchField.of(4), PatchField.omitted(), PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(AccountUnavailableException.class);
    }

    @Test
    @DisplayName("updateStudentProfile: profile not found throws StudentProfileNotFoundException")
    void update_profileNotFound_throwsException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.empty());

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(), PatchField.omitted(), PatchField.omitted(),
                PatchField.omitted(), PatchField.of(4), PatchField.omitted(), PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(StudentProfileNotFoundException.class);
    }

    @Test
    @DisplayName("updateStudentProfile: profile exists but role absent throws StudentCapabilityUnavailableException")
    void update_profileExists_roleAbsent_throwsStudentCapabilityUnavailableException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                null, fixedNow.minusSeconds(3600), fixedNow.minusSeconds(3600)
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(false);

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(), PatchField.omitted(), PatchField.omitted(),
                PatchField.omitted(), PatchField.of(4), PatchField.omitted(), PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(StudentCapabilityUnavailableException.class)
                .hasMessageContaining("not active");

        verify(studentProfilePort, never()).update(any());
    }

    @Test
    @DisplayName("updateStudentProfile: false -> true sets onboardingCompletedAt to now when role active")
    void update_onboardingFalseToTrue_setsTimestamp() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                null, fixedNow.minusSeconds(3600), fixedNow.minusSeconds(3600)
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(true);
        when(studentProfilePort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(true)
        );

        StudentProfile updated = service.updateStudentProfile(command);
        assertThat(updated.onboardingCompletedAt()).isEqualTo(fixedNow);
    }

    @Test
    @DisplayName("updateStudentProfile: true -> true keeps existing onboardingCompletedAt timestamp")
    void update_onboardingTrueToTrue_keepsTimestamp() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        Instant earlier = fixedNow.minusSeconds(7200);
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                earlier, earlier, earlier
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(true);
        when(studentProfilePort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(true)
        );

        StudentProfile updated = service.updateStudentProfile(command);
        assertThat(updated.onboardingCompletedAt()).isEqualTo(earlier);
    }

    @Test
    @DisplayName("updateStudentProfile: true -> false clears onboardingCompletedAt to null")
    void update_onboardingTrueToFalse_clearsTimestamp() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        Instant earlier = fixedNow.minusSeconds(7200);
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                earlier, earlier, earlier
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(true);
        when(studentProfilePort.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(false)
        );

        StudentProfile updated = service.updateStudentProfile(command);
        assertThat(updated.onboardingCompletedAt()).isNull();
    }

    @Test
    @DisplayName("updateStudentProfile: application validation rejects empty update (no properties specified)")
    void update_noPropertiesSpecified_throwsValidationException() {
        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("At least one property must be provided");
    }

    @Test
    @DisplayName("updateStudentProfile: application validation rejects null patch field")
    void update_nullPatchField_throwsValidationException() {
        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                null,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Patch fields cannot be null");
    }

    @Test
    @DisplayName("updateStudentProfile: application validation rejects explicit null onboardingCompleted")
    void update_explicitNullOnboardingCompleted_throwsValidationException() {
        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(null)
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("onboardingCompleted cannot be null");
    }

    @Test
    @DisplayName("updateStudentProfile: application validation rejects target state with future dateOfBirth")
    void update_targetStateFutureDateOfBirth_throwsValidationException() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        StudentProfile existing = new StudentProfile(
                USER_ID, null, null, null, null, null, null,
                null, fixedNow.minusSeconds(3600), fixedNow.minusSeconds(3600)
        );
        when(studentProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(userRoleQuery.hasActiveRole(USER_ID, "STUDENT")).thenReturn(true);

        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                USER_ID,
                PatchField.of(LocalDate.of(2099, 1, 1)),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> service.updateStudentProfile(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Date of birth cannot be in the future");
    }
}
