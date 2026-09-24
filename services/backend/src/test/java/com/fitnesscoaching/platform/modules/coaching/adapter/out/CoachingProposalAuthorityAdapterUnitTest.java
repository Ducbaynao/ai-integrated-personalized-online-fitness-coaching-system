package com.fitnesscoaching.platform.modules.coaching.adapter.out;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachingProposalAuthorityAdapterUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UserAccountStatusQuery userAccountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private TrainerCoachingEligibilityQuery eligibilityQuery;

    private CoachingProposalAuthorityAdapter adapter;

    private UUID trainerId;
    private UUID studentId;
    private UUID relationshipId;

    @BeforeEach
    void setUp() {
        adapter = new CoachingProposalAuthorityAdapter(jdbcTemplate, userAccountStatusQuery, userRoleQuery, eligibilityQuery);
        trainerId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        relationshipId = UUID.randomUUID();
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: succeeds when all 7 checks pass")
    void verify_success() {
        // 1. Account status ACTIVE
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));

        // 2. Has role TRAINER
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(true);

        // 3 & 4. Canonical eligibility eligible
        when(eligibilityQuery.getCoachingEligibility(trainerId))
                .thenReturn(new CoachingEligibility(true, List.of()));

        // 5. Active relationship
        when(jdbcTemplate.query(contains("coaching_relationships"), any(RowMapper.class), eq(trainerId), eq(studentId)))
                .thenReturn(List.of(relationshipId));

        // 6. Active HUMAN_COACH period
        when(jdbcTemplate.query(contains("coaching_periods"), any(RowMapper.class), eq(studentId), eq(trainerId), eq(relationshipId)))
                .thenReturn(List.of(UUID.randomUUID()));

        // 7. Active data sharing permission
        when(jdbcTemplate.query(contains("data_sharing_permissions"), any(RowMapper.class), eq(relationshipId), eq(studentId), eq(trainerId)))
                .thenReturn(List.of(UUID.randomUUID()));

        assertThatCode(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when account is not ACTIVE")
    void verify_accountNotActive() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(AccountUnavailableException.class);
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when trainer role is absent")
    void verify_trainerRoleMissing() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(false);

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(TrainerCapabilityUnavailableException.class);
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when trainer is not eligible")
    void verify_trainerIneligible() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(true);
        when(eligibilityQuery.getCoachingEligibility(trainerId))
                .thenReturn(new CoachingEligibility(false, List.of("PROFILE_SUSPENDED")));

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(TrainerNotEligibleException.class);
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when coaching relationship is not ACTIVE")
    void verify_noCoachingRelationship() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(true);
        when(eligibilityQuery.getCoachingEligibility(trainerId))
                .thenReturn(new CoachingEligibility(true, List.of()));
        when(jdbcTemplate.query(contains("coaching_relationships"), any(RowMapper.class), eq(trainerId), eq(studentId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(CoachingRelationshipRequiredException.class);
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when no active HUMAN_COACH period exists")
    void verify_noActiveHumanCoachPeriod() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(true);
        when(eligibilityQuery.getCoachingEligibility(trainerId))
                .thenReturn(new CoachingEligibility(true, List.of()));
        when(jdbcTemplate.query(contains("coaching_relationships"), any(RowMapper.class), eq(trainerId), eq(studentId)))
                .thenReturn(List.of(relationshipId));
        when(jdbcTemplate.query(contains("coaching_periods"), any(RowMapper.class), eq(studentId), eq(trainerId), eq(relationshipId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(CoachingRelationshipRequiredException.class);
    }

    @Test
    @DisplayName("verifyTrainerCanProposeGoal: fails when FITNESS_GOAL permission is not granted")
    void verify_noDataSharingPermission() {
        when(userAccountStatusQuery.getAccountStatus(trainerId))
                .thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(trainerId, "TRAINER"))
                .thenReturn(true);
        when(eligibilityQuery.getCoachingEligibility(trainerId))
                .thenReturn(new CoachingEligibility(true, List.of()));
        when(jdbcTemplate.query(contains("coaching_relationships"), any(RowMapper.class), eq(trainerId), eq(studentId)))
                .thenReturn(List.of(relationshipId));
        when(jdbcTemplate.query(contains("coaching_periods"), any(RowMapper.class), eq(studentId), eq(trainerId), eq(relationshipId)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(jdbcTemplate.query(contains("data_sharing_permissions"), any(RowMapper.class), eq(relationshipId), eq(studentId), eq(trainerId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(DataSharingPermissionRequiredException.class);
    }
}
