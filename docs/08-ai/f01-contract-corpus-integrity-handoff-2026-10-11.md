# Bàn giao F01 contract và corpus integrity — 11/10/2026

## 1. Phạm vi và điểm dừng của Antigravity

- **Branch:** `feat/phase-2-f01-contract-corpus-integrity`
- **HEAD:** `a5348ea` (main baseline trước khi làm việc).
- **Nguyên tắc Git:** Không tạo branch mới, không commit/push/pull/merge/rebase/reset/restore/stash, không sửa `.git` hoặc Git configuration. Toàn bộ thay đổi nằm trong working tree để người dùng và B review.

Sau đợt tự review trước đó, các vấn đề của F01 contract đã được hoàn thiện dứt điểm:
1. Chốt validation date-time: kiểm tra ngày thực tế, năm nhuận, timezone offset, fractional seconds; áp dụng giới hạn contract F01 chỉ nhận seconds `00–59`, chặn mọi timestamp có `second=60` (leap seconds).
2. Chặn source ID rỗng và sai quy ước trong `f01-internal-context-envelope.schema.json`.
3. Chuẩn hóa kế hoạch AI Run persistence: tái sử dụng schema hiện có (V9/V12/V15/V16), triển khai persistence layer, không tạo lại bảng.
4. Lập gói review checklist với B và backlog chuẩn bị cho Content workflow tối thiểu (P2-M2).

## 2. Chi tiết các điểm hoàn thiện contract

### 2.1. Validation date-time và giới hạn seconds 00–59

- **Vấn đề ban đầu:** Validator ban đầu chấp nhận `asOf: "2026-99-99T99:99:99Z"`, tháng 13, ngày 31 tháng 4, ngày 29 tháng 2 năm không nhuận (2026), giờ 99, offset không hợp lệ. Khi cho phép `second=60` nếu `minute=59`, điều kiện này chưa đủ để khẳng định timestamp leap second thực sự hợp lệ theo lịch thiên văn.
- **Quyết định cho F01:**
  - Áp dụng lựa chọn an toàn và dứt khoát: **chỉ chấp nhận seconds 00–59**, chặn mọi timestamp có `second=60`.
  - Ghi rõ đây là giới hạn hỗ trợ có chủ đích của contract F01 đợt đầu, không tuyên bố validator hỗ trợ toàn bộ trường hợp leap second của RFC 3339.
  - Giữ đầy đủ validation ngày lịch thực tế, tính năm nhuận chính xác (`(year % 4 === 0 && year % 100 !== 0) || (year % 400 === 0)`), timezone offset (`Z`, `+07:00`) và fractional seconds.
  - Không dựa vào `Date.parse()` để tránh lỗi tự động trượt/chuẩn hóa ngày của JavaScript Date.
- **Kiểm chứng:**
  - Đã thêm regression test tái hiện việc timestamp có giây 60 từng được chấp nhận và nay bị chặn:
    - `2026-10-11T00:59:60Z` bị chặn.
    - `2016-12-31T23:59:60Z` (thời điểm leap second lịch sử) cũng bị chặn theo giới hạn đã chọn.
    - Các timestamp hợp lệ với `seconds=59`, UTC, `+07:00` và fractional seconds tiếp tục được chấp nhận.
    - Kiểm tra đồng thời cả `result.asOf` và internal `envelope.asOf`.
  - Helper dùng chung [tools/schema-validator.mjs](schema-validator.mjs) phục vụ thống nhất cả validator CLI và contract tests.

### 2.2. Chặn source ID rỗng trong internal context envelope

- **Vấn đề ban đầu:** `f01-internal-context-envelope.schema.json` chấp nhận `allowedSourceIds: [""]` hoặc chuỗi khoảng trắng do thiếu pattern.
- **Cách sửa:**
  - Thêm `pattern: "^[A-Z0-9-]+$"` cho từng phần tử của `allowedSourceIds`, đồng bộ tuyệt đối với `citation.schema.json` và `KnowledgeCitation.sourceId` trong OpenAPI.
  - Chặn chuỗi rỗng `""`, chuỗi chỉ có khoảng trắng `"   "`, chuỗi có ký tự đặc biệt hoặc chữ thường.
  - Tiếp tục chấp nhận `allowedSourceIds: []` (`minItems: 0`) đại diện cho trường hợp server không tìm thấy nguồn tri thức ACTIVE phù hợp (dẫn đến kết quả `INSUFFICIENT_DATA`), không mở rộng quyền sang DRAFT/ARCHIVED.
  - Giữ `uniqueItems: true` để chặn ID trùng lặp.
  - Không hard-code enum 4 nguồn để Spring/Content tiếp tục xác thực tính hợp lệ động.

### 2.3. Kế hoạch AI Run Persistence

- **Đối chiếu Flyway:** Schema cơ sở dữ liệu cho AI đã được tạo và làm cứng trong các migration trước đó:
  - `V9__ai_and_knowledge.sql`: bảng `ai_runs`, `ai_context_snapshots`, `ai_run_input_references`, `ai_run_knowledge_chunks`, `ai_validation_results`, `ai_recommendations`, `knowledge_documents`, `knowledge_versions`, `knowledge_chunks`, `knowledge_embeddings`.
  - `V12__integrity_indexes_and_views.sql`: các index hiệu năng và toàn vẹn.
  - `V15__content_and_ai_governance.sql` và `V16__domain_completeness_and_invariants.sql`: triggers, constraints bảo vệ version content và audit.
- **Định hướng triển khai:**
### 2.3. Triển khai Content Workflow tối thiểu và hoàn thiện Review Findings (F09 / P2-M2 / A-30)

- **Quyền sở hữu và phân công:** Developer A sở hữu Content module (import, version, review, publish, archive, permission, audit, query contract cho AI). Developer B tiếp tục Phase 1.
- **Ranh giới 3 trục độc lập:**
  1. *Review chuyên môn:* `APPROVED`, `CHANGES_REQUESTED`, `REJECTED` (lưu trong `knowledge_version_reviews` với bằng chứng review, chữ ký, hash phê duyệt).
  2. *Technical readiness & hold:* `TECHNICAL_VERIFICATION_PASSED`, `HELD_AS_DRAFT`, `DRAFT_STAGED` (lưu trong `knowledge_versions.technical_readiness` và `hold_reason`).
  3. *Publication lifecycle:* Duy nhất `DRAFT`, `ACTIVE`, `ARCHIVED` (enum `knowledge_status`).
- **Sửa import tự cấp approval:**
  - `ContentImportService` không tự tạo bản ghi review `APPROVED` dựa trên chuỗi rỗng/tùy ý từ client.
  - Sử dụng `SpecialistReviewEvidenceRegistry` nạp từ `data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json` và `derivative-index.json`.
  - Kiểm chứng nghiêm ngặt binding: `documentId`, `versionLabel`, `contentPath`, `contentHash` SHA-256 và lineage base hash.
  - Tái sử dụng bằng chứng phê duyệt chuyên môn đã có (`REV-PO-20261008-01`), bảo toàn danh tính và vai trò reviewer gốc (`PROJECT_OWNER`, `Chủ dự án...`), không ghi đè importer thành reviewer gốc.
  - Technical readiness và hold reason được trích xuất từ trusted index của server, không nhận trực tiếp từ client payload.
  - Evidence không hợp lệ bị từ chối ngay lập tức (fail-closed, HTTP 400 `CONTENT_VALIDATION_FAILED`).
- **Siết publish readiness và review có hiệu lực:**
  - `ContentLifecycleService` và trigger DB chỉ cho phép publish khi `technical_readiness == TECHNICAL_VERIFICATION_PASSED`. Trạng thái `DRAFT_STAGED` hoặc `HELD_AS_DRAFT` (như NIDDK 05/06) bị từ chối fail-closed.
  - Review có hiệu lực phải là review mới nhất (`ORDER BY reviewed_at DESC, id DESC LIMIT 1`) với quyết định `APPROVED` và khớp `content_hash`.
  - Tính bất biến (Immutability): Phiên bản đã `ACTIVE` hoặc `ARCHIVED` không thể chỉnh sửa nội dung; được bảo vệ kép bởi trigger database `protect_governed_version_content()` (từ V16).
- **Bảo vệ Concurrency & Idempotency:**
  - Row locking (`findByIdForUpdate`) và conditional update (`UPDATE ... WHERE status = 'DRAFT'`) đảm bảo khi nhiều tiến trình publish đồng thời, đúng 1 request thành công và các request còn lại trả về HTTP 409 Conflict an toàn.
  - Database unique index `uq_knowledge_versions_doc_version_label` trên `(knowledge_document_id, version_label)` ngăn chặn race condition khi import.
- **Chuẩn hóa Audit JSON và Transaction Rollback:**
  - Sử dụng `tools.jackson.databind.ObjectMapper` tuần tự hóa audit payload, bảo vệ trích dẫn, escape Unicode và backslashes.
  - Bổ sung `UserRoleQuery` để giải quyết vai trò actor chính xác (`ADMIN`), loại bỏ hardcode vai trò.
  - Thao tác audit thất bại sẽ rollback toàn bộ transaction nghiệp vụ (fail-closed).
- **Hoàn thiện ranh giới Indexing qua Durable Outbox Queue:**
  - Bổ sung bảng `fitness.knowledge_processing_runs` lưu hàng đợi outbox bền vững (`status = 'QUEUED'`) ngay trong transaction publish của Spring Boot.
  - `LoggingAiIndexingNotificationAdapter` và `KnowledgeIndexingOutboxConsumer` xử lý outbox run sau khi commit, kiểm tra trạng thái eligibility (phiên bản bị `ARCHIVED` trước khi index sẽ bị hủy bỏ ngay lập tức). Tuyệt đối không gọi mô hình AI bên trong transaction publish.
- **Rà soát quyền và Query Boundary:**
  - Toàn bộ read endpoint của Admin Content Controller kiểm tra tài khoản ACTIVE và quyền `KNOWLEDGE_IMPORT` / `KNOWLEDGE_REVIEW` / `KNOWLEDGE_PUBLISH`.
  - `KnowledgeQueryService` dùng regex whitelist sanitize `allowedSourceIds` (chặn SQL injection và path traversal), nghiêm ngặt chỉ trả về các phiên bản `ACTIVE`.
- **Flyway forward migration:**
  - `V35__content_knowledge_governance_and_provenance.sql` bổ sung các cột governance, trigger `validate_knowledge_publish()`, bảng `knowledge_processing_runs` index, và quyền Admin.

---

## 3. Kết quả kiểm tra local thực tế

| Lệnh kiểm tra | Kết quả | Chi tiết |
|---|---|---|
| `npm run test:contracts` | **21/21 PASS** | Regression tests cho RFC 3339 date-time, leap seconds (second=60 bị chặn, second=59 hợp lệ) và envelope allowedSourceIds |
| `npm run validate:contracts` | **PASS** | Redocly lint OpenAPI hợp lệ; 5 schemas, 20 fixtures PASS với Ajv 2020 |
| `npm run test:knowledge` | **25/25 PASS** | Toàn bộ bài kiểm tra tri thức, tính toàn vẹn corpus và bộ lọc Git checkout autocrlf |
| `npm run validate:knowledge` | **PASS** | 4 sources, 73 articles, 114 candidate cases, 361 text files, 2 binary assets, ACTIVE=0; 11 derivatives |
| `node tools/validate-knowledge.mjs --check-local-assets` | **PASS** | Hai file PDF local có đầy đủ và khớp đúng sha256 |
| `npm run validate:ux-docs` | **PASS** | 18/18 files tài liệu UX hợp lệ |
| `git diff --check` | **PASS** | Không có conflict markers hay whitespace errors |
| `mvn test` (Content Unit Tests) | **35/35 PASS** | `ContentImportServiceUnitTest` (6), `ContentLifecycleServiceUnitTest` (6), `KnowledgeIndexingOutboxConsumerTest` (5), `KnowledgeQueryServiceUnitTest` (5), `SpecialistReviewEvidenceRegistryTest` (13) |
| `mvn test` (Flyway V35-V37 Migration Test) | **9/9 PASS** | `V35ContentKnowledgeGovernanceMigrationIntegrationTest` kiểm tra clean migration lên V35-V37, legacy multi-version backfill an toàn với immutability trigger, sequence review đơn điệu, trigger publish guard (HELD_AS_DRAFT, DRAFT_STAGED, review hash mismatch/blank/null), DB immutability, outbox claim fencing token & attempt guards (V36), full-lifecycle operation identity uniqueness constraint (V37), và reconciliation legacy nulls/diagnostic fail duplicates (V37) |
| `mvn test` (Outbox Integration Test) | **16/16 PASS** | `KnowledgeIndexingOutboxIntegrationTest` kiểm chứng PostgreSQL Testcontainers: full-lifecycle operation identity uniqueness, idempotency return existing run cùng intent, conflict 409 khi mismatch intent, validate blank operation key (400), attempt limits cho cả QUEUED và RUNNING, caller transaction suspension (`PROPAGATION_NOT_SUPPORTED` - suspend transaction caller, worker không tham gia caller tx, suspend không tự giải phóng lock/connection caller đã giữ), fixed delay retry 30s (không phải exponential), strict claim token fencing, chỉ log completion khi update thành công (không log khi bị fencing từ chối, không ghi đè job worker khác), reap terminal FAILED |
| `mvn test` (Content Integration Test) | **3/3 PASS** | `ContentManagementIntegrationTest` kiểm tra trọn vẹn: Fake evidence rejection (400), Provenance conflict (409), Snapshot unverified draft publish guard (409), Authentic review evidence import (200), Publish guardrail, DB immutability, Durable outbox atomic claim & stats (`real_embeddings: false`, `mode: BOUNDARY_VERIFICATION_ONLY`), Concurrent outbox claim (`SKIP LOCKED`), AI knowledge query authorization (401 unauth, 403 suspended, 200 student active), Archive eligibility, Concurrent publish idempotency (409) |

---

## 4. Bảo toàn tính toàn vẹn của Corpus

- Toàn bộ 389 file corpus được track dưới `data/knowledge/` giữ nguyên byte giống HEAD.
- Không sửa nội dung corpus, manifest/hash, approval records hoặc trạng thái publication.
- Corpus vẫn giữ nguyên trạng thái DRAFT, `retrieval_enabled = false`, `ACTIVE = 0`; 2 bài NIDDK 05/06 giữ `HELD_AS_DRAFT`.
- Không chạy reset/restore/renormalize hoặc sửa hash để làm sạch hiển thị Git status do stat/attributes trên Windows.

---

## 5. Trạng thái Files và Git

### Tracked có diff nội dung:
- `.github/workflows/ci.yml` (bổ sung test:contracts vào CI)
- `contracts/openapi/openapi.yaml` (F01 endpoint, schemas F01KnowledgeQueryRequest, KnowledgeCitation, F01KnowledgeQueryResponse, Admin Content APIs)
- `docs/08-ai/README.md` (cập nhật tài liệu AI contract)
- `package.json` (script test:contracts, validate:contracts, validate:ai-schemas)
- `services/backend/src/main/java/com/fitnesscoaching/platform/common/exception/GlobalExceptionHandler.java` (bổ sung exception handler cho Content)
- `tools/validate-ai-schemas.mjs` (tích hợp createAjv dùng chung, đăng ký 2 fixture mới)
- `tools/validate-knowledge.test.mjs` (test checkout filter)

### Untracked được chuẩn bị để review:
- `.gitattributes` (bảo vệ bytes corpus khi checkout trên Windows)
- `contracts/ai-schemas/citation.schema.json`
- `contracts/ai-schemas/f01-internal-context-envelope.schema.json`
- `contracts/ai-schemas/f01-knowledge-query-request.schema.json`
- `contracts/ai-schemas/f01-knowledge-query-result.schema.json`
- `contracts/ai-schemas/fixtures/f01/` (20 fixtures)
- `tools/schema-validator.mjs` (helper validation dùng chung cho date-time và uuid)
- `tools/validate-contracts.test.mjs` (21 contract tests kiểm tra toàn diện schema, OpenAPI parity, boundaries)
- `docs/08-ai/f01-general-knowledge-contract.md` (đặc tả contract F01)
- `docs/08-ai/f01-integration-review-checklist-b.md` (gói checklist review kỹ thuật với B, cập nhật phân công A và ranh giới 3 trục)
- `docs/08-ai/f01-contract-corpus-integrity-handoff-2026-10-11.md` (báo cáo bàn giao này)
- `services/backend/src/main/resources/db/migration/V35__content_knowledge_governance_and_provenance.sql` (Flyway migration V35)
- `services/backend/src/main/resources/db/migration/V36__outbox_claim_fencing_and_attempt_guards.sql` (Flyway migration V36 - outbox claim fencing & attempt guards)
- `services/backend/src/main/resources/db/migration/V37__outbox_full_lifecycle_operation_identity.sql` (Flyway migration V37 - outbox full-lifecycle operation identity uniqueness)
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/**` (Triển khai module Content Spring Boot & KnowledgeIndexingOutboxWorker)
- `services/backend/src/main/java/com/fitnesscoaching/platform/common/exception/Content*.java` (Content domain exceptions)
- `services/backend/src/test/java/com/fitnesscoaching/platform/ContentManagementIntegrationTest.java` (Integration test E2E)
- `services/backend/src/test/java/com/fitnesscoaching/platform/KnowledgeIndexingOutboxIntegrationTest.java` (PostgreSQL integration test cho 7 outbox review findings)
- `services/backend/src/test/java/com/fitnesscoaching/platform/V35ContentKnowledgeGovernanceMigrationIntegrationTest.java` (Migration & trigger integration test cho V35-V37)
- `services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/**` (Unit tests cho Content services)

---

## 6. Giới hạn và các phần chưa được kiểm chứng

- **GitHub CI:** Đã khai báo trong `.github/workflows/ci.yml`, chưa chạy trên remote GitHub runner.
- **FastAPI AI Service Runtime:** Backend Spring Boot Content workflow và Query contract đã hoàn thành 100%; AI service FastAPI (embedding generation, pgvector retrieval, prompt generation, provider call) chưa triển khai runtime.
- **Retrieval & Model benchmark:** Chưa benchmark chất lượng embedding hay mô hình LLM với mock/real provider.
- **T01–T28:** Các bài kiểm tra T12, T13, T27, T28 liên quan đến Content governance, publish guard, và audit log đã được kiểm chứng qua `ContentManagementIntegrationTest` và migration test. Toàn bộ T01–T28 sẽ được nghiệm thu khi tích hợp đầy đủ các module.

---

## 7. Gói review kỹ thuật với B và Bước tiếp theo

Chi tiết toàn bộ các hạng mục rà soát cùng B đã được cập nhật tại:
👉 **[docs/08-ai/f01-integration-review-checklist-b.md](f01-integration-review-checklist-b.md)**

Tóm tắt các điểm trọng tâm cần review với B:
1. **URI & DTO:** Khớp nối Spring Boot Java DTO và FastAPI Pydantic schema với contract JSON Schema đã chốt.
2. **Identity & Mapping:** Spring sinh `requestId`, FastAPI echo `runId`; ánh xạ mã lỗi/HTTP (200, 502, 504, 500) và filter sanitized error.
3. **Quyền & Ownership:** Requester (STUDENT/TRAINER) sở hữu Run; không cho đọc chéo; Admin đơn lẻ không có quyền hỏi F01.
4. **Ranh giới Content và AI:** Spring/Content quản lý xuất bản `ACTIVE`; RAG chỉ truy vấn nguồn `ACTIVE`; danh sách nguồn rỗng trả về `INSUFFICIENT_DATA`.
5. **AI Run Persistence:** Tái sử dụng schema hiện có (V9/V12/V15/V16), triển khai persistence layer, không tạo lại bảng.
6. **Service Authentication:** Lựa chọn cơ chế bảo vệ gọi nội bộ Spring → FastAPI (shared secret header / service token).
7. **Cấu hình vận hành A-13:** Provider, model, embedding space (chiều 1536), ngân sách token, timeout/retry, rate limit và retention.
