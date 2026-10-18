package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Coaching-owned period facts for review scheduling; no cross-module repository access. */
public interface HumanReviewPeriodQuery {
    List<Period> activePeriods();
    record Period(UUID id, UUID studentId, UUID trainerId, Instant startedAt) {}
}
