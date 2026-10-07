package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CurrentCoachingContextQuery {
    Optional<CurrentCoachingContext> findEffectiveContext(UUID studentId, boolean lock);

    record CurrentCoachingContext(UUID periodId, Mode mode, UUID relationshipId, UUID trainerId,
                                  Instant startedAt, Instant scheduledEndAt) {}
    enum Mode { HUMAN_COACH, SELF_DIRECTED }
}
