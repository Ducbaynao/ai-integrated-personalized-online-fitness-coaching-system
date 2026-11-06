package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;

import java.util.UUID;

/**
 * Inbound port for F04 AI Progress Summarization and readback.
 */
public interface AiProgressSummarizationUseCase {

    /**
     * Executes minimal authorized progress summarization for the requested student.
     * Enforces active account status, authority scope, and minimization rules.
     */
    F04ProgressSummaryResponse summarizeProgress(UUID actorId, F04ProgressSummaryApiRequest request);

    /**
     * Reads back an existing AI Run progress summary by runId with real-time authority revalidation
     * and freshness watermark verification.
     */
    F04ProgressSummaryResponse getProgressSummary(UUID actorId, UUID runId);
}
