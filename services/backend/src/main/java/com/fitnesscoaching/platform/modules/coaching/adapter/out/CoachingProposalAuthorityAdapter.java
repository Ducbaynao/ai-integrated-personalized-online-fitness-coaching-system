package com.fitnesscoaching.platform.modules.coaching.adapter.out;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.CoachingRelationshipRequiredException;
import com.fitnesscoaching.platform.common.exception.DataSharingPermissionRequiredException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerNotEligibleException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalTrainerAuthorityPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class CoachingProposalAuthorityAdapter implements GoalTrainerAuthorityPort {

    private final JdbcTemplate jdbcTemplate;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final TrainerCoachingEligibilityQuery trainerCoachingEligibilityQuery;

    public CoachingProposalAuthorityAdapter(
            JdbcTemplate jdbcTemplate,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            TrainerCoachingEligibilityQuery trainerCoachingEligibilityQuery
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.trainerCoachingEligibilityQuery = trainerCoachingEligibilityQuery;
    }

    @Override
    public void verifyTrainerCanProposeGoal(UUID trainerId, UUID studentId) {
        verifyTrainerAuthority(trainerId, studentId);
    }

    @Override
    public void verifyTrainerCanViewProposal(UUID trainerId, UUID studentId) {
        verifyTrainerAuthority(trainerId, studentId);
    }

    private void verifyTrainerAuthority(UUID trainerId, UUID studentId) {
        if (trainerId == null) {
            throw new TrainerCapabilityUnavailableException("Trainer ID cannot be null");
        }
        if (studentId == null) {
            throw new CoachingRelationshipRequiredException("Student ID cannot be null");
        }

        // 1. Account must be ACTIVE
        AccountStatus accountStatus = userAccountStatusQuery.getAccountStatus(trainerId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + trainerId));
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account status " + accountStatus + " does not permit this operation.");
        }

        // 2. Active TRAINER role
        if (!userRoleQuery.hasActiveRole(trainerId, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException(
                    "Trainer capability is not active for user: " + trainerId);
        }

        // 3 & 4. Trainer profile active & canonical coaching eligibility
        CoachingEligibility eligibility = trainerCoachingEligibilityQuery.getCoachingEligibility(trainerId);
        if (!eligibility.eligible()) {
            throw new TrainerNotEligibleException(
                    "Trainer is not eligible to coach: " + eligibility.blockingReasons());
        }

        // 5. Active Coaching Relationship with the student
        String relationshipSql = """
                SELECT id
                FROM fitness.coaching_relationships
                WHERE trainer_id = ?
                  AND student_id = ?
                  AND status = 'ACTIVE'::fitness.coaching_relationship_status
                LIMIT 1
                """;
        List<UUID> relationshipIds = jdbcTemplate.query(
                relationshipSql,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                trainerId,
                studentId
        );
        if (relationshipIds.isEmpty()) {
            throw new CoachingRelationshipRequiredException(
                    "An active coaching relationship between trainer and student is required.");
        }
        UUID relationshipId = relationshipIds.get(0);

        // 6. Current HUMAN_COACH Coaching Period
        String periodSql = """
                SELECT id
                FROM fitness.coaching_periods
                WHERE student_id = ?
                  AND trainer_id = ?
                  AND coaching_relationship_id = ?
                  AND mode = 'HUMAN_COACH'::fitness.coaching_mode
                  AND started_at <= now()
                  AND (ended_at IS NULL OR ended_at > now())
                LIMIT 1
                """;
        List<UUID> periodIds = jdbcTemplate.query(
                periodSql,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                studentId,
                trainerId,
                relationshipId
        );
        if (periodIds.isEmpty()) {
            throw new CoachingRelationshipRequiredException(
                    "An active HUMAN_COACH coaching period between trainer and student is required.");
        }

        // 7. Active Data Sharing Permission for FITNESS_GOAL
        String permissionSql = """
                SELECT id
                FROM fitness.data_sharing_permissions
                WHERE relationship_id = ?
                  AND student_id = ?
                  AND trainer_id = ?
                  AND data_scope = 'FITNESS_GOAL'::fitness.data_scope_code
                  AND decision = 'ALLOW'::fitness.permission_decision
                  AND revoked_at IS NULL
                  AND valid_from <= now()
                  AND (valid_until IS NULL OR valid_until > now())
                LIMIT 1
                """;
        List<UUID> permissionIds = jdbcTemplate.query(
                permissionSql,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                relationshipId,
                studentId,
                trainerId
        );
        if (permissionIds.isEmpty()) {
            throw new DataSharingPermissionRequiredException(
                    "Active FITNESS_GOAL data sharing permission is required.");
        }
    }
}
