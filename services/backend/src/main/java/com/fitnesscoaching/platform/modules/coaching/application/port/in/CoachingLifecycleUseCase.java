package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;

import java.util.List;
import java.util.UUID;

public interface CoachingLifecycleUseCase {
    Outcome initiate(UUID actor, UUID studentId, UUID trainerId, UUID commandKey);
    Outcome relationshipAction(UUID actor, UUID relationshipId, String action, long expectedVersion,
                               String reason, UUID commandKey);
    Outcome createResume(UUID actor, UUID relationshipId, long expectedVersion, String reason, UUID commandKey);
    Outcome resumeAction(UUID actor, UUID relationshipId, UUID resumeId, String action,
                         long expectedRelationshipVersion, long expectedRequestVersion, UUID commandKey);
    Outcome current(UUID actor);
    Outcome detail(UUID actor, UUID relationshipId);
    List<Relationship> pending(UUID actor, String direction, int page, int size);
    List<History> history(UUID actor, UUID relationshipId, int page, int size);
    List<Resume> resumeHistory(UUID actor, UUID relationshipId, int page, int size);
    List<Period> periods(UUID actor, int page, int size);
}
