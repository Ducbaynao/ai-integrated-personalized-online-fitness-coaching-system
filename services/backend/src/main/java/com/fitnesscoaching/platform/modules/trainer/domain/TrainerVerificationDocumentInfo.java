package com.fitnesscoaching.platform.modules.trainer.domain;

import java.time.Instant;
import java.util.UUID;

public record TrainerVerificationDocumentInfo(
        UUID id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String scanStatus,
        Instant createdAt
) {}
