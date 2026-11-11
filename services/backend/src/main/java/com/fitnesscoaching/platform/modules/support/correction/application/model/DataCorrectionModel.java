package com.fitnesscoaching.platform.modules.support.correction.application.model;

import java.time.Instant;
import java.util.UUID;

public record DataCorrectionModel(
        UUID id,
        UUID supportCaseId,
        String caseNumber,
        String targetType,
        UUID targetId,
        String beforeDataJson,
        String proposedDataJson,
        String appliedDataJson,
        String validationResultJson,
        UUID requestedBy,
        String requestedByName,
        UUID appliedBy,
        String appliedByName,
        Instant createdAt,
        Instant appliedAt
) {
}
