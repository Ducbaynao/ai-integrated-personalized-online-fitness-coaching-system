package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProgressPhotoResponse(
        UUID id,
        UUID studentId,
        UUID mediaId,
        Instant takenAt,
        String viewType,
        BigDecimal weightAtTime,
        Short weightUnitId,
        String weightUnitCode,
        String notes,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {}
