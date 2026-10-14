package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiProvenanceRepository {

    AiContextSnapshot saveSnapshot(AiContextSnapshot snapshot);

    Optional<AiContextSnapshot> findSnapshotByRunId(UUID aiRunId);

    void saveInputReferences(UUID aiRunId, List<AiRunInputReference> references);

    List<AiRunInputReference> findInputReferencesByRunId(UUID aiRunId);

    void saveKnowledgeChunkReferences(UUID aiRunId, List<AiRunKnowledgeChunk> chunkReferences);

    List<AiRunKnowledgeChunk> findKnowledgeChunkReferencesByRunId(UUID aiRunId);

    void saveValidationResults(UUID aiRunId, List<AiValidationResult> validationResults);

    List<AiValidationResult> findValidationResultsByRunId(UUID aiRunId);
}
