package com.fitnesscoaching.platform.modules.trainer.domain;

import java.util.UUID;

public record TrainerVerificationDocumentContent(
        UUID id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String scanStatus,
        byte[] bytes
) {}
