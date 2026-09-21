package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;

import java.util.List;

public record CoachingEligibilityDto(
        boolean eligible,
        List<String> blockingReasons
) {
    public static CoachingEligibilityDto fromDomain(CoachingEligibility domain) {
        return new CoachingEligibilityDto(domain.eligible(), domain.blockingReasons());
    }
}
