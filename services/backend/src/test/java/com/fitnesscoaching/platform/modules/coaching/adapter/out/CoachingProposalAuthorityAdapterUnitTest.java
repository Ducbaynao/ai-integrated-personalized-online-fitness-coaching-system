package com.fitnesscoaching.platform.modules.coaching.adapter.out;

import com.fitnesscoaching.platform.common.exception.CoachingRelationshipRequiredException;
import com.fitnesscoaching.platform.common.exception.DataSharingAccessLevelInsufficientException;
import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachingProposalAuthorityAdapterUnitTest {
    @Mock CoachingAuthorityQuery authorityQuery;

    private CoachingProposalAuthorityAdapter adapter;
    private UUID trainerId;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        adapter = new CoachingProposalAuthorityAdapter(authorityQuery);
        trainerId = UUID.randomUUID();
        studentId = UUID.randomUUID();
    }

    @Test
    void proposalDelegatesToCanonicalContributeAuthority() {
        ArgumentCaptor<AuthorityRequest> request = ArgumentCaptor.forClass(AuthorityRequest.class);
        when(authorityQuery.evaluateCurrent(request.capture())).thenReturn(allowed(DataAccessLevel.CONTRIBUTE));

        assertThatCode(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId)).doesNotThrowAnyException();
        assertThat(request.getValue().requiredLevel()).isEqualTo(DataAccessLevel.CONTRIBUTE);
        assertThat(request.getValue().actorId()).isEqualTo(trainerId);
        assertThat(request.getValue().studentId()).isEqualTo(studentId);
    }

    @Test
    void proposalRejectsViewOnlyGrantWithStableException() {
        when(authorityQuery.evaluateCurrent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(denied(AuthorityReason.ACCESS_LEVEL_INSUFFICIENT, DataAccessLevel.VIEW));

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(DataSharingAccessLevelInsufficientException.class);
    }

    @Test
    void proposalRejectsMissingPermission() {
        when(authorityQuery.evaluateCurrent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(denied(AuthorityReason.PERMISSION_NOT_GRANTED, null));

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(DataSharingPermissionRequiredException.class);
    }

    @Test
    void viewRequiresOnlyViewLevel() {
        ArgumentCaptor<AuthorityRequest> request = ArgumentCaptor.forClass(AuthorityRequest.class);
        when(authorityQuery.evaluateCurrent(request.capture())).thenReturn(allowed(DataAccessLevel.VIEW));

        assertThatCode(() -> adapter.verifyTrainerCanViewProposal(trainerId, studentId)).doesNotThrowAnyException();
        assertThat(request.getValue().requiredLevel()).isEqualTo(DataAccessLevel.VIEW);
    }

    @Test
    void missingPeriodPreservesGoalRelationshipErrorContract() {
        when(authorityQuery.evaluateCurrent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(denied(AuthorityReason.PERIOD_NOT_EFFECTIVE, null));

        assertThatThrownBy(() -> adapter.verifyTrainerCanProposeGoal(trainerId, studentId))
                .isInstanceOf(CoachingRelationshipRequiredException.class);
    }

    private AuthorityDecision allowed(DataAccessLevel level) {
        return new AuthorityDecision(true, AuthorityReason.ALLOWED, UUID.randomUUID(), 2,
                UUID.randomUUID(), UUID.randomUUID(), 3, level, null, null);
    }

    private AuthorityDecision denied(AuthorityReason reason, DataAccessLevel level) {
        return new AuthorityDecision(false, reason, UUID.randomUUID(), 2,
                UUID.randomUUID(), null, -1, level, null, null);
    }
}
