# Chẩn đoán & Sửa lỗi Offline Citation Ghép Chéo Chunk — 27/11/2026

**Phạm vi:** `reports/evaluation/baselines/live-9docs-retest-20261127/` (giữ nguyên artifacts baseline & retest)  
**Trạng thái:** **OFFLINE ONLY** — chưa gọi lại provider live.

---

## 1. Xác định nguyên nhân có căn cứ

- **Quan sát thực tế từ `WHO-PA-SB-2020:E01` (`run-20261126204908-559a4e`):**
  - Model trả về citation với `documentId="WHO-PA-SB-2020-VI-05"`, `sectionIndex=0` (chunk tiêu đề `# Vận động và tăng cường cơ ở tuổi 18–64\n\nDocument ID: WHO-PA-SB-2020-VI-05 · Version: 1.1.0`), nhưng `quoteOrExcerpt` lại là `"WHO 2020 đưa ra khuyến nghị sức khỏe cộng đồng về lượng, tần suất, cường độ vận..."` — vốn là nguyên văn đoạn đầu trong `## Nội dung` của `WHO-PA-SB-2020-VI-01` (`01-scope.vi.md`, `sectionIndex=1`).
- **Kiểm tra serialization trước khi sửa (`openai_provider.py`):**
  - `_build_user_prompt()` trước đó dùng format phẳng `--- CHUNK #i ---\ndocumentId: ...\n...\ncontent:\n{text}` **không có thẻ đóng** ranh giới từng chunk và không tách khối `<metadata>` khỏi `<content>`.
  - `OPENAI_RESPONSE_SCHEMA` ở các trường `version` và `sourceId` vẫn còn ví dụ mẫu (`1.0.0`, `ISSN-PROTEIN-EXERCISE-2017`) và chưa ràng buộc rõ mọi trường trong cùng một citation object phải đến từ **cùng một** evidence chunk.
- **Phân biệt giả thuyết với nguyên nhân đã chứng minh:**
  - *Đã chứng minh:* (1) Citation của model ghép metadata của `VI-05#0` với quote của `VI-01#1`; (2) validator của `OpenAiKnowledgeProvider` đã từ chối chính xác (`REJECTED_BY_VALIDATOR` / `UNVERIFIED_CITATION`).
  - *Giả thuyết hợp lý (chưa chứng minh nhân quả tuyệt đối chỉ từ 1 lượt chạy):* Ranh giới chunk phẳng thiếu thẻ đóng + thiếu nhấn mạnh mỗi citation là một đơn vị khép kín làm tăng xác suất model trộn metadata và quote giữa hai chunk liền kề. Cần live smoke retest để xác nhận tác dụng thực tế trên `gpt-4o-mini-2024-07-18`.

---

## 2. Sửa tối thiểu đã áp dụng

1. **`_build_user_prompt()` ([openai_provider.py](../../../../services/ai-service/app/integrations/openai_provider.py#L446-L462)):**
   - Serialize mỗi chunk thành đơn vị khép kín `<evidence_chunk index="{i}">` với `<metadata>...</metadata>` và `<content>...</content>` và thẻ đóng `</evidence_chunk>`.
   - Không lọc bỏ `sectionIndex=0`; không thay đổi contract `CitationDto` / `f01-knowledge-response.schema.json`.
2. **`OPENAI_RESPONSE_SCHEMA` & `_build_system_prompt()` ([openai_provider.py](../../../../services/ai-service/app/integrations/openai_provider.py#L55-L75)):**
   - Xóa ví dụ `1.0.0` / `ISSN-PROTEIN-EXERCISE-2017` ở `version`/`sourceId`; nêu rõ mỗi citation là một đơn vị khép kín phải lấy toàn bộ metadata và `quoteOrExcerpt` từ **cùng một `<evidence_chunk>`**.
   - Giữ nguyên `max_completion_tokens=800`, không nới validator và không tự sửa/remap metadata citation sau khi model trả về.

---

## 3. Kiểm thử Offline (Không chứng minh chất lượng live)

- `services/ai-service`: `ruff check app tests` → **All checks passed!**; `pytest -q -p no:cacheprovider` → **`126 passed`** (gồm 4 cases parametrized mới trong `test_live_regression_cross_chunk_and_same_chunk_citation_grounding`: `VI-05#0` + quote `VI-01` bị từ chối `UNVERIFIED_CITATION`, sai `version` bị từ chối, sai `sectionIndex` bị từ chối, đúng cùng chunk `VI-01#1` được chấp nhận `SUCCEEDED`, và kiểm tra serialization `<evidence_chunk>`/`<metadata>`/`<content>`).
- Contracts: `npm run validate:contracts` (13 schemas, 52 fixtures PASS) & `npm run test:contracts` (**22/22 PASS**).
- *Giới hạn:* Offline tests dùng HTTP mock, **không chứng minh** model `gpt-4o-mini-2024-07-18` sẽ sinh citation đúng khi chạy live.

---

## 4. Kế hoạch Chạy lại Live Tối thiểu (Chưa thực hiện)

- **Bước 1 (Reindex + 1 Smoke Gate):** Do stack cô lập đã dọn, cần reindex 9 docs WHO/NIDDK (`29` chunks, `text-embedding-3-small`, ước tính `<= 15,000` tokens ≈ `$0.00030`) + chạy đúng **1 smoke case** `WHO-PA-SB-2020:E01` với `--smoke-gate` (~`2,400` input + ~`350` output tokens ≈ `$0.00057`). Tổng ước tính bước 1: **`~$0.00087`**.
- **Bước 2 (Chỉ chạy nếu Smoke Gate đạt):** Chạy tiếp 4 ca còn lại (`NIDDK-WEIGHT-FACTORS-2023:E01`, `AG02-DEV:EN-01`, `AG02-DEV:VI-NO-DIACRITICS-01`, `AG02-DEV:POLICY-01`) + retrieval check (~`$0.0027`), tổng cả đợt retest **`~$0.0036`** (tích lũy dự kiến `~$0.0125 / $0.50`).
