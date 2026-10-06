package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryPage;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;

import java.util.List;
import java.util.UUID;

public interface CoachingMobileReadUseCase {
    TrainerDirectoryPage discoverTrainers(UUID actorId, String displayNameQuery, int page, int size);

    UserDisplaySummary lookupStudent(UUID actorId, String email);

    List<Relationship> relationships(UUID actorId, int page, int size);
}
