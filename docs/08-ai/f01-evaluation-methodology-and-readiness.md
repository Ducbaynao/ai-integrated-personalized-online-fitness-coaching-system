# Phương pháp Luận & Kế hoạch Đánh giá Chất lượng F01 (Evaluation Methodology & Readiness)

> **Mục tiêu:** Xác định nguồn gốc các tiêu chí đánh giá, phương pháp đo lường và tính sẵn sàng của bộ công cụ đánh giá F01 (Personalized Fitness Knowledge Inquiry).\
> **Tham chiếu:** `docs/07-development/phase-2-implementation-plan.md` mục 11.2, `docs/08-ai/phase-2-feature-data-authority-matrix.md` mục 2, `docs/08-ai/f01-a13-provider-decision.md`.\
> **Cập nhật:** 18/11/2026.

---

## 1. Nguồn Gốc và Phân Loại Các Tiêu Chí Đánh Giá

| Tiêu chí | Giá trị ngưỡng | Nguồn gốc trong tài liệu | Phân loại trạng thái |
| :--- | :---: | :--- | :--- |
| **Bộ câu hỏi kiểm thử** | 30 câu trong phạm vi + 10 câu ngoài phạm vi | `phase-2-implementation-plan.md` Mục 11.2 (bảng baseline) | **ĐÃ PHÊ DUYỆT (Approved Baseline)** theo quyết định bản 1.0 (04/10/2026). |
| **Citation Grounding (Hit Rate)** | $\ge 90\%$ (ít nhất 27/30 câu có nguồn đúng) | `phase-2-implementation-plan.md` Mục 11.2 | **ĐÃ PHÊ DUYỆT (Approved Baseline)** |
| **Tỷ lệ Hallucination (Ngoại vi)** | $0\%$ (10 câu không đủ nguồn không được bịa đặt) | `phase-2-implementation-plan.md` Mục 11.2 & Invariant #5 | **ĐÃ PHÊ DUYỆT (Approved Invariant)** |
| **P95 Latency** | $\le 8000$ms (thời gian phản hồi mạng) | `f01-a13-provider-decision.md` (DEC-A13-02) | **ĐỀ XUẤT VẬN HÀNH (Proposed Operational Target)** |
| **Mức tiêu thụ Token** | Mục tiêu $< 800$ tokens/request | `f01-a13-provider-decision.md` & `f01-a52-quality-evaluation-report.md` | **ĐỀ XUẤT VẬN HÀNH (Proposed Operational Target)** |

---

## 2. Phương Pháp Đo Lường Chi Tiết

### A. Đo lường Trích dẫn & Độ tin cậy Bằng chứng (Citation Grounding & Faithfulness)
- **Tồn tại trích dẫn**: Schema validation với Ajv 2020 (`f01-knowledge-query-result.schema.json` và `citation.schema.json`). Mọi kết quả `resultType = 'INFORMATION'` bắt buộc phải có ít nhất 1 trích dẫn hợp lệ.
- **Hỗ trợ thực tế của trích dẫn (Faithfulness)**:
  - Trường `quoteOrExcerpt` phải là chuỗi con sao chép nguyên văn (exact verbatim substring) từ nội dung của đoạn trích (`matched.content()`).
  - Kiểm tra chuẩn hóa khoảng trắng và ký tự tiếng Việt: `normContent.contains(normQuote)` trong `AiKnowledgeOrchestrationService.java`.
  - Kiểm tra trạng thái hiện tại trong database: Chunk trích dẫn phải thuộc tài liệu có trạng thái `ACTIVE` và đã vượt qua kiểm tra kỹ thuật (`isChunkEligible(...)`).

### B. Mức độ Phù hợp với Bối cảnh Cá nhân hóa (Personal Context Relevance)
- Bối cảnh học viên (`personalContext`) chỉ được sử dụng để diễn giải sự phù hợp của khuyến nghị đối với học viên (kinh nghiệm, mục tiêu, dụng cụ).
- Không được suy diễn dữ liệu cá nhân chưa được khai báo: Nếu `activeGoal = null`, câu trả lời không được gán mục tiêu giả định, đồng thời phải trả về cảnh báo trong `missingData`.
- Tuyệt đối không thay thế kiểm tra an toàn: Bối cảnh học viên không được dùng để bỏ qua chính sách cấm chất kích thích (doping/WADA).

### C. Xử lý Câu hỏi Nằm ngoài Kho Tri thức (Abstention & Guardrails)
- Manifest kiểm thử chứa các ca kiểm thử có nhãn `annotated_action = "ABSTENTION_EXPECTED"`.
- Hệ thống phải từ chối an toàn bằng:
  - `resultType: "INSUFFICIENT_DATA"` (kèm lý do `NO_RELEVANT_EVIDENCE` hoặc `MISSING_REQUIRED_CONTEXT`) khi kho tài liệu chưa có bằng chứng.
  - `runStatus: "REJECTED_BY_VALIDATOR"` + `resultType: "BLOCKED"` (lý do `RULE_BLOCKED`) khi câu hỏi vi phạm an toàn y khoa hoặc dược lý.

### D. Đo lường Token, Chi phí và Độ trễ (Usage, Cost, Latency)
- **Tokens**: Đo trực tiếp từ response `usage` (`promptTokens`, `completionTokens`, `totalTokens`).
- **Chi phí tiền tệ**: Áp dụng biểu giá OpenAI cho model `gpt-4o-mini-2024-07-18`:
  $$\text{Chi phí (USD)} = \frac{\text{promptTokens} \times 0.15 + \text{completionTokens} \times 0.60}{1{,}000{,}000}$$
- **Độ trễ (Latency)**: Đo bằng đồng hồ bấm giờ đơn vị millisecond từ khi Spring gửi envelope đến khi nhận kết quả cuối cùng.
- **Truy vết phiên bản (Lineage)**: Ghi nhận trong `GenerationMetadataDto` gồm: `schemaVersion: "1.0.0"`, `promptVersion: "v1.0"`, `ruleVersion: "f01-rule-v1"`, `provider`, `model`, `embeddingModelSpace`, `indexVersion`.

---

## 3. Tính Sẵn Sàng của Bộ Công Cụ & Tình Trạng Credential

1. **Evaluation Runner Sẵn Có**:
   - `npm run eval:manifest`: Sinh manifest chuẩn hóa từ 114 candidate cases trong `data/knowledge/evaluation-mapping.json`.
   - `npm run eval:f01`: Chạy bộ đánh giá độc lập (đã kiểm chứng qua 24 unit tests trong `tools/run-f01-evaluation.test.mjs`).
2. **Trạng thái Credential Live**:
   - Biến môi trường `OPENAI_API_KEY` hiện chưa có quota khả dụng (gặp mã lỗi `HTTP 401 Unauthorized` khi kết nối trực tiếp đến endpoint OpenAI).
   - **Kết luận**: Quá trình đánh giá trực tiếp với Live Provider OpenAI đang ở trạng thái **CHỜ CẤP CREDENTIAL HỢP LỆ (Blocked on valid API key)**. Không công bố số liệu live giả mạo khi chưa chạy thực tế. Toàn bộ hạ tầng và harness đánh giá đã hoàn thành và sẵn sàng thực thi ngay khi được cấu hình key.
