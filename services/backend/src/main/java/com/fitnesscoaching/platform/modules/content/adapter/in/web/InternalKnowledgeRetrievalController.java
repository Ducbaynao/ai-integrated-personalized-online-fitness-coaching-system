package com.fitnesscoaching.platform.modules.content.adapter.in.web;

import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal REST controller serving authenticated knowledge retrieval requests from the FastAPI AI service.
 * Protected by X-Internal-Service-Key.
 */
@RestController
@RequestMapping("/internal/v1/knowledge")
public class InternalKnowledgeRetrievalController {

    private final KnowledgeRetrievalUseCase retrievalUseCase;
    private final AiServiceProperties aiProperties;
    private final F01RunEvidenceTracker evidenceTracker;

    public InternalKnowledgeRetrievalController(
            KnowledgeRetrievalUseCase retrievalUseCase,
            AiServiceProperties aiProperties,
            F01RunEvidenceTracker evidenceTracker
    ) {
        this.retrievalUseCase = retrievalUseCase;
        this.aiProperties = aiProperties;
        this.evidenceTracker = evidenceTracker;
    }

    @PostMapping("/retrieval")
    public ResponseEntity<InternalRetrievalResponse> retrieveChunks(
            @RequestHeader(value = "X-Internal-Service-Key", required = false) String internalKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationIdHeader,
            @RequestBody InternalRetrievalRequest request
    ) {
        if (internalKey == null || !internalKey.equals(aiProperties.internalServiceKey())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.ok(new InternalRetrievalResponse(List.of()));
        }

        int limit = request.limit() != null ? request.limit() : 5;
        List<RetrievedKnowledgeChunk> chunks = retrievalUseCase.retrieveActiveKnowledge(
                request.query(),
                request.allowedSourceIds(),
                limit
        );

        if (correlationIdHeader != null && !correlationIdHeader.isBlank()) {
            try {
                UUID correlationId = UUID.fromString(correlationIdHeader.trim());
                evidenceTracker.recordRunEvidence(correlationId, chunks);
            } catch (IllegalArgumentException ignored) {
                // Ignore non-UUID correlation ID headers
            }
        }

        List<InternalRetrievalResponse.ChunkDto> dtoList = chunks.stream()
                .map(c -> new InternalRetrievalResponse.ChunkDto(
                        c.chunkId(),
                        c.documentId(),
                        c.documentCode(),
                        c.versionLabel(),
                        c.sourceId(),
                        c.title(),
                        c.contentPath(),
                        c.headingPath(),
                        c.sectionIndex(),
                        c.snippet(),
                        c.content(),
                        c.similarityScore()
                ))
                .toList();

        return ResponseEntity.ok(new InternalRetrievalResponse(dtoList));
    }

    public record InternalRetrievalRequest(
            String query,
            List<String> allowedSourceIds,
            Integer limit
    ) {}

    public record InternalRetrievalResponse(
            List<ChunkDto> chunks
    ) {
        public record ChunkDto(
                UUID chunkId,
                UUID documentId,
                String documentCode,
                String version,
                String sourceId,
                String title,
                String sourcePath,
                String heading,
                int sectionIndex,
                String snippet,
                String content,
                double similarityScore
        ) {}
    }
}
