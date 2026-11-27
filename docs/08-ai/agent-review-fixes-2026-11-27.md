# Sửa lỗi sau review Fitness Agent — 27/11/2026

**Nhánh:** `feat/fitness-agent-upgrade`  
**Phạm vi:** AG-M3/M4/M5/M6; F01/F03/F05, module AI/Content và Mobile.  
**Thực hiện:** Codex trong phiên sửa lỗi do chủ dự án yêu cầu; chưa có sign-off reviewer độc lập.  
**Trạng thái:** sửa implementation và kiểm thử offline/integration hoàn tất cho các finding dưới đây; chưa phải nghiệm thu release.

Actor là Student hoặc Trainer có quyền hiện hành; subject do Spring ràng buộc với run. INFORMATION không áp dụng nghiệp vụ; proposal F03/F05 vẫn PENDING cho người có thẩm quyền. Quyền quyết định, lịch sử và source-of-truth giữ theo baseline Phase 2. Các kịch bản liên quan T03/T04/T12/T13/T23/T25/T27; đây là mapping phạm vi, không tuyên bố đã chạy toàn bộ T01–T28.

## 1. Tổng hợp finding và sửa đổi

| Finding | Sửa và evidence | Trạng thái |
|---|---|---|
| Citation chỉ kiểm ACTIVE toàn cục | `AgentCitationValidator` đối chiếu candidate snapshot đúng run và `evidenceChunkIds` do runner tạo từ observations sau rerank/MMR/context budget; kiểm document/version/source/path/section/heading, exact quote và eligibility hiện tại. Thiếu/mất evidence hoặc ID ngoài run bị chặn; không lọc bỏ citation sai rồi giữ answer. | FIXED, unit + HTTP E2E |
| Test E2E thay FastAPI bằng JSON stub | `AiAgentCrossLayerHttpE2EIntegrationTest` khởi chạy uvicorn production app/routes; chỉ override model dependency sang OpenAI adapter gọi HTTP fixture cục bộ. Agent loop, ToolGateway, RetrievalPipeline, Spring APIs và PostgreSQL đều chạy thật. | FIXED, 4/4 tests |
| operationKey trùng, khác message | So sánh message sau trim và trả 409 qua lifecycle conflict exception; không truncate operationKey quá dài thành key khác. | FIXED, integration |
| Thiếu cancel/late completion test | Barrier trong HTTP model fixture cho phép hủy khi turn PENDING; xác minh turn/run CANCELLED và assistant_message không bị ghi đè. Test timeout và account bị đình chỉ giữa lượt cũng chạy xuyên tầng. | FIXED, integration |
| Client tự khai TRAINER cho self conversation | Self scope luôn ghi STUDENT; actorRole đầu vào không tự nâng quyền. Khi đọc subject khác, kiểm coaching authority; list lọc conversation đã mất quyền. | FIXED, integration |
| Mobile không tải lịch sử, thiếu retry test | Fetch `getConversation` có AbortSignal, loading/error/reload states; chặn send khi chưa tải được history. Retry giữ message/operationKey/turnId; dedup turn trả về. | FIXED, Jest |
| Thiếu test CLARIFY/INSUFFICIENT_DATA/BLOCKED/malformed | Mock HTTP phủ các nhánh; parse final output bằng Pydantic, từ chối schema sai/extra fields. Loop có total asyncio deadline; native tools tắt parallel calls, privacy `store=false`. | FIXED, pytest |
| Search bỏ qua run authorization khi cancel/revoke | Trước mỗi search, ToolGateway gọi `/internal/v1/agent/tools/knowledge-access`; Spring kiểm run/actor/subject/quyền hiện tại. Test search bị deny không được gọi retrieval. | FIXED, unit + integration |
| Provider openai thiếu key tự fallback simulated | Không fallback âm thầm; runner trả FAILED/PROVIDER_ERROR. Default tool advertisement chỉ có read tools khi binding không chỉ định actions. | FIXED, pytest |
| F03 proposal mapping còn sai sau báo cáo cũ | Phát hiện thêm `WorkoutAdjustmentApiRequest.studentId` vẫn nhận runId; sửa sang subject đã verify, commandKey riêng và userReason đúng vị trí. Hai test gateway enabled mới chạy thật qua F03/F05 use case, recommendation PENDING và không đổi plan/schedule trước Accept. | FIXED, integration |

`evidenceChunkIds` là metadata nội bộ do runner tạo; không thuộc JSON final output mà model được phép sinh. Backend vẫn yêu cầu mọi ID có trong snapshot retrieval của run. Tracker eviction hoặc thiếu snapshot dẫn đến validation failure, không fallback sang kiểm ACTIVE toàn cục. F01 một lượt cũ giữ nguyên luồng riêng; pipeline nâng cấp áp dụng cho agent search.

## 2. Kiểm thử đã chạy sau sửa

| Phạm vi/lệnh | Kết quả | Giới hạn |
|---|---|---|
| Backend Maven `test` với 9 suites dưới đây | 119 passed, 0 failures/errors/skipped; BUILD SUCCESS | Selected regression suites; không phải toàn bộ backend tests |
| FastAPI `.venv/Scripts/ruff.exe check app tests` | PASS | Không tải model/provider thật |
| FastAPI `.venv/Scripts/python.exe -m pytest -q -p no:cacheprovider` | 120 passed | Hai deprecation warnings từ Starlette/TestClient hiện có |
| Mobile `npm run typecheck` | PASS | TypeScript compile |
| Mobile `npm run lint` | 0 errors; 12 warnings cũ ở CurrentGoalScreen/coachingQrScreens tests | Không thay các file ngoài phạm vi này |
| Mobile Jest aiAgentConversation + aiKnowledgeApi | 31 passed | React test renderer/API mocks; không phải thiết bị thật |
| `npm run validate:contracts` | OpenAPI PASS; 13 schemas và 52 fixtures PASS | Hai agent request/response schema được bổ sung |
| `npm run test:contracts` | 22 passed | Bộ contract regression hiện có |

Lệnh Backend (PowerShell, từ `services/backend`):

```powershell
.\mvnw.cmd '-Dtest=AgentCitationValidatorTest,F01RunEvidenceTrackerTest,ReciprocalRankFusionTest,KnowledgeRetrievalServiceTest,AiKnowledgeRetrievalIntegrationTest,AiAgentConversationAndToolGatewayIntegrationTest,AiAgentCrossLayerHttpE2EIntegrationTest,AiWorkoutAdjustmentFullSliceIntegrationTest,AiWorkoutRescheduleIntegrationTest' test
```

| Suite | Tests |
|---|---:|
| AgentCitationValidatorTest | 2 |
| F01RunEvidenceTrackerTest | 1 |
| ReciprocalRankFusionTest | 3 |
| KnowledgeRetrievalServiceTest | 13 |
| AiKnowledgeRetrievalIntegrationTest | 15 |
| AiAgentConversationAndToolGatewayIntegrationTest | 2 |
| AiAgentCrossLayerHttpE2EIntegrationTest | 4 |
| AiWorkoutAdjustmentFullSliceIntegrationTest | 32 |
| AiWorkoutRescheduleIntegrationTest | 47 |

Maven đã compile toàn bộ main/test source theo lifecycle chuẩn, không dùng `surefire:test` trên classes chọn lọc/stale. Không tái hiện lỗi compile F06 được báo trước đó; không sửa source F06. Testcontainers dùng `pgvector/pgvector:pg18` và Flyway V1–V67 trên database cô lập. XML trong `services/backend/target/surefire-reports/` và log `target/agent-fixes-verification.log` là artifact local, không cần commit; log DEBUG test chỉ chứa fixture tổng hợp.

HTTP fixture ở `services/ai-service/tests/agent_http_fixture.py`, chỉ import khi `AGENT_HTTP_E2E=true`, bind loopback. Test tự khởi chạy/dừng process. Có model/reranker HTTP fixtures và lịch sử clarification kiểm tra thực, không trả JSON cứng từ endpoint `/internal/v1/agent/turns`. Sai sót khởi tạo container và HTTP/2 upgrade trong harness đã được sửa trước lượt chạy PASS cuối. Không dùng key thật và không gọi dịch vụ trả phí.

## 3. Việc chưa đóng

- Live LLM/reranker quality evaluation, holdout labels, ablation/recall/EXPLAIN trên corpus thật, ngân sách và specialist sign-off vẫn OPEN.
- Chưa kiểm thử UX/E2E trên điện thoại thật. Jest và uvicorn E2E không thay thế kiểm thử thiết bị.
- Proposal enabled tests nay có đường success F03/F05 và guard dữ liệu thật; toàn bộ hai domain suites có apply/retry/concurrency regression. Chưa coi đây là live agent tự chọn tool/proposal có chất lượng; giữ `AI_AGENT_PROPOSALS_ENABLED=false` mặc định đến review/rollout riêng.
- F03 gateway hiện yêu cầu substitution (`EXERCISE_SUBSTITUTION`), không tuyên bố agent đã hỗ trợ mọi load/volume change type chỉ từ free-text requestedChange.
- Cancel chặn ghi answer muộn và deny tool tiếp theo; không cam kết abort từ xa socket/provider request đã chạy. Vòng lặp FastAPI có total deadline riêng.
- Evidence tracker hiện trong memory một Spring process. Chạy nhiều replica cần routing/cơ chế snapshot chia sẻ đã review; khi thiếu snapshot, validation phải fail closed.

Kết quả đủ để đưa bản sửa đi review code với evidence rõ ràng; không tự đóng G0–G6, không tuyên bố đủ điều kiện release. Không stage/commit/push, đổi branch hoặc chạy migration trên DB đang dùng.

## 4. Working tree v? b?n giao

Tr??c phi?n s?a ?? c? nhi?u thay ??i tracked/untracked c?a ph?n n?ng c?p agent. C?c thay ??i ?? ???c gi? l?i; kh?ng s?a F06, QR, CurrentGoal ho?c c?c lu?ng ngo?i ph?m vi. Phi?n n?y s?a l?p AI conversation/tool gateway/citation validation, Mobile history/retry, Python adapter/loop/auth, b? sung regression tests v? ??ng b? contracts/t?i li?u. Kh?ng s?a `.env`, Git metadata ho?c migration ?? c?.

`CANCELLED` ?? t?n t?i trong `AiRunStatus` v? enum `fitness.ai_run_status` t?i V1; kh?ng th?m enum/schema lifecycle m?i. `git diff --check` ?? ki?m tra; warning LF/CRLF c?a tracker c? s?n kh?ng ph?i l?i whitespace.

Git status cu?i (M = tracked modified, ?? = untracked; ch?a stage/commit/push):

```text
 M apps/mobile/src/features/ai-knowledge/AiKnowledgeScreen.tsx
 M contracts/openapi/openapi.yaml
 M docs/07-development/README.md
 M docs/08-ai/README.md
 M docs/README.md
 M services/ai-service/app/core/config.py
 M services/ai-service/app/main.py
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/application/service/F01RunEvidenceTracker.java
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/application/service/KnowledgeRetrievalService.java
 M services/backend/src/main/resources/application.yml
 M services/backend/src/test/java/com/fitnesscoaching/platform/AiKnowledgeRetrievalIntegrationTest.java
 M services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutAdjustmentFullSliceIntegrationTest.java
 M services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutRescheduleIntegrationTest.java
 M services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/application/service/KnowledgeRetrievalServiceTest.java
?? apps/mobile/src/__tests__/aiAgentConversation.test.tsx
?? apps/mobile/src/app/(app)/ai-knowledge/conversation.tsx
?? apps/mobile/src/features/ai-knowledge/AiAgentConversationScreen.tsx
?? apps/mobile/src/services/aiAgentApi.ts
?? apps/mobile/src/types/aiAgent.ts
?? contracts/ai-schemas/agent-turn-request.schema.json
?? contracts/ai-schemas/agent-turn-response.schema.json
?? docs/07-development/agent-upgrade-progress.md
?? docs/08-ai/agent-operations-runbook.md
?? docs/08-ai/agent-review-fixes-2026-11-27.md
?? docs/08-ai/agent-upgrade-roadmap.md
?? services/ai-service/app/agent/
?? services/ai-service/app/api/routes/internal_agent.py
?? services/ai-service/app/rag/context_budget.py
?? services/ai-service/app/rag/mmr.py
?? services/ai-service/app/rag/pipeline.py
?? services/ai-service/app/rag/reranker.py
?? services/ai-service/tests/agent_http_fixture.py
?? services/ai-service/tests/test_agent_loop.py
?? services/ai-service/tests/test_rag_pipeline.py
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AiAgentConversationController.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/InternalAgentToolController.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AgentCitationValidator.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AgentToolGatewayService.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiAgentConversationService.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/application/service/ReciprocalRankFusion.java
?? services/backend/src/main/resources/db/migration/V66__knowledge_lexical_search.sql
?? services/backend/src/main/resources/db/migration/V67__ai_agent_conversations.sql
?? services/backend/src/test/java/com/fitnesscoaching/platform/AiAgentConversationAndToolGatewayIntegrationTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/AiAgentCrossLayerHttpE2EIntegrationTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AgentCitationValidatorTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/application/service/F01RunEvidenceTrackerTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/application/service/ReciprocalRankFusionTest.java
```
