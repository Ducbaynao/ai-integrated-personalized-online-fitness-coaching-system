package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Public request DTO for F04 progress summarization.
 */
public record F04ProgressSummaryApiRequest(
        UUID studentId,
        LocalDate fromDate,
        LocalDate toDate,
        String timezone,
        Set<String> metricCodes,
        UUID correlationId
) {
    public F04ProgressSummaryApiRequest {
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Ho_Chi_Minh";
        }
    }
}
