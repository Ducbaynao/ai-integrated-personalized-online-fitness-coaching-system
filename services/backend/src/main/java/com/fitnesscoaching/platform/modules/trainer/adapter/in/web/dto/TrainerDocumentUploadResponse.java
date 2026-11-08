package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record TrainerDocumentUploadResponse(
        UUID id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String scanStatus,
        Instant createdAt
) {}
