package com.fitnesscoaching.platform.modules.goal.application.port.out;

import java.util.UUID;

public interface GoalTrainerAuthorityPort {

    void verifyTrainerCanProposeGoal(UUID trainerId, UUID studentId);

    void verifyTrainerCanViewProposal(UUID trainerId, UUID studentId);
}
