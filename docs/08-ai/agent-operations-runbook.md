# Fitness AI Agent Operations & Rollback Runbook (AG-M6)

**Ngày cập nhật:** 27/11/2026 (UTC+7)  
**Liên quan:** [Agent upgrade roadmap](agent-upgrade-roadmap.md) · [Progress tracker](../07-development/agent-upgrade-progress.md)

## 1. Feature Flags & Kill Switches

| Layer | Config / Env Var | Default | Tác dụng khi tắt (`false`) |
|---|---|---|---|
| Backend Agent Conversation | `ai.agent.enabled` (`AI_AGENT_ENABLED`) | `true` | Chặn `/api/v1/ai/conversations/**` (`AiFeatureDisabledException`); F01 cũ và luồng thủ công Phase 1 tiếp tục hoạt động |
| Backend Proposal Tools (AG-M5) | `ai.agent.proposals-enabled` (`AI_AGENT_PROPOSALS_ENABLED`) | `false` | Từ chối (`DENIED`) mọi lời gọi `prepare_workout_adjustment` / `prepare_reschedule` qua agent |
| Backend Hybrid Retrieval (AG-M1) | `ai.retrieval.hybrid-enabled` (`AI_RETRIEVAL_HYBRID_ENABLED`) | `false` | Quay về dense pgvector-only (`KnowledgeRetrievalService`) |
| Backend Knowledge Outbox Worker | `ai.knowledge.outbox-worker.enabled` (`AI_KNOWLEDGE_OUTBOX_WORKER_ENABLED`) | `true` | Tạm dừng `KnowledgeIndexingOutboxScheduler`; bài publish mới vẫn tạo bản ghi `QUEUED` trong `knowledge_processing_runs` để xử lý sau |
| FastAPI Reranker (AG-M2) | `RAG_RERANK_ENABLED` | `false` | Dùng `DisabledReranker`; không gọi external rerank API và không giả lập score (hiện nối vào `search_knowledge` của agent; F01 một lượt cũ giữ nguyên `KnowledgeRetriever`) |
| FastAPI MMR (AG-M2) | `RAG_MMR_ENABLED` | `false` | Giữ nguyên thứ tự sau retrieval/rerank (áp dụng trong `RetrievalPipeline` của agent) |

## 2. Thứ tự Rollback khi có sự cố

1. **Tắt proposal tools trước:** đặt `AI_AGENT_PROPOSALS_ENABLED=false` để agent về chế độ read-only. Các `ai_recommendations` đã tạo ở trạng thái `PENDING` vẫn giữ nguyên lifecycle và kiểm tra source/version khi người có thẩm quyền duyệt.
2. **Tắt agent conversation nếu cần:** đặt `AI_AGENT_ENABLED=false`. Luồng F01 một lượt (`/api/v1/ai/knowledge/query`) và toàn bộ luồng thủ công Phase 1 vẫn hoạt động bình thường.
3. **Tắt các tầng retrieval mới độc lập:** đặt `RAG_MMR_ENABLED=false`, `RAG_RERANK_ENABLED=false`, `AI_RETRIEVAL_HYBRID_ENABLED=false` để quay về dense vector-only.
4. **Không dùng Flyway repair hoặc sửa lịch sử học viên** để rollback. `V66` (cột `search_vector` + GIN index) và `V67` (`ai_conversations`, `ai_conversation_turns`) là forward migrations tương thích ngược với luồng Phase 1 và F01 cũ.

## 3. Giới hạn vận hành đã cấu hình

### Sửa lỗi sau review ngày 27/11/2026

Xem [báo cáo sửa lỗi và kiểm thử](agent-review-fixes-2026-11-27.md). Backend yêu cầu runId/correlationId khớp và citation thuộc `evidenceChunkIds` do runner tạo, giao với snapshot retrieval của đúng run; đồng thời kiểm exact quote/source/version và eligibility hiện tại. Thiếu snapshot phải fail closed. FastAPI và Spring phải được cập nhật cùng contract; response agent cũ thiếu metadata cho citation sẽ bị từ chối. Internal JSON Schema nằm trong `contracts/ai-schemas/agent-turn-request.schema.json` và `agent-turn-response.schema.json`; REST conversation contract đã thêm vào OpenAPI.

Mỗi search thực hiện thêm authorization callback `POST /internal/v1/agent/tools/knowledge-access` (service key + run/actor/subject binding) trước retrieval. Run cancelled/terminal hoặc quyền bị thu hồi không được tiếp tục search. Không truyền callback này cho Mobile. Không tự fallback sang simulated khi cấu hình OpenAI thiếu key.

Test xuyên tầng mới chạy uvicorn production routes và real ToolGateway/RetrievalPipeline, dùng HTTP fixtures cho model/reranker; đây là bằng chứng tích hợp offline, không phải chất lượng model thật. Cancel không abort được HTTP call từ Spring đã chạy; conditional finalize chặn answer muộn. Giữ proposal flag tắt; F03 tool hiện là exercise substitution.

- `maxModelTurns`: 5 (tối đa 8)
- `maxToolCalls`: 8 (tối đa 12)
- `maxSearchCalls`: 2 (tối đa 4)
- `deadlineSeconds`: 25.0s
- `rag_context_max_chars`: 8000
- `OPENAI_API_KEY`: hiện trống trong môi trường dev; `F01_PROVIDER=simulated`, `ai.service.mode=simulated`. Live LLM/reranker evaluation là opt-in có ngân sách riêng.
