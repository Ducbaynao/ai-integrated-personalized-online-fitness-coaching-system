package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;

import java.util.UUID;

public interface AiKnowledgeQueryUseCase {

    F01KnowledgeQueryResponse queryKnowledge(UUID actorId, F01KnowledgeQueryApiRequest request);
}
