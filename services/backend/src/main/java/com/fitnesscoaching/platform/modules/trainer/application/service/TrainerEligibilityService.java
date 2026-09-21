package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.user.application.port.out.CoachingAuthorityQuery;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TrainerEligibilityService implements TrainerCoachingEligibilityQuery, CoachingAuthorityQuery {

    private final TrainerProfilePort trainerProfilePort;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;

    public TrainerEligibilityService(
            TrainerProfilePort trainerProfilePort,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery
    ) {
        this.trainerProfilePort = trainerProfilePort;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
    }

    @Override
    public boolean canCoach(UUID userId) {
        return getCoachingEligibility(userId).eligible();
    }

    @Override
    public CoachingEligibility getCoachingEligibility(UUID userId) {
        if (userId == null) {
            return CoachingEligibility.evaluate(null, false, null);
        }

        AccountStatus accountStatus = userAccountStatusQuery.getAccountStatus(userId).orElse(null);
        boolean hasActiveTrainerRole = userRoleQuery.hasActiveRole(userId, "TRAINER");
        TrainerProfile profile = trainerProfilePort.findByUserId(userId).orElse(null);

        return CoachingEligibility.evaluate(accountStatus, hasActiveTrainerRole, profile);
    }
}
