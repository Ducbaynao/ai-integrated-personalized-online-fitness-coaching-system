package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ShareAdjustmentDraftApiResponse(
        UUID recommendationId,
        boolean sharedWithStudent,
        int sharedRevision,
        Instant sharedAt,
        UUID sharedBy
) {}
