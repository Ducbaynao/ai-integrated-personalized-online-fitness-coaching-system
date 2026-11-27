# Mốc tạm dừng Fitness Agent — 27/11/2026

**Trạng thái công việc:** TẠM DỪNG theo yêu cầu chủ dự án; chưa nghiệm thu/release. Không chạy thêm provider khi đang tạm dừng.  
**Branch:** `feat/fitness-agent-upgrade`.  
**HEAD lúc ghi mốc:** `57e27f8f40ed385c931979e5b35cff660b9f9fa8`; working tree có nhiều thay đổi tracked/untracked. HEAD này là nền trước commit bàn giao, **không đại diện toàn bộ implementation đang chưa commit**.  
**Git:** Chủ dự án dự định commit/push để lưu branch, **chưa merge**. Phiên ghi mốc này không stage, commit, push hoặc merge; chưa xác minh remote đã nhận thay đổi.  
**Phạm vi:** AG-M0..M6, F01/F03/F04/F05 và conversation/read tools; không thay invariant về actor/subject/authority. Student/Trainer quyết định nghiệp vụ theo quyền; AI không tự Apply.

Đọc cùng [tracker](agent-upgrade-progress.md), [gói review](../08-ai/agent-review-readiness-2026-11-27.md), [roadmap](../08-ai/agent-upgrade-roadmap.md) và [runbook](../08-ai/agent-operations-runbook.md). Đây là snapshot hiện tại; các evidence cũ trong tracker giữ nguyên để truy vết, không phải blocker hiện hành nếu đã được bản sửa sau thay thế.

## 1. Con số tại thời điểm dừng

| Hạng mục | Giá trị | Giới hạn / nguồn |
|---|---|---|
| Corpus benchmark | 9 tài liệu ACTIVE WHO/NIDDK, 29 chunks / vectors, 1536 dimensions, index_ready=true | Corpus trong DB benchmark cô lập của các lượt chạy; không phải staging/production hoặc DB đang được giữ chạy |
| Migrations | V1–V68; nâng cấp V67 → V68 đã kiểm tra | Generated folded vector/backfill và GIN index đã có evidence trên DB cô lập |
| Dev dataset | 16 cases; toàn bộ nhãn vẫn PROVISIONAL | Chưa có specialist/technical reviewer xác nhận nhãn/chunk_relevance |
| Coverage retrieval | 5 full + 2 partial = 7 evaluable; 3 thiếu nguồn; 6 không có expected docs | Phải giữ mẫu số riêng theo loại bài đánh giá; 2 ca permission thuộc authorization integration |
| Holdout | Template rỗng; chưa mở/đánh giá | Không dùng các câu dev đã tuning làm holdout |
| Rerank benchmark | 10 cases, 7 evaluable; candidate_limit=15 đủ chứa chunk mục tiêu trong lượt đó | Chưa đủ kết luận nên bật mặc định |
| Live agent xác minh citation key mới nhất | 5 runs, 5 HTTP 200, 0 UNKNOWN, 0 truncation | 4 INFORMATION có citation được chấp nhận; 1 POLICY bị BLOCKED đúng contract, không gọi retrieval |
| Domain live cases | **CHƯA CHẠY: 0/5** | Mới có kế hoạch và HTTP E2E dùng model fixture; không suy từ prompt đề xuất ra kết quả |

## 2. Chi phí tích lũy cần mang sang phiên tiếp theo

Nguồn: [usage-and-cost-summary.json](../../reports/evaluation/ablations/live-agent-e2e-citation-retest-9docs-20261127/usage-and-cost-summary.json). Các phiên domain HTTP E2E/tổng hợp tài liệu sau đó không phát sinh paid calls theo evidence log.

| Provider | Lũy kế USD | Trần đợt thử nghiệm USD | Phần dư tính toán USD |
|---|---:|---:|---:|
| OpenAI | 0.036246 | 0.500000 | 0.463754 |
| Cohere | 0.060000 | 0.100000 | 0.040000 |
| Tổng số học hai provider | 0.096246 | 0.600000 | 0.503754 |

- Không chuyển ngân sách giữa hai provider. Đây không phải số dư tài khoản hoặc đối soát hóa đơn: OpenAI gồm LLM usage đo được và embedding ước tính; Cohere tính theo số calls/đơn giá lưu trong manifests.
- `unknown_requests=0` theo manifest cuối. Lượt 5 runs cuối: 9 LLM calls, 18,399 input + 934 output tokens; LLM $0.003320, embedding ước tính $0.000328, OpenAI đợt đó $0.003648. Cohere 3 calls, $0.006000.
- Không cộng lại các trường cumulative ở nhiều manifests vì sẽ đếm trùng. Khi tiếp tục, đối chiếu mọi artifacts mới hơn trước khi dùng số dư này. Tạm dừng không cấp quyền tự động chạy thêm calls.

## 3. Kết quả mới nhất và cấu hình tái hiện

Nguồn: [báo cáo 5-run citation retest](../../reports/evaluation/ablations/live-agent-e2e-citation-retest-9docs-20261127/agent-e2e-citation-retest-summary.md).

| Case / cấu hình | Kết quả | VI-05#1 trong context | Latency server |
|---|---|---|---:|
| WHO:E01 / rerank_mmr | INFORMATION; 3 citations được chấp nhận | #5; claims số phút và ngày tập cơ có evidence | 9,717 ms |
| VI-NO-DIACRITICS-01 / rrf_current | INFORMATION; 2 citations được chấp nhận | #4 | 6,655 ms |
| VI-NO-DIACRITICS-01 / rerank | INFORMATION; 3 citations được chấp nhận | #3 | 7,671 ms |
| VI-NO-DIACRITICS-01 / rerank_mmr | INFORMATION; 3 citations được chấp nhận | #2 | 7,468 ms |
| POLICY-01 / rerank_mmr | BLOCKED / RULE_BLOCKED; ai_runs REJECTED_BY_VALIDATOR | NOT_EXERCISED; 0 tools/citations | 1,937 ms |

Mỗi cấu hình chỉ quan sát một lượt trong tập nhỏ. Query do model tự sinh thay đổi giữa các lượt; không suy ra reranker/MMR vượt trội, p95 ổn định hoặc lỗi đã được loại bỏ tuyệt đối. `turn_status=SUCCEEDED` không đồng nghĩa chất lượng đạt hoặc `ai_runs.status=SUCCEEDED`.

- Embedding: `openai/text-embedding-3-small/1536`; LLM: `openai/gpt-4o-mini-2024-07-18`; reranker: `Cohere/rerank-v3.5` trong các nhánh được bật riêng.
- `max_completion_tokens=800`, tối đa 1 retry lỗi tạm thời; `rag_search_min_top_k=5`; top_k=5; candidate_limit=15 khi rerank/MMR; context budget=8000 ký tự.
- Citation key chuẩn: `{documentCode}@{version}#{sectionIndex}`, dùng chung `format_chunk_citation_key`.
- Mặc định trong source vẫn tắt hybrid, rerank, MMR và proposal. Không suy từ mặc định source ra overrides `.env` của máy khác. Không lưu credentials trong checkpoint.
- Public F01 chưa dùng RetrievalPipeline rerank/MMR; kết quả Agent Search không nghiệm thu thay Public F01.
- Tài nguyên eval tạm đã được dọn theo báo cáo lượt chạy; phiên ghi mốc không kiểm tra lại runtime, không dựng stack mới.

## 4. Implementation và tests đã có evidence

Các sửa chính: mẫu số metrics A-49; scheduler indexing outbox; evidence chunks/citation grounding của Public F01; lexical folding V68; effective top_k của agent; canonical citation key. Có conversation API/UI, bounded agent loop, run-bound citation validator, domain read tools và proposal gateway (proposal tắt mặc định).

| Kiểm tra | Kết quả đã ghi nhận | Phạm vi |
|---|---|---|
| AI Service pytest / ruff | 129/129 PASS / PASS | Được chạy sau bổ sung domain E2E fixtures theo evidence log |
| AiAgentCrossLayerHttpE2EIntegrationTest | 8/8 PASS | Conversation → FastAPI agent loop → Spring/domain → PostgreSQL; chỉ model provider là fixture |
| Contracts | 13 schemas / 52 fixtures PASS; 22/22 tests PASS | Evidence ở các phiên trước; không chạy lại trong phiên ghi mốc |
| Evaluation runner | 27/27 PASS | Metrics/smoke-gate regression; không phải 27 live quality cases |
| Retrieval / outbox | Unit suites PASS; retrieval integration 16/16; scheduler 2/2 | Evidence lịch sử trong readiness pack |
| Proposal domains | F03 32/32; F05 47/47 PASS | Không nghiệm thu live proposal hoặc cho phép bật proposal |
| Mobile | typecheck PASS; lint 0 errors, 12 warnings cũ; Jest 31/31 PASS | Tests trước đó, không phải E2E thiết bị thật |

Domain HTTP E2E đã kiểm Student/Trainer hợp lệ, cross-user/forged/Trainer thiếu quyền, revoke giữa lượt trước domain read, thiếu dữ liệu và không mutation trên các bảng được assertions kiểm tra. Không tuyên bố toàn bộ backend suite hoặc mọi business table đã được kiểm. `continuity != null` không chứng minh đủ dữ liệu: `daysSinceLastWorkout=null` là unknown; giá trị `scheduledCount=0` phải diễn giải theo contract, không tự coi mọi số 0 là missing.

Các số test ở trên được đối chiếu từ báo cáo/evidence log hiện có; phiên lưu mốc chỉ kiểm tài liệu, không chạy lại application tests hoặc trả phí.

## 5. Phần chưa nghiệm thu và bước tiếp tục

- AG-M0..M6 vẫn IN_REVIEW/đang thực hiện theo tracker; G0–G6 chưa đóng đủ acceptance. Không đánh dấu DONE do đã push branch.
- Nhãn 16 dev cases/chunk_relevance, owner/reviewer thực tế, quyết định AD-01..08 về release/defaults và ngưỡng AD-05 trước holdout còn mở. Authorization thử nghiệm đã có không đồng nghĩa duyệt release.
- ISSN/NIH ODS còn thiếu coverage ACTIVE của benchmark; không publish DRAFT để làm đẹp điểm. Corpus 9 docs không chứng minh toàn corpus hoặc môi trường production.
- Domain tools đã có HTTP E2E với model fixture; **model live tự chọn domain tools và 5 domain live cases chưa được xác minh**.
- Clamp limit=1 → 5 có evidence offline; live mới quan sát model tự chọn limit=5.
- Chưa có E2E Mobile thiết bị thật, holdout, specialist sign-off, canary/rollback drill; rollout proposal cần quyết định riêng.

Khi chủ dự án yêu cầu tiếp tục:

1. Đọc checkpoint này → tracker → readiness pack; kiểm tra branch/HEAD/dirty state, artifacts mới hơn và runtime trước khi dựng stack hoặc gọi dịch vụ. Không tự đổi branch hoặc bỏ thay đổi hiện có.
2. Đầu việc kỹ thuật tiếp theo: tối đa 5 domain live cases đã chuẩn bị tại mục 4.3 của readiness pack, để xác minh lựa chọn tools và dữ liệu thiếu/quyền; thực hiện sau khi người dùng cho tiếp tục. Tính vào trần OpenAI cũ, dự kiến 0 Cohere calls; không mặc định đã chạy do có prompt trong hội thoại.
3. Song song, chủ dự án/reviewer xác nhận nhãn và ngưỡng; Mobile QA kiểm thiết bị thật. Chỉ chuẩn bị/chạy holdout khi có đủ quyết định và protocol cố định; không tự merge/release.

## 6. Nhận diện artifacts và lưu Git

SHA-256 đo trực tiếp lúc ghi mốc (các hash cũ trong tracker là lịch sử trước khi sửa dataset):

- [ag02-dev-benchmark.json](../../data/evaluation/ag02-dev-benchmark.json): `407a6a6b421fabe25c1f40d0d703cb021a3d4f3f5d296224161dc862d84fd20d`.
- [ag02-holdout-structure.json](../../data/evaluation/ag02-holdout-structure.json): `2d41d310ae398843b21c3b1613974bdc798e349d029ecfb58ccc2cbf46def957`.

Nhiều implementation files, migrations V66–V68, tests, datasets, docs và evaluation reports đang **untracked** lúc ghi mốc; `git commit -am` sẽ không lưu các file mới đó. Chủ dự án cần review cả tracked/untracked trước khi stage/commit/push. `.env` AI Service đã được xác minh Git-ignore; không đưa secrets, local logs hoặc raw personal data vào commit. Phiên này không xác nhận toàn bộ artifacts đã qua secret scan và không tự stage chúng.

Checkpoint không chứa API keys, giá trị credentials hoặc dữ liệu sức khỏe cá nhân. Chưa có commit SHA bàn giao mới ở thời điểm ghi; khi đọc từ branch đã push, dùng HEAD thực tế để xác định commit chứa mốc này.
