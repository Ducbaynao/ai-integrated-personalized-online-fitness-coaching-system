package com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionModel;

import java.time.Instant;
import java.util.UUID;

public record DataCorrectionResponse(
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
    public static DataCorrectionResponse fromDomain(DataCorrectionModel model) {
        if (model == null) return null;
        return new DataCorrectionResponse(
                model.id(),
                model.supportCaseId(),
                model.caseNumber(),
                model.targetType(),
                model.targetId(),
                model.beforeDataJson(),
                model.proposedDataJson(),
                model.appliedDataJson(),
                model.validationResultJson(),
                model.requestedBy(),
                model.requestedByName(),
                model.appliedBy(),
                model.appliedByName(),
                model.createdAt(),
                model.appliedAt()
        );
    }
}
