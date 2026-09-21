package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainerEligibilityServiceUnitTest {

    @Mock
    private TrainerProfilePort trainerProfilePort;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    private TrainerEligibilityService service;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        service = new TrainerEligibilityService(
                trainerProfilePort,
                userAccountStatusQuery,
                userRoleQuery
        );
    }

    private TrainerProfile createTestProfile(TrainerVerificationStatus verification, TrainerActivityStatus activity, boolean active) {
        return new TrainerProfile(
                USER_ID,
                "coach-test",
                "Bio",
                BigDecimal.valueOf(5),
                true,
                verification,
                activity,
                Instant.now(),
                UUID.randomUUID(),
                active,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("getCoachingEligibility: returns eligible=true when user satisfies all policy conditions")
    void getCoachingEligibility_allValid_returnsEligibleTrue() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(
                createTestProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.ACTIVE, true)));

        CoachingEligibility result = service.getCoachingEligibility(USER_ID);

        assertThat(result.eligible()).isTrue();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(service.canCoach(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("getCoachingEligibility: returns eligible=false when profile is missing")
    void getCoachingEligibility_missingProfile_returnsEligibleFalse() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CoachingEligibility result = service.getCoachingEligibility(USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("PROFILE_NOT_FOUND");
        assertThat(service.canCoach(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("getCoachingEligibility: returns eligible=false when account is suspended")
    void getCoachingEligibility_suspendedAccount_returnsEligibleFalse() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.SUSPENDED));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(true);
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(
                createTestProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.ACTIVE, true)));

        CoachingEligibility result = service.getCoachingEligibility(USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("ACCOUNT_NOT_ACTIVE");
        assertThat(service.canCoach(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("getCoachingEligibility: returns eligible=false when TRAINER role is missing or revoked")
    void getCoachingEligibility_missingRole_returnsEligibleFalse() {
        when(userAccountStatusQuery.getAccountStatus(USER_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(USER_ID, "TRAINER")).thenReturn(false);
        when(trainerProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(
                createTestProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.ACTIVE, true)));

        CoachingEligibility result = service.getCoachingEligibility(USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("TRAINER_ROLE_MISSING");
        assertThat(service.canCoach(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("getCoachingEligibility: handles null userId safely")
    void getCoachingEligibility_nullUserId_returnsEligibleFalse() {
        CoachingEligibility result = service.getCoachingEligibility(null);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("ACCOUNT_NOT_ACTIVE", "TRAINER_ROLE_MISSING", "PROFILE_NOT_FOUND");
        assertThat(service.canCoach(null)).isFalse();
    }
}
