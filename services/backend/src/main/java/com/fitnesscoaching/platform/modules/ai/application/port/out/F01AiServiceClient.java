package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;

/**
 * Outbound port for querying the AI Assistance Service for general fitness knowledge.
 */
public interface F01AiServiceClient {

    F01KnowledgeQueryResponse queryKnowledge(F01InternalContextEnvelope envelope);
}
