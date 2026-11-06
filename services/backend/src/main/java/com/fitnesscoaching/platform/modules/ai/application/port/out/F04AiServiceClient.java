package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;

/**
 * Outbound port invoking the FastAPI AI Assistance Service for F04 progress summarization.
 */
public interface F04AiServiceClient {

    /**
     * Dispatches the minimal context envelope to the AI service and maps the structured response.
     */
    F04ProgressSummaryResponse summarizeProgress(F04InternalContextEnvelope envelope);
}
