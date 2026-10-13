# Contract F01 — Kiến thức thể hình chung

**Cập nhật:** 11/10/2026 · **Phiên bản contract:** 1.0.0  
**Phạm vi:** F01, P2-M0 A-10/A-11; chuẩn bị P2-M2.  
**Trạng thái:** CONTRACT ONLY — chưa có endpoint runtime; review tích hợp với B chưa thực hiện.  
**Module sở hữu:** AI điều phối; Content quản lý tri thức; Audit ghi nhận nghiệp vụ.

Đọc [phạm vi](../00-project-overview/phase-2-release-scope.md), [ma trận dữ liệu/quyền](phase-2-feature-data-authority-matrix.md) và [kế hoạch](../07-development/phase-2-implementation-plan.md). Phê duyệt D01–D07 không chứng minh contract mới đã được B review, runtime đã hoạt động hoặc model đã đạt chất lượng.

## 1. Hai tầng giao tiếp

Client chỉ gọi Spring: `POST /api/v1/ai/knowledge/query`. OpenAPI đặt path `/ai/knowledge/query` dưới server `/api/v1`.

- [Public OpenAPI](../../contracts/openapi/openapi.yaml)
- [Public request](../../contracts/ai-schemas/f01-knowledge-query-request.schema.json)
- [Terminal result](../../contracts/ai-schemas/f01-knowledge-query-result.schema.json)
- [Citation](../../contracts/ai-schemas/citation.schema.json)
- [Internal envelope](../../contracts/ai-schemas/f01-internal-context-envelope.schema.json)

```json
{
  "question": "Nguyên tắc vận động cho người trưởng thành là gì?",
  "language": "vi"
}
```

Spring gọi internal endpoint dự kiến `POST /internal/v1/knowledge/query` bằng envelope do server dựng: requestId, requestType, schemaVersion, correlationId, actor, purpose, allowedActions, asOf, question, language, knowledgeScope và Exercise metadata tùy chọn. Internal caller phải được xác thực là Spring; cơ chế service authentication cần thống nhất khi triển khai. Không cho client gọi FastAPI trực tiếp.

`requestId` là AI Run ID do Spring tạo. FastAPI trả terminal result cùng schema, với `runId` echo requestId; Spring kiểm identity/correlation, validate và tự ghi trạng thái cuối. FastAPI không tự cấp quyền, tạo bản ghi Run hoặc ghi domain. correlationId chỉ để truy vết, không phải idempotency key.

Đợt này khai báo luồng terminal đồng bộ; không khai báo polling/read-history endpoint. QUEUED/RUNNING/CANCELLED vẫn là lifecycle DB theo baseline. Nếu cần job nền/read history, bổ sung contract ở P2-M2/F10 trước khi tích hợp client.

## 2. Actor, quyền và dữ liệu

- Actor có capability Student hoặc Trainer hợp lệ; hỏi kiến thức chung không cần coaching relationship.
- actor.role do Spring chọn từ capability đã kiểm chứng, không nhận từ client. Admin đơn lẻ không cấp quyền F01; account có capability Student/Trainer dùng capability tương ứng.
- Chủ Run là authenticated requester. Không mặc nhiên cho Trainer khác, Student khác hoặc Admin đọc lịch sử. Runtime kiểm quyền lúc request, trước trả output và khi đọc lại; quyền vận hành/evaluation Admin thuộc F10 riêng.
- Không có subject Student khác hoặc profile/Goal/measurement/Workout history lấy từ domain trong đợt này. Free text vẫn có thể chứa dữ liệu riêng tư do người hỏi nhập; cần policy retention/logging và không suy diễn quyền từ nội dung câu hỏi.
- Exercise tham chiếu phải được module sở hữu xác minh ACTIVE; UUID đúng hình dạng chưa chứng minh catalog eligibility.
- INFORMATION không tạo recommendation PENDING, không Accept/Reject/Apply và không sửa dữ liệu coaching/nutrition.

knowledgeScope do Spring cấp, chỉ dùng ACTIVE. Danh sách source rỗng trả thiếu bằng chứng; không mở rộng sang DRAFT/ARCHIVED. Source allowlist chưa cấp quyền mọi version: retrieval/output/cache phải kiểm eligibility của document/version tại thời điểm dùng.

## 3. Run status, result type và reason

| HTTP | runStatus | resultType | reasonCode | Payload |
|---|---|---|---|---|
| 200 | SUCCEEDED | INFORMATION | NONE hoặc không có | answer, ít nhất một citation, limitations, missingData |
| 200 | SUCCEEDED | INSUFFICIENT_DATA | MISSING_REQUIRED_CONTEXT / NO_RELEVANT_EVIDENCE | limitations, missingData không rỗng; không có answer |
| 200 | REJECTED_BY_VALIDATOR | BLOCKED | RULE_BLOCKED | limitations, missingData; không có answer/citation nội dung bị chặn |
| 502 | REJECTED_BY_VALIDATOR | Không có | VALIDATION_FAILED | sanitized error; không trả output bị từ chối |
| 502 | FAILED | Không có | MODEL_ERROR / PROVIDER_ERROR | sanitized error |
| 504 | FAILED | Không có | TIMEOUT | sanitized error |
| 500 | FAILED | Không có | PROCESSING_ERROR | sanitized error |

Request sai schema (400), thiếu xác thực (401), thiếu capability (403), Exercise không khả dụng (404) và feature disabled (503) dùng ErrorResponse chung; không bắt buộc tạo Run. Feature disabled dùng code SERVICE_UNAVAILABLE. Không lộ stack trace, raw provider/output payload hoặc secret trong error.message.

```json
{
  "runId": "44444444-4444-4444-8444-444444444444",
  "runStatus": "FAILED",
  "reasonCode": "TIMEOUT",
  "asOf": "2026-10-11T00:00:00Z",
  "error": { "code": "TIMEOUT", "message": "Không nhận được kết quả trong thời gian cho phép." }
}
```

Spring phải ánh xạ đúng HTTP theo trạng thái/reason; schema không tự thực thi mapping HTTP hoặc quyền. BLOCKED áp dụng policy đã review; đợt contract không tự tạo danh sách chất/thuốc bị cấm mới.

## 4. Citation và AI Run provenance

Citation cần documentId, version, sourceId và locator.sourcePath. Path tương đối trong package của source (đối chiếu package-index/derivative-index), không chứa đường dẫn tuyệt đối, backslash hoặc `..`. Heading/pageRange/sectionIndex tùy chọn; runtime xác minh locator hỗ trợ claim, page range đúng thứ tự và chunk đúng version.

Ví dụ cấu trúc tham chiếu snapshot đang DRAFT trong repo:

```json
{
  "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
  "version": "1.0.0",
  "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
  "locator": { "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md" }
}
```

Ví dụ/fixture chỉ kiểm cấu trúc, không tuyên bố nguồn ACTIVE hoặc answer đã được đánh giá. Runtime phải kiểm tồn tại, source/version/hash/lineage, quyền dùng và evidence hỗ trợ answer. Không ingest evaluation cases vào retrieval.

usage lưu token/latency nếu đã đo; thiếu là unknown, không tự điền 0. generationMetadata chứa schemaVersion, promptVersion, ruleVersion, provider, model; embeddingModelSpace/indexVersion nếu đã dùng retrieval. Trường này tùy chọn cho kết quả chưa gọi provider, nhưng phải lưu provenance đã biết khi chạy thật; mock được ghi rõ. Spring giữ ownership, source refs, versions, validation outcome, usage/latency và sanitized error trong AI Run theo A-28.

## 5. Giới hạn và giá trị vận hành

- Question: 5–1000 Unicode code points, có ký tự không phải whitespace; kiểm ở public/internal schema. Đây là giới hạn contract thử nghiệm cần B review trước runtime.
- Language: vi mặc định; en tùy chọn, không thêm release gate tiếng Anh.
- exerciseId/correlationId là UUID; trường thừa bị chặn bằng additionalProperties=false.
- asOf: Định dạng date-time theo chuẩn RFC 3339. Giới hạn hỗ trợ của contract F01 đợt này: chỉ chấp nhận seconds 00–59, chặn mọi timestamp có second=60 (leap seconds) thay vì tuyên bố hỗ trợ toàn bộ trường hợp leap second của RFC 3339. Giữ đầy đủ kiểm tra ngày lịch thực tế, năm nhuận, timezone offset (Z, +07:00) và fractional seconds.
- Provider/model, embedding space/dimension, ngân sách, timeout/retry/concurrency, request byte limit, rate limit và retention vẫn là A-13 cần chốt trước rollout. Không đưa số đoán thành cấu hình sản xuất đã duyệt.
- Baseline V9 dùng vector(1536); cùng dimension chưa chứng minh cùng model space. Không thêm migration ở đợt contract này.

## 6. Bảo toàn corpus khi checkout

[.gitattributes](../../.gitattributes) dùng -text cho packages/derivatives để giữ nguyên bytes, kể cả file vốn CRLF. Manifest/review JSON và tài liệu cấp corpus dùng LF. Không sửa hash/approval để hợp thức hóa checkout chuyển đổi. Attributes chỉ đi cùng clean checkout sau khi được đưa vào Git; đợt này giữ thay đổi ở working tree.

Corpus giữ DRAFT, retrieval_enabled=false, ACTIVE=0; NIDDK 05/06 giữ HELD_AS_DRAFT. Hai PDF ngoài Git cần kiểm riêng bằng --check-local-assets khi có local assets.

## 7. Kiểm chứng và bàn giao

Chạy npm run test:contracts, validate:contracts, test:knowledge, validate:knowledge, validate:ux-docs và git diff --check. CI chạy test:contracts.

Test kiểm request/citation, INFORMATION/PROPOSAL/ESTIMATE, lifecycle/error separation, scope envelope và public OpenAPI/JSON Schema parity. Đây là bằng chứng cấu trúc hỗ trợ T12/T13/T27/T28; chưa chứng minh runtime đạt các test đó, quyền được thực thi, retrieval ACTIVE-only hay chất lượng model.

Chi tiết các hạng mục thảo luận xem tại [Checklist review tích hợp với B](f01-integration-review-checklist-b.md).

Cần thống nhất với B:

1. Review URI/DTO, identity, lỗi/HTTP, ownership/read history; assignee/reviewer/paths/test IDs tại issue.
2. Ranh giới Content import/version/review/publish/archive, permission/audit với AI chunking/indexing; quyền dùng nguồn/cache sau archive.
3. A-13, service authentication và policy retention/logging của question/result.
4. AI Run persistence: Tái sử dụng schema hiện có (V9/V12/V15/V16: `ai_runs`, `ai_context_snapshots`, `ai_validation_results`, `ai_run_knowledge_chunks`), triển khai application/persistence layer và chỉ bổ sung forward migration cho phần thực sự thiếu. Không tạo lại bảng hoặc sửa migration đã tồn tại.

Việc tiếp theo: Content workflow tối thiểu → AI Run/Spring orchestration (tái sử dụng schema) → FastAPI/provider giả lập → ACTIVE-only retrieval/output validation → evaluation runner. Không triển khai runtime hoặc sửa domain Phase 1 của B trong đợt contract này.
