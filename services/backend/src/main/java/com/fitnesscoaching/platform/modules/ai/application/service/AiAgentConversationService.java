package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.CitationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse.LocatorDto;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.KnowledgeRetrievalService;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages scoped AI Agent conversations, turn execution, history, idempotency, and cancellation.
 * Strictly isolated from Student-Trainer human chat and never holds DB transactions during HTTP I/O.
 */
@Service
public class AiAgentConversationService {

    private static final int MAX_HISTORY_MESSAGES = 12;

    private final JdbcTemplate jdbcTemplate;
    private final AiRunF01UseCase aiRunUseCase;
    private final AiRunRepository aiRunRepository;
    private final ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;
    private final KnowledgeRetrievalUseCase retrievalUseCase;
    private final AiServiceProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean agentEnabled;
    private final boolean proposalsEnabled;
    private final RestClient restClient;
    private final TransactionTemplate txTemplate;
    private final F01RunEvidenceTracker evidenceTracker;
    private final AgentCitationValidator citationValidator;

    public AiAgentConversationService(
            JdbcTemplate jdbcTemplate,
            AiRunF01UseCase aiRunUseCase,
            AiRunRepository aiRunRepository,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            KnowledgeRetrievalUseCase retrievalUseCase,
            AiServiceProperties aiProperties,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager,
            F01RunEvidenceTracker evidenceTracker,
            AgentCitationValidator citationValidator,
            @Value("${ai.agent.enabled:true}") boolean agentEnabled,
            @Value("${ai.agent.proposals-enabled:false}") boolean proposalsEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiRunUseCase = aiRunUseCase;
        this.aiRunRepository = aiRunRepository;
        this.coachingAuthorityQueryProvider = coachingAuthorityQueryProvider;
        this.retrievalUseCase = retrievalUseCase;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.agentEnabled = agentEnabled;
        this.proposalsEnabled = proposalsEnabled;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.evidenceTracker = evidenceTracker;
        this.citationValidator = citationValidator;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, aiProperties.connectTimeoutMs()));
        requestFactory.setReadTimeout(Math.max(1000, aiProperties.readTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(aiProperties.baseUrl() != null ? aiProperties.baseUrl() : "http://localhost:8000")
                .requestFactory(requestFactory)
                .build();
    }

    public record ConversationSummary(
            UUID conversationId,
            String actorRole,
            UUID subjectId,
            String title,
            String status,
            Instant updatedAt
    ) {}

    public record TurnView(
            UUID turnId,
            int turnIndex,
            UUID aiRunId,
            String userMessage,
            String assistantMessage,
            String clarificationQuestion,
            String resultType,
            String reasonCode,
            List<CitationDto> citations,
            Map<String, Object> proposalDraft,
            String status,
            Instant createdAt
    ) {}

    public record ConversationDetail(
            UUID conversationId,
            String actorRole,
            UUID subjectId,
            String title,
            String status,
            List<TurnView> turns
    ) {}

    public ConversationSummary createConversation(UUID actorId, String actorRole, UUID subjectId, String title) {
        ensureEnabled();
        aiRunUseCase.revalidateActorAuthorization(actorId);

        UUID effectiveSubject;
        String effectiveRole;
        if (subjectId != null && !subjectId.equals(actorId)) {
            verifyTrainerScope(actorId, subjectId);
            effectiveSubject = subjectId;
            effectiveRole = "TRAINER";
        } else {
            effectiveSubject = actorId;
            // Self-scoped conversations never obtain a privileged role from client input.
            effectiveRole = "STUDENT";
        }

        String effectiveTitle = (title != null && !title.isBlank()) ? title.trim() : "Hội thoại AI";
        if (effectiveTitle.length() > 180) {
            effectiveTitle = effectiveTitle.substring(0, 180);
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        final String finalRole = effectiveRole;
        final String finalTitle = effectiveTitle;
        txTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                INSERT INTO fitness.ai_conversations (id, owner_user_id, actor_role, subject_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, actorId, finalRole, effectiveSubject, finalTitle,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)));

        return new ConversationSummary(id, effectiveRole, effectiveSubject, effectiveTitle, "ACTIVE", now);
    }

    public List<ConversationSummary> listConversations(UUID actorId) {
        ensureEnabled();
        aiRunUseCase.revalidateActorAuthorization(actorId);
        return jdbcTemplate.query("""
                SELECT id, actor_role, subject_id, title, status, updated_at
                FROM fitness.ai_conversations
                WHERE owner_user_id = ?
                ORDER BY updated_at DESC
                LIMIT 50
                """, (rs, rowNum) -> new ConversationSummary(
                (UUID) rs.getObject("id"),
                rs.getString("actor_role"),
                (UUID) rs.getObject("subject_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
        ), actorId).stream().filter(conversation -> {
            if (conversation.subjectId() == null || conversation.subjectId().equals(actorId)) return true;
            try {
                verifyTrainerScope(actorId, conversation.subjectId());
                return true;
            } catch (AiRunAccessDeniedException denied) {
                return false;
            }
        }).toList();
    }

    public ConversationDetail getConversation(UUID actorId, UUID conversationId) {
        ensureEnabled();
        ConversationSummary conv = loadAndVerifyAccess(actorId, conversationId);
        List<TurnView> turns = loadTurns(conversationId);
        return new ConversationDetail(
                conv.conversationId(),
                conv.actorRole(),
                conv.subjectId(),
                conv.title(),
                conv.status(),
                turns
        );
    }

    public TurnView sendTurn(UUID actorId, UUID conversationId, String message) {
        return sendTurn(actorId, conversationId, message, null, null);
    }

    public TurnView sendTurn(
            UUID actorId,
            UUID conversationId,
            String message,
            UUID requestedTurnId,
            String operationKey
    ) {
        ensureEnabled();
        if (message == null || message.isBlank()) {
            throw new AiRunValidationException("Message must not be blank.");
        }
        String trimmed = message.trim();
        if (trimmed.length() > 2000) {
            throw new AiRunValidationException("Message exceeds 2000 characters.");
        }
        if (operationKey != null && operationKey.trim().length() > 120) {
            throw new AiRunValidationException("operationKey exceeds 120 characters.");
        }
        String normalizedOpKey = (operationKey != null && !operationKey.isBlank())
                ? operationKey.trim()
                : null;

        ConversationSummary conv = loadAndVerifyAccess(actorId, conversationId);

        record PreparedTurn(
                boolean idempotentHit,
                TurnView existingTurn,
                UUID turnId,
                int turnIndex,
                AiRun run,
                List<Map<String, String>> history,
                Instant createdAt
        ) {}

        PreparedTurn prepared = txTemplate.execute(status -> {
            // Lock conversation row to serialize turn_index allocation and idempotency check
            jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.ai_conversations WHERE id = ? FOR UPDATE",
                    UUID.class,
                    conversationId
            );

            if (normalizedOpKey != null) {
                Optional<TurnView> existing = findTurnByOperationKey(conversationId, normalizedOpKey);
                if (existing.isPresent()) {
                    if (!existing.get().userMessage().equals(trimmed)) {
                        throw new AiRunLifecycleConflictException("operationKey was already used with a different message.");
                    }
                    return new PreparedTurn(true, existing.get(), null, 0, null, List.of(), null);
                }
            }

            List<TurnView> priorTurns = loadTurns(conversationId);
            List<Map<String, String>> history = new ArrayList<>();
            int startIdx = Math.max(0, priorTurns.size() - (MAX_HISTORY_MESSAGES / 2));
            for (int i = startIdx; i < priorTurns.size(); i++) {
                TurnView t = priorTurns.get(i);
                if (!"SUCCEEDED".equals(t.status())) {
                    continue;
                }
                history.add(Map.of("role", "user", "content", t.userMessage()));
                String reply = t.clarificationQuestion() != null
                        ? t.clarificationQuestion()
                        : (t.assistantMessage() != null ? t.assistantMessage() : "");
                if (!reply.isBlank()) {
                    history.add(Map.of("role", "assistant", "content", reply));
                }
            }

            UUID correlationId = UUID.randomUUID();
            AiRun run = aiRunUseCase.createF01Run(actorId, new CreateF01AiRunCommand(
                    trimmed,
                    "vi",
                    null,
                    correlationId,
                    conv.subjectId(),
                    Boolean.FALSE
            ));
            aiRunUseCase.transitionRunStatus(run.id(), AiRunStatus.QUEUED, AiRunStatus.RUNNING, Instant.now(clock), null);

            int nextIndex = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(turn_index), 0) + 1 FROM fitness.ai_conversation_turns WHERE conversation_id = ?",
                    Integer.class,
                    conversationId
            );
            UUID turnId = requestedTurnId != null ? requestedTurnId : UUID.randomUUID();
            Instant now = Instant.now(clock);
            jdbcTemplate.update("""
                    INSERT INTO fitness.ai_conversation_turns (id, conversation_id, turn_index, operation_key, ai_run_id, user_message, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, turnId, conversationId, nextIndex, normalizedOpKey, run.id(), trimmed, java.sql.Timestamp.from(now));

            return new PreparedTurn(false, null, turnId, nextIndex, run, history, now);
        });

        if (prepared == null) {
            throw new IllegalStateException("Failed to prepare conversation turn.");
        }
        if (prepared.idempotentHit()) {
            return prepared.existingTurn();
        }

        // Execute external AI / retrieval OUTSIDE any DB transaction
        long startMs = System.currentTimeMillis();
        TurnOutcome executionOutcome;
        try {
            if ("http".equalsIgnoreCase(aiProperties.mode())) {
                executionOutcome = executeViaFastApi(conv, prepared.run(), trimmed, prepared.history());
            } else {
                executionOutcome = executeSimulated(trimmed);
            }
            // Consent/account may have changed while external work was in flight.
            loadAndVerifyAccess(actorId, conversationId);
        } catch (RuntimeException ex) {
            executionOutcome = new TurnOutcome(null, null, "BLOCKED", "VALIDATION_FAILED", List.of(), null, List.of(), "FAILED");
        } finally {
            evidenceTracker.clearRunEvidence(prepared.run().id());
        }
        int latencyMs = (int) Math.max(0, System.currentTimeMillis() - startMs);
        final TurnOutcome completedOutcome = executionOutcome;

        // Conditional finalize inside short transaction: never overwrite a CANCELLED turn
        TurnView finalized = txTemplate.execute(status -> {
            TurnOutcome outcome = completedOutcome;
            int updated = jdbcTemplate.update("""
                    UPDATE fitness.ai_conversation_turns
                    SET assistant_message = ?,
                        clarification_question = ?,
                        result_type = ?,
                        reason_code = ?,
                        citations_json = ?::jsonb,
                        proposal_draft_json = ?::jsonb,
                        steps_json = ?::jsonb,
                        status = ?
                    WHERE id = ? AND status = 'PENDING'
                    """,
                    outcome.assistantMessage(),
                    outcome.clarificationQuestion(),
                    outcome.resultType(),
                    outcome.reasonCode(),
                    toJson(outcome.citations()),
                    outcome.proposalDraft() != null ? toJson(outcome.proposalDraft()) : null,
                    toJson(outcome.steps()),
                    outcome.turnStatus(),
                    prepared.turnId()
            );

            if (updated == 0) {
                // Turn was cancelled while in flight; return persisted cancelled view without overwriting
                return findTurnById(conversationId, prepared.turnId()).orElseThrow();
            }

            AiRunStatus runTerminalStatus = "FAILED".equals(outcome.turnStatus())
                    ? AiRunStatus.FAILED
                    : ("CANCELLED".equals(outcome.turnStatus())
                    ? AiRunStatus.CANCELLED
                    : ("BLOCKED".equals(outcome.resultType()) ? AiRunStatus.REJECTED_BY_VALIDATOR : AiRunStatus.SUCCEEDED));

            if (runTerminalStatus == AiRunStatus.CANCELLED) {
                aiRunRepository.transitionStatus(
                        prepared.run().id(), AiRunStatus.RUNNING, AiRunStatus.CANCELLED, null, Instant.now(clock)
                );
            } else {
                aiRunUseCase.completeF01Run(new CompleteF01AiRunCommand(
                        prepared.run().id(),
                        runTerminalStatus,
                        toJson(Map.of("resultType", outcome.resultType(), "reasonCode", outcome.reasonCode())),
                        "FAILED".equals(outcome.turnStatus()) || "BLOCKED".equals(outcome.resultType()) ? outcome.reasonCode() : null,
                        null,
                        null,
                        null,
                        null,
                        latencyMs,
                        Instant.now(clock)
                ));
            }

            jdbcTemplate.update(
                    "UPDATE fitness.ai_conversations SET updated_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now(clock)),
                    conversationId
            );

            return new TurnView(
                    prepared.turnId(),
                    prepared.turnIndex(),
                    prepared.run().id(),
                    trimmed,
                    outcome.assistantMessage(),
                    outcome.clarificationQuestion(),
                    outcome.resultType(),
                    outcome.reasonCode(),
                    outcome.citations(),
                    outcome.proposalDraft(),
                    outcome.turnStatus(),
                    prepared.createdAt()
            );
        });

        return finalized;
    }

    public TurnView cancelTurn(UUID actorId, UUID conversationId, UUID turnId) {
        ensureEnabled();
        loadAndVerifyAccess(actorId, conversationId);

        // Note: synchronous RestClient HTTP calls already in flight cannot be mid-stream aborted remotely;
        // marking both the turn and bound ai_run CANCELLED here prevents late completion from persisting.
        txTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE fitness.ai_conversation_turns
                    SET status = 'CANCELLED', result_type = 'BLOCKED', reason_code = 'CANCELLED'
                    WHERE id = ? AND conversation_id = ? AND status = 'PENDING'
                    """, turnId, conversationId);
            if (updated == 0) {
                if (findTurnById(conversationId, turnId).isEmpty()) {
                    throw new AiRunNotFoundException("Turn not found.");
                }
                return;
            }
            findTurnById(conversationId, turnId).ifPresent(turn -> {
                if (turn.aiRunId() != null) {
                    Instant now = Instant.now(clock);
                    if (!aiRunRepository.transitionStatus(turn.aiRunId(), AiRunStatus.RUNNING, AiRunStatus.CANCELLED, null, now)) {
                        aiRunRepository.transitionStatus(turn.aiRunId(), AiRunStatus.QUEUED, AiRunStatus.CANCELLED, null, now);
                    }
                }
            });
        });

        return findTurnById(conversationId, turnId)
                .orElseThrow(() -> new AiRunNotFoundException("Turn not found."));
    }

    private record TurnOutcome(
            String assistantMessage,
            String clarificationQuestion,
            String resultType,
            String reasonCode,
            List<CitationDto> citations,
            Map<String, Object> proposalDraft,
            List<Map<String, Object>> steps,
            String turnStatus
    ) {}

    private TurnOutcome executeViaFastApi(
            ConversationSummary conv,
            AiRun run,
            String message,
            List<Map<String, String>> history
    ) {
        List<String> allowedActions = new ArrayList<>(List.of(
                "search_knowledge", "get_current_plan", "get_progress_summary", "get_availability"
        ));
        if (proposalsEnabled) {
            allowedActions.add("prepare_workout_adjustment");
            allowedActions.add("prepare_reschedule");
        }

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("runId", run.id().toString());
        binding.put("correlationId", run.correlationId().toString());
        binding.put("actorId", run.requestedBy().toString());
        binding.put("actorRole", conv.actorRole());
        binding.put("subjectId", conv.subjectId() != null ? conv.subjectId().toString() : null);
        binding.put("allowedActions", allowedActions);
        binding.put("allowedSourceIds", new ArrayList<>(KnowledgeRetrievalService.APPROVED_SOURCES));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("binding", binding);
        payload.put("message", message);
        payload.put("language", "vi");
        payload.put("conversationHistory", history);

        Map<String, Object> resp;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri("/internal/v1/agent/turns")
                    .header("X-Internal-Service-Key", aiProperties.internalServiceKey())
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            resp = body;
        } catch (RestClientException ex) {
            boolean isTimeout = isTimeoutException(ex);
            return new TurnOutcome(
                    null, null, "INSUFFICIENT_DATA",
                    isTimeout ? "TIMEOUT" : "PROVIDER_ERROR",
                    List.of(), null, List.of(), "FAILED"
            );
        }

        if (resp == null) {
            return new TurnOutcome(null, null, "INSUFFICIENT_DATA", "PROVIDER_ERROR", List.of(), null, List.of(), "FAILED");
        }

        List<CitationDto> citations = objectMapper.convertValue(
                resp.getOrDefault("citations", List.of()),
                new TypeReference<List<CitationDto>>() {}
        );
        List<String> presentedIds = objectMapper.convertValue(
                resp.getOrDefault("evidenceChunkIds", List.of()), new TypeReference<List<String>>() {});
        if (!run.id().toString().equals(String.valueOf(resp.get("runId")))
                || !run.correlationId().toString().equals(String.valueOf(resp.get("correlationId")))
                || !citationValidator.valid(run.id(), presentedIds, citations)) {
            return new TurnOutcome(null, null, "BLOCKED", "UNVERIFIED_CITATION", List.of(), null, List.of(), "FAILED");
        }
        List<CitationDto> verifiedCitations = citations;

        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) resp.get("proposalDraft");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.getOrDefault("steps", List.of());
        boolean searched = steps.stream().anyMatch(s -> "search_knowledge".equals(s.get("toolName")));
        boolean readDomain = steps.stream().anyMatch(s -> "TOOL_CALL".equals(s.get("action"))
                && "OK".equals(s.get("status"))
                && List.of("get_current_plan", "get_progress_summary", "get_availability").contains(s.get("toolName")));
        if ("INFORMATION".equals(resp.get("resultType")) && (
                !(resp.get("answer") instanceof String answer) || answer.isBlank()
                || (citations.isEmpty() && (searched || !readDomain)))) {
            return new TurnOutcome(null, null, "BLOCKED", "UNVERIFIED_CITATION", List.of(), null, List.of(), "FAILED");
        }

        String runStatus = String.valueOf(resp.getOrDefault("runStatus", "SUCCEEDED"));
        String turnStatus = "FAILED".equals(runStatus) ? "FAILED"
                : ("CANCELLED".equals(runStatus) ? "CANCELLED" : "SUCCEEDED");

        return new TurnOutcome(
                (String) resp.get("answer"),
                (String) resp.get("clarificationQuestion"),
                String.valueOf(resp.getOrDefault("resultType", "INFORMATION")),
                String.valueOf(resp.getOrDefault("reasonCode", "NONE")),
                verifiedCitations,
                draft,
                steps,
                turnStatus
        );
    }

    private TurnOutcome executeSimulated(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("steroid") || lower.contains("doping")) {
            return new TurnOutcome(
                    null, null, "BLOCKED", "RULE_BLOCKED",
                    List.of(), null, List.of(Map.of("action", "GUARDRAIL", "status", "BLOCKED")), "SUCCEEDED"
            );
        }
        if (message.trim().split("\\s+").length < 3 && (lower.contains("tư vấn") || lower.contains("help"))) {
            return new TurnOutcome(
                    null,
                    "Bạn muốn hỏi về kỹ thuật bài tập, xem tiến độ hiện tại, hay điều chỉnh lịch tập?",
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION_REQUIRED",
                    List.of(),
                    null,
                    List.of(Map.of("action", "CLARIFICATION", "status", "OK")),
                    "SUCCEEDED"
            );
        }
        List<RetrievedKnowledgeChunk> chunks = retrievalUseCase.retrieveActiveKnowledge(
                message,
                new ArrayList<>(KnowledgeRetrievalService.APPROVED_SOURCES),
                3
        );
        if (chunks.isEmpty()) {
            return new TurnOutcome(
                    null, null, "INSUFFICIENT_DATA", "NO_RELEVANT_EVIDENCE",
                    List.of(), null, List.of(Map.of("action", "SEARCH", "status", "NOT_FOUND")), "SUCCEEDED"
            );
        }
        List<CitationDto> citations = chunks.stream()
                .map(c -> new CitationDto(
                        c.documentCode(),
                        c.versionLabel(),
                        c.sourceId(),
                        new LocatorDto(c.contentPath(), c.headingPath(), null, c.sectionIndex()),
                        c.content().length() > 200 ? c.content().substring(0, 200) : c.content()
                ))
                .toList();
        String answer = "Dựa trên tài liệu đã kiểm duyệt:\n- " + chunks.getFirst().snippet();
        return new TurnOutcome(
                answer, null, "INFORMATION", "NONE",
                citations, null, List.of(Map.of("action", "TOOL_CALL", "toolName", "search_knowledge", "status", "OK")), "SUCCEEDED"
        );
    }

    private ConversationSummary loadAndVerifyAccess(UUID actorId, UUID conversationId) {
        aiRunUseCase.revalidateActorAuthorization(actorId);
        List<ConversationSummary> rows = jdbcTemplate.query("""
                SELECT id, owner_user_id, actor_role, subject_id, title, status, updated_at
                FROM fitness.ai_conversations
                WHERE id = ?
                """, (rs, rowNum) -> {
            UUID ownerId = (UUID) rs.getObject("owner_user_id");
            if (!ownerId.equals(actorId)) {
                throw new AiRunAccessDeniedException("Conversation does not belong to authenticated user.");
            }
            return new ConversationSummary(
                    (UUID) rs.getObject("id"),
                    rs.getString("actor_role"),
                    (UUID) rs.getObject("subject_id"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }, conversationId);
        if (rows.isEmpty()) {
            throw new AiRunNotFoundException("AI conversation not found.");
        }
        ConversationSummary conv = rows.getFirst();
        if (conv.subjectId() != null && !conv.subjectId().equals(actorId)) {
            verifyTrainerScope(actorId, conv.subjectId());
        }
        return conv;
    }

    private void verifyTrainerScope(UUID trainerId, UUID studentId) {
        CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider.getIfAvailable();
        if (authorityQuery == null) {
            throw new AiRunAccessDeniedException("Coaching authority verification unavailable.");
        }
        AuthorityDecision decision = authorityQuery.evaluateCurrent(
                new AuthorityRequest(trainerId, studentId, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW)
        );
        if (decision == null || !decision.allowed()) {
            throw new AiRunAccessDeniedException("Actor lacks active coaching authority for target student.");
        }
    }

    private List<TurnView> loadTurns(UUID conversationId) {
        return jdbcTemplate.query("""
                SELECT id, turn_index, ai_run_id, user_message, assistant_message, clarification_question,
                       result_type, reason_code, citations_json::text AS citations_text,
                       proposal_draft_json::text AS draft_text, status, created_at
                FROM fitness.ai_conversation_turns
                WHERE conversation_id = ?
                ORDER BY turn_index ASC
                """, (rs, rowNum) -> mapTurnRow(rs), conversationId);
    }

    private Optional<TurnView> findTurnById(UUID conversationId, UUID turnId) {
        List<TurnView> rows = jdbcTemplate.query("""
                SELECT id, turn_index, ai_run_id, user_message, assistant_message, clarification_question,
                       result_type, reason_code, citations_json::text AS citations_text,
                       proposal_draft_json::text AS draft_text, status, created_at
                FROM fitness.ai_conversation_turns
                WHERE conversation_id = ? AND id = ?
                """, (rs, rowNum) -> mapTurnRow(rs), conversationId, turnId);
        return rows.stream().findFirst();
    }

    private Optional<TurnView> findTurnByOperationKey(UUID conversationId, String operationKey) {
        List<TurnView> rows = jdbcTemplate.query("""
                SELECT id, turn_index, ai_run_id, user_message, assistant_message, clarification_question,
                       result_type, reason_code, citations_json::text AS citations_text,
                       proposal_draft_json::text AS draft_text, status, created_at
                FROM fitness.ai_conversation_turns
                WHERE conversation_id = ? AND operation_key = ?
                """, (rs, rowNum) -> mapTurnRow(rs), conversationId, operationKey);
        return rows.stream().findFirst();
    }

    private TurnView mapTurnRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TurnView(
                (UUID) rs.getObject("id"),
                rs.getInt("turn_index"),
                (UUID) rs.getObject("ai_run_id"),
                rs.getString("user_message"),
                rs.getString("assistant_message"),
                rs.getString("clarification_question"),
                rs.getString("result_type"),
                rs.getString("reason_code"),
                parseCitations(rs.getString("citations_text")),
                parseMap(rs.getString("draft_text")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static boolean isTimeoutException(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof SocketTimeoutException
                    || cur instanceof java.net.http.HttpTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("504")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private void ensureEnabled() {
        if (!agentEnabled) {
            throw new AiFeatureDisabledException("AI Agent conversation feature is currently disabled.");
        }
    }

    private List<CitationDto> parseCitations(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<List<CitationDto>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
