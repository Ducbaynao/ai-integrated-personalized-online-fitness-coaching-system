# Checklist review tích hợp F01 contract với B và Backlog chuẩn bị P2-M2

**Ngày lập:** 11/10/2026 · **Trạng thái:** DRAFT FOR REVIEW — chuẩn bị phiên làm việc kỹ thuật với B.  
**Phạm vi:** F01 General Fitness Knowledge Contract (P2-M0 A-10/A-11, chuẩn bị P2-M2).  
**Tài liệu liên quan:** [Contract F01](f01-general-knowledge-contract.md) · [Báo cáo bàn giao](f01-contract-corpus-integrity-handoff-2026-10-11.md).

> [!IMPORTANT]
> Tài liệu này tổng hợp các đề xuất kỹ thuật hiện có để rà soát cùng B (phụ trách Backend/Phase 1). Không tự ý đánh dấu A-11/A-14 đã hoàn tất hoặc tự gán quyền xác nhận khi chưa có phiên review thực tế. Mọi phân công assignee/reviewer, đường dẫn file và test IDs sẽ được ghi nhận tại issue tracker tương ứng.

---

## 1. Danh mục review kỹ thuật với B

### 1.1. Public / Internal URI và DTO
- **Đề xuất hiện có:**
  - Client gọi Spring Boot: `POST /api/v1/ai/knowledge/query` (đã khai báo trong OpenAPI `contracts/openapi/openapi.yaml`).
  - Spring Boot gọi FastAPI nội bộ: `POST /internal/v1/knowledge/query` mang context envelope (`contracts/ai-schemas/f01-internal-context-envelope.schema.json`).
  - Request DTO: `question` (5–1000 ký tự, non-whitespace), `language` (vi/en), tùy chọn `exerciseId` (UUID) và `correlationId` (UUID).
  - Terminal Result DTO: `runId`, `runStatus`, `resultType` (INFORMATION / INSUFFICIENT_DATA / BLOCKED), `asOf` (RFC 3339, seconds 00–59), `answer`, `citations[]`, `limitations[]`, `missingData[]`, `usage`, `error`.
- **Điểm cần B review/xác nhận:**
  - Định dạng JSON payload giữa Spring Boot REST Controller (Java DTO / Jackson) và FastAPI endpoint (Pydantic model) đã khớp hoàn toàn với schema JSON hay cần điều chỉnh naming conventions.
  - Tiêu chuẩn hóa error body khi lỗi trước khi vào AI service (HTTP 400, 401, 403, 404, 503 dùng `ErrorResponse` chung của platform).
- **Dependency thực sự chặn runtime:**
  - Định nghĩa DTO class Java trong backend Spring Boot và Pydantic model trong FastAPI.
- **Có thể phát triển độc lập:**
  - Contract JSON Schema và OpenAPI đã hoàn tất và đóng băng ở mức contract; có thể dùng fixture và mock test độc lập ở cả hai phía client/server.

---

### 1.2. Run Identity, Correlation ID và HTTP/Error Mapping
- **Đề xuất hiện có:**
  - Spring Boot sinh `requestId` (UUID) đóng vai trò `ai_run.id` trước khi gọi FastAPI; FastAPI echo `runId = requestId`.
  - `correlationId` truyền xuyên suốt qua HTTP header `X-Correlation-Id` và body để truy vết distributed tracing, không phải idempotency key.
  - HTTP status mapping:
    - `200`: SUCCEEDED (INFORMATION), INSUFFICIENT_DATA, BLOCKED (RULE_BLOCKED).
    - `502`: REJECTED_BY_VALIDATOR (VALIDATION_FAILED) — tuyệt đối không trả output bị từ chối ra client; FAILED (MODEL_ERROR / PROVIDER_ERROR).
    - `504`: FAILED (TIMEOUT).
    - `500`: FAILED (PROCESSING_ERROR).
- **Điểm cần B review/xác nhận:**
  - Chiến lược ánh xạ HTTP status từ Spring Boot Controller khi nhận mã lỗi nội bộ từ AI service hoặc từ AI Run record.
  - Đảm bảo exception handling trong Spring Boot filter sạch stack trace/provider secret trước khi trả về cho client.
- **Dependency thực sự chặn runtime:**
  - Cơ chế bắt lỗi và mapping status trong GlobalExceptionHandler của Spring Boot.
- **Có thể phát triển độc lập:**
  - Mock provider trong FastAPI có thể giả lập trả về các mã lỗi mẫu (TIMEOUT, MODEL_ERROR, VALIDATION_FAILED) để kiểm chứng trước.

---

### 1.3. Capability Request, Ownership và Read Access
- **Đề xuất hiện có:**
  - Requester phải có capability hợp lệ (`STUDENT` hoặc `TRAINER`). Quyền `ADMIN` đơn lẻ không cấp quyền hỏi F01.
  - `actor.role` trong internal envelope do Spring Boot tự gán dựa trên authentication token, không nhận từ client payload.
  - Hỏi kiến thức chung không yêu cầu có `Coaching Relationship` đang active.
  - Quyền đọc lịch sử Run (khi mở rộng read-history API): chỉ authenticated owner (`requested_by`) mới được đọc; không cấp quyền mặc nhiên cho Trainer khác hoặc Student khác.
- **Điểm cần B review/xác nhận:**
  - Spring Security capability evaluator cho F01 endpoint: kiểm tra token JWT và xác thực trạng thái tài khoản/profile thực tế trong database tại thời điểm gọi, không chỉ dựa thuần túy vào JWT claim.
  - Chính sách ghi nhận quyền khi một user sở hữu đồng thời profile Student và Trainer.
- **Dependency thực sự chặn runtime:**
  - Spring Security configuration và User/Profile principal resolution.
- **Có thể phát triển độc lập:**
  - Kiểm tra schema context envelope với các vai trò `STUDENT`/`TRAINER` đã được unit test tự động bảo đảm.

---

### 1.4. Ranh giới Content Publishing và AI Chunking/Indexing
- **Đề xuất hiện có:**
  - Module Content (Spring Boot) quản lý tài liệu tri thức, versioning, kiểm tra integrity/hash, specialist review, và chuyển trạng thái sang `ACTIVE` hoặc `ARCHIVED`.
  - Ba trục trạng thái được phân định độc lập:
    1. **Review chuyên môn:** `APPROVED` / `CHANGES_REQUESTED` / `REJECTED` (lưu tại `knowledge_version_reviews`).
    2. **Technical readiness & hold:** `TECHNICAL_VERIFICATION_PASSED` / `HELD_AS_DRAFT` (metadata thuộc tính kỹ thuật, không phải enum publication).
    3. **Publication lifecycle:** Chỉ duy nhất `DRAFT`, `ACTIVE`, `ARCHIVED` (enum `knowledge_status`).
  - Module AI chỉ thực hiện chunking và indexing (tạo embedding) cho các Knowledge Version đã đạt trạng thái `ACTIVE`.
  - RAG runtime chỉ được phép tìm kiếm trong phạm vi `knowledgeScope.status = "ACTIVE"`.
  - Danh sách nguồn rỗng (`allowedSourceIds: []`) trả về `INSUFFICIENT_DATA`, không được tự ý fallback sang tài liệu DRAFT.
- **Điểm cần B review/xác nhận:**
  - Event/trigger giữa Content và AI: khi một Knowledge Version được xuất bản `ACTIVE`, Spring bắn domain event qua hàng đợi/job nội bộ hay gọi trực tiếp sang AI indexing service.
  - Xử lý khi tài liệu bị `ARCHIVED`: vô hiệu hóa eligibility tìm kiếm ngay lập tức trong vector query.
- **Dependency thực sự chặn runtime:**
  - Module Content quản lý trạng thái tài liệu trong PostgreSQL.
- **Có thể phát triển độc lập:**
  - Quá trình chuẩn hóa corpus và kiểm tra tính toàn vẹn (389 files, hash SHA-256) đã hoàn tất độc lập trong repository.

---

### 1.5. AI Run Persistence — Tái sử dụng Schema hiện có
- **Đề xuất hiện có:**
  - **Tái sử dụng schema hiện có** trong Flyway migrations đã merge (`V9__ai_and_knowledge.sql`, `V12__integrity_indexes_and_views.sql`, `V15__content_and_ai_governance.sql`, `V16__domain_completeness_and_invariants.sql`).
  - Các bảng đã có sẵn: `ai_runs`, `ai_context_snapshots`, `ai_run_input_references`, `ai_run_knowledge_chunks`, `ai_validation_results`, `ai_recommendations`.
  - Triển khai application/persistence layer trong Spring Boot sử dụng kiến trúc module với `JdbcTemplate` nhất quán với toàn bộ backend (không dùng JPA do toàn bộ hệ thống đang dùng JdbcTemplate và explicit SQL).
  - Bổ sung forward migration `V35__content_knowledge_governance_and_provenance.sql` (chọn version còn trống kế tiếp sau các migration Phase 1 đã merge trên main, không sửa migration cũ của B) để thêm các trường provenance, document_code, source_id, readiness, hold_reason, content_path, review_evidence_id, và permission/trigger guardrails.
- **Điểm cần B review/xác nhận:**
  - Đối chiếu các trường trong `ai_runs` (ví dụ `request_type`, `status`, `correlation_id`, `input_payload`, `output_payload`, `error_code`, `error_message`, `latency_ms`, `input_token_count`, `output_token_count`) với contract F01.
  - Chiến lược transactional boundary: Spring ghi bản ghi `ai_runs` với trạng thái `QUEUED/RUNNING`, sau khi nhận kết quả cập nhật `SUCCEEDED/FAILED/REJECTED_BY_VALIDATOR` trong transaction riêng biệt với model invocation.
- **Dependency thực sự chặn runtime:**
  - Khai báo repositories/services trong `services/backend`.
- **Có thể phát triển độc lập:**
  - Contract schema và fixture terminal result đã đầy đủ; FastAPI service mock có thể trả kết quả mà không phụ thuộc Spring database.

---

### 1.6. Service Authentication
- **Đề xuất hiện có:**
  - FastAPI internal endpoint chỉ chấp nhận yêu cầu xuất phát từ Spring Boot backend.
  - Client di động/web tuyệt đối không được gọi FastAPI trực tiếp.
- **Điểm cần B review/xác nhận:**
  - Lựa chọn giải pháp xác thực service-to-service: HTTP Shared Secret Header (e.g. `X-Internal-Service-Key`), JWT service account token do Spring ký, hoặc network-level isolation (Docker network/mTLS).
- **Dependency thực sự chặn runtime:**
  - Cấu hình mạng hoặc secret giữa backend Spring và container FastAPI.
- **Có thể phát triển độc lập:**
  - Trong môi trường local development/test, có thể cấu hình bypass hoặc dùng secret tĩnh qua biến môi trường.

---

### 1.7. Cấu hình vận hành A-13
- **Đề xuất hiện có:**
  - Phân tách rõ ràng giữa contract baseline và các thông số vận hành thực tế.
  - Không hardcode các giá trị ước lượng vào mã nguồn sản xuất.
- **Điểm cần B review/xác nhận:**
  - Provider & Model LLM lựa chọn (e.g. Gemini, OpenAI, Claude).
  - Không gian Embedding Model: schema V9 hiện dùng `vector(1536)` (chuẩn text-embedding-3-small / text-embedding-ada-002). Cần thống nhất model embedding tương thích trước khi nạp vector thật.
  - Ngân sách token (budget per request, max completion tokens).
  - Timeout (khuyến nghị ban đầu: timeout Spring -> FastAPI là 10s-15s), chính sách retry (không retry với lỗi 4xx).
  - Concurrency limit & Rate limiting per user.
  - Data Retention & PII logging policy: free-text câu hỏi có thể chứa dữ liệu riêng tư, cần xác định chính sách lưu trữ và che mờ (masking) trong audit log.
- **Dependency thực sự chặn runtime:**
  - API key nhà cung cấp và quyết định hạ tầng AI của chủ dự án (khi triển khai production).
- **Có thể phát triển độc lập:**
  - Mock provider giả lập hoàn toàn không cần API key thật, sinh câu trả lời giả lập grounded citations để hoàn thiện toàn bộ luồng tích hợp trước.

---

## 2. Triển khai Content Workflow tối thiểu (F09 / P2-M2 / A-30)

Mục tiêu là xây dựng luồng nghiệp vụ Content tối thiểu có kiểm tra quyền và ghi audit trên Spring Boot để quản lý và cung cấp dữ liệu `ACTIVE` hợp lệ cho RAG F01. Tài liệu corpus đã có biên bản phê duyệt chuyên môn (`data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json`), tái sử dụng bằng chứng này để liên kết provenance.

| Task ID | Hạng mục công việc | Chi tiết kỹ thuật & Tiêu chí nghiệm thu | Ràng buộc nghiệp vụ |
|---|---|---|---|
| **CONT-01** | **Import Corpus vào PostgreSQL** | - Đọc package tri thức đã chuẩn hóa (`who-2020`, `issn-protein-2017`, `ods-exercise-supplements`, `niddk-weight-factors`).<br>- Nạp vào `knowledge_documents` và `knowledge_versions` với trạng thái ban đầu `DRAFT`.<br>- Xác thực SHA-256 hash trước khi nạp. Bỏ qua evaluation/project guidance, không lưu binary PDF.<br>- Idempotent: cùng document/version/hash thì noop; trùng version khác hash báo 409 Conflict.<br>- Nạp metadata, lineage và liên kết review evidence `REV-PO-20261008-01`.<br>- Đánh dấu technical readiness: `TECHNICAL_VERIFICATION_PASSED` (WHO, ISSN, ODS) hoặc `HELD_AS_DRAFT` (NIDDK 05/06 có hold_reason). | Tái sử dụng bảng `knowledge_documents` và `knowledge_versions` với forward migration `V35`. Không tạo lại bảng cũ. |
| **CONT-02** | **Spring Domain Commands cho Content Lifecycle** | - Triển khai các command: `ReviewKnowledgeDocumentCommand`, `PublishKnowledgeVersionCommand`, `ArchiveKnowledgeVersionCommand`.<br>- Vòng đời publication: `DRAFT` → `ACTIVE` → `ARCHIVED`.<br>- Guardrails Publish: Phải có quyền `KNOWLEDGE_PUBLISH`, review chuyên môn `APPROVED`, technical readiness `TECHNICAL_VERIFICATION_PASSED`. Nếu `HELD_AS_DRAFT` (như NIDDK 05/06) chặn tuyệt đối, từ chối publish. | Không cho phép edit in-place version đã `ACTIVE`. Muốn sửa phải tạo `DRAFT` version mới. |
| **CONT-03** | **Permission & Immutable Audit** | - Phân quyền tối thiểu: `KNOWLEDGE_IMPORT`, `KNOWLEDGE_REVIEW`, `KNOWLEDGE_PUBLISH`, `KNOWLEDGE_ARCHIVE`.<br>- Kiểm tra quyền active trong SecurityContext/DB, không chỉ dựa vào JWT claim.<br>- Mọi thao tác import/review/publish/archive ghi nhận vào `audit_events` với actor, timestamp, document, version, content_hash và reason. | Phân quyền nghiêm ngặt. Thao tác thất bại phải fail-closed và rollback transaction. |
| **CONT-04** | **ACTIVE-only Eligibility Guard & AI Query Contract** | - Cung cấp query service cho RAG: chỉ trả về các Knowledge Document/Version có `status = 'ACTIVE'`.<br>- Tuyệt đối không trả về `DRAFT`, `HELD_AS_DRAFT` hoặc `ARCHIVED`.<br>- Hỗ trợ lọc theo `allowedSourceIds`: nếu danh sách rỗng `[]` thì trả về kết quả rỗng (không có nguồn). | RAG không bao giờ được chạm vào tài liệu chưa kiểm duyệt. NIDDK 05/06 giữ nguyên `HELD_AS_DRAFT`. |
| **CONT-05** | **Kiểm thử Content Lifecycle** | - Unit & Integration tests cho các kịch bản:<br>  + Import thành công, deduplication/conflict detection.<br>  + Publish thành công khi đủ điều kiện và quyền.<br>  + Từ chối publish khi thiếu quyền (403 Forbidden).<br>  + Từ chối publish khi `HELD_AS_DRAFT` (422/400 Bad Request).<br>  + Audit ghi nhận đầy đủ sau mỗi thao tác.<br>  + RAG query loại trừ hoàn toàn các tài liệu DRAFT/ARCHIVED. | Kiểm thử tự động hóa bằng JUnit 5 & Mockito / Spring Boot Test. |
| **CONT-06** | **Tách biệt Content Publishing khỏi AI Indexing** | - Content publishing hoàn thành trong transaction của Spring Boot.<br>- Indexing / Embedding được kích hoạt bất đồng bộ qua event hoặc background job, không làm nghẽn hoặc rollback transaction xuất bản tài liệu. Tuyệt đối không gọi provider LLM trong transaction publish. | Đảm bảo tính độc lập và khả năng phục hồi giữa hai module. |

| **CONT-07** | **Outbox Claim Fencing & Batch Transaction Isolation** | - Thêm forward migration `V36__outbox_claim_fencing_and_attempt_guards.sql`: cột `claim_token uuid`, constraint `max_attempts > 0`, `attempt_count >= 0`, index fencing.<br>- Tách `KnowledgeIndexingOutboxWorker` độc lập: claim commit ngay (`REQUIRES_NEW`), execute chạy với caller transaction suspended (`PROPAGATION_NOT_SUPPORTED`), complete/fail commit riêng (`REQUIRES_NEW`).<br>- Fencing token: kiểm tra `claim_token = ? AND status = 'RUNNING' AND lease_expires_at >= clock_timestamp()`, reject stale worker.<br>- Giới hạn attempts: `reapExpiredRuns()` chuyển runs hết attempt sang terminal `FAILED`, ngăn reclaim lặp vô tận.<br>- Retry failure dùng khoảng thời gian hoãn cố định 30 giây (fixed delay 30s, không phải exponential backoff).<br>- `INSERT ... ON CONFLICT (operation_key) ... DO NOTHING RETURNING *`: không abort transaction Postgres, intent mismatch ném `ContentConflictException` (409), rollback caller rollback sạch outbox. | Kiểm chứng bằng PostgreSQL Testcontainers thật. |
| **CONT-08** | **Full-Lifecycle Operation Identity, Transaction Suspension & Fencing Completion Logging** | - Thêm forward migration `V37__outbox_full_lifecycle_operation_identity.sql`: constraint toàn lifecycle `UNIQUE (operation_key)`, `NOT NULL` và check không rỗng, đối soát legacy null (`LEGACY:<id>`), fail chẩn đoán nếu có legacy duplicates.<br>- Subquery claim và `reapExpiredRuns()` bảo đảm kiểm tra `attempt_count < max_attempts` cho cả `QUEUED` và `RUNNING` hết lease.<br>- Worker execution đình chỉ caller transaction bằng `TransactionTemplate` với `PROPAGATION_NOT_SUPPORTED` (`isActualTransactionActive() == false`); worker không tham gia transaction của caller; việc đình chỉ này không tự giải phóng lock hoặc connection mà caller đã giữ trước đó.<br>- Strict claim-token fencing: truyền kết quả update từ repository qua `markSuccessInTx`, chỉ log completion thành công khi `updated` xác nhận; khi stale token hoặc lease hết hạn, ghi rõ completion bị từ chối; fencing rejection không gọi `markFailed` để tránh ghi đè claim của worker khác; bảo toàn semantics đếm job đã nhận xử lý trong batch.<br>- Ranh giới thực thi: `BOUNDARY_VERIFICATION_ONLY` với `real_embeddings = false`; chưa thiết lập retrieval index readiness thật. | Kiểm chứng bằng PostgreSQL Testcontainers thật (`KnowledgeIndexingOutboxIntegrationTest` 16/16 passed, `KnowledgeIndexingOutboxConsumerTest` 5/5 passed, `V35ContentKnowledgeGovernanceMigrationIntegrationTest` 9/9 passed). |

---

## 3. Ghi nhận phân công và theo dõi Issue

- **Developer phụ trách Content (Đợt này):** Developer A (sở hữu Content import/version/review/publish/archive, outbox processing, permission, audit, query contract cho AI).
- **Developer Phase 1:** Developer B (tiếp tục phạm vi Phase 1).
- **Phân công tích hợp khác:** UNASSIGNED / PENDING CONFIRMATION.
- **Reviewer:** B (Backend / Phase 1) và Project Owner — TRẠNG THÁI: PENDING REVIEW (chưa xác nhận phiên review).
- **File paths chịu tác động chính khi triển khai:**
  - `services/backend/src/main/resources/db/migration/V35__content_knowledge_governance_and_provenance.sql`
  - `services/backend/src/main/resources/db/migration/V36__outbox_claim_fencing_and_attempt_guards.sql`
  - `services/backend/src/main/resources/db/migration/V37__outbox_full_lifecycle_operation_identity.sql`
  - `services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/...`
  - `services/backend/src/test/java/com/fitnesscoaching/platform/KnowledgeIndexingOutboxIntegrationTest.java`
  - `services/backend/src/test/java/com/fitnesscoaching/platform/V35ContentKnowledgeGovernanceMigrationIntegrationTest.java`
  - `contracts/openapi/openapi.yaml`
- **Test scenario IDs liên quan (theo Phase 2 Implementation Plan):**
  - **T12:** Knowledge DRAFT/ARCHIVED, cache cũ, nội dung có prompt injection (Kỳ vọng: Không retrieve nguồn không hợp lệ; không thực thi chỉ dẫn vượt quyền - F01/F02/F03/F09).
  - **T13:** Thiếu căn cứ, schema sai, Exercise ID giả, model timeout (Kỳ vọng: Không tạo recommendation có thể áp dụng; có trạng thái/fallback - F01-F08/F10).
  - **T20:** Publish knowledge/config nhạy cảm thiếu quyền/step-up (Kỳ vọng: Bị chặn; publish hợp lệ có immutable audit - F09/F10).
  - **T27:** Hiển thị và thao tác INFORMATION/PROPOSAL/ESTIMATE (Kỳ vọng: INFORMATION chỉ xem; PROPOSAL Accept/Reject đúng quyền; ESTIMATE Confirm/Correct qua Nutrition; backend từ chối gửi nhầm loại payload vào mutation khác - F01-F08).
  - **T28:** Embedding sai dimension hoặc khác model space dù cùng dimension; đổi index (Kỳ vọng: Cấu hình không tương thích bị chặn; query/index switch và rollback đồng bộ; không mất source/version lineage - F01/F02/F03/F09).
