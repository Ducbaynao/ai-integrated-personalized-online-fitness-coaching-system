package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import java.util.UUID;

public interface TrainerAvailabilityQuery {
    void lockForCoachingDecision(UUID trainerId);
    boolean isAcceptingStudents(UUID trainerId);
}
