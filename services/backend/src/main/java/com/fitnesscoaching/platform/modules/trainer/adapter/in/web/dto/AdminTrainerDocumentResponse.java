package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentInfo;

import java.time.Instant;
import java.util.UUID;

public record AdminTrainerDocumentResponse(
        UUID id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String scanStatus,
        String downloadUrl,
        Instant createdAt
) {
    public static AdminTrainerDocumentResponse fromDomain(UUID applicationId, TrainerVerificationDocumentInfo doc) {
        String downloadUrl = String.format("/api/v1/admin/trainer-applications/%s/documents/%s/download",
                applicationId, doc.id());
        return new AdminTrainerDocumentResponse(
                doc.id(),
                doc.originalFilename(),
                doc.contentType(),
                doc.sizeBytes(),
                doc.scanStatus(),
                downloadUrl,
                doc.createdAt()
        );
    }
}
