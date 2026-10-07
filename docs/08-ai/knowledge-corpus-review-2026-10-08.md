# Review corpus F01 — 08/10/2026

Kết quả: **nhận tích hợp làm corpus DRAFT phục vụ review/phát triển; ghi nhận phê duyệt chuyên môn trực tiếp của Chủ dự án; giữ nguyên DRAFT và retrieval_enabled=false cho đến khi luồng Content publishing workflow được kích hoạt**.

## 1. Inventory và phạm vi kiểm tra

| Bộ nguồn | Bài Việt | Case ứng viên | Kết luận |
|---|---:|---:|---|
| WHO 2020 | 16 | 24 | Khuyến nghị vận động sức khỏe cộng đồng; 5 bài derivative v1.1.0 đã chuẩn hóa locator relative và sẵn sàng xuất bản khi workflow kích hoạt |
| ISSN protein/exercise 2017 | 18 | 30 | Thông tin protein thể thao có bối cảnh; giữ DRAFT để liên kết an toàn với Nutrition Target |
| NIDDK weight factors | 8 | 18 | Sáu nhóm yếu tố cân nặng; 6 bài derivative v1.1.0 đã chuẩn hóa; 2 bài thuốc/bệnh (05/06) đã tách policy và giữ guardrail lâm sàng |
| NIH ODS exercise supplements | 31 | 42 | Supplement tham khảo; 12 issue OPEN về lỗi số liệu nguồn (caffeine 140 vs 210 mg); giữ DRAFT |
| Tổng | **73 snapshot + 11 derivative** | **114 candidate cases** | **Specialist Review: APPROVED (Chủ dự án); Technical Readiness: 9 TECHNICAL_VERIFICATION_PASSED, 2 HELD_AS_DRAFT; Publication: DRAFT (`ACTIVE=0`, `retrieval_enabled=false`)** |

Kiểm tra toàn bộ inventory: bytes/hash file, parse JSON/JSONL, cặp article/metadata, source IDs, document IDs/version, raw hash, ranges trang curated, tham chiếu claim/issue/recommendation/statement và expected document IDs ở evaluation mapping. Giữ 361 file text nguyên byte; hai PDF được sao chép nguyên byte vào local object staging và kiểm hash với manifest.

## 2. Xác nhận phê duyệt chuyên môn của Chủ dự án (2026-10-08)

Trong phiên làm việc ngày 08/10/2026, Chủ dự án xác nhận trực tiếp:
> *"Những file kiến thức này ở dạng DRAFT chỉ là tạm thời, những nội dung trong đó đã được tôi review chuyên môn nên có thể đảm bảo, nếu được có thể sửa lại trạng thái DRAFT."*

- **Người thẩm định:** Chủ dự án — xác nhận trực tiếp của người dùng.
- **Ngày tiếp nhận:** `2026-10-08`.
- **Hồ sơ thẩm định:** `data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json` và `.md`.
- **Phân định 3 tầng trạng thái:**
  1. *Review chuyên môn:* **`APPROVED`** (toàn bộ 73 bài snapshot và 11 bài derivative được thẩm định nội dung).
  2. *Tính sẵn sàng kỹ thuật:* **`TECHNICAL_VERIFICATION_PASSED`** (9 bài sẵn sàng cho publish workflow: WHO 01/02/03/05/06, NIDDK 01/02/03/04; 2 bài NIDDK 05/06 giữ **`HELD_AS_DRAFT`** do guardrail an toàn thuốc/rối loạn ăn uống).
  3. *Vòng đời xuất bản:* **`DRAFT`** (`retrieval_enabled=false`). Lý do: Trạng thái `ACTIVE` phải được thiết lập qua luồng Content publishing workflow trong Spring Boot backend (Module Monolith Content Domain Command với audit log trong PostgreSQL), không phải vì thiếu review chuyên môn.

## 3. Findings và cách xử lý cập nhật

| ID | Finding | Xử lý |
|---|---|---|
| KR01 | Mọi curated metadata DRAFT, cần review chuyên môn | **ĐÃ GIẢI QUYẾT:** Ghi nhận phê duyệt chuyên môn của Chủ dự án ngày 08/10/2026; giữ publication DRAFT cho đến khi kích hoạt qua Spring CMS |
| KR02 | WHO 2020 không đủ kỹ thuật squat/deadlift, set/rep hoặc thời gian recovery | Bổ sung bài viết về giới hạn nguồn; từ chối bịa citation trong evaluation |
| KR03 | WHO có nhóm trẻ/thai kỳ/bệnh/khuyết tật và giấy phép CC BY-NC-SA 3.0 IGO | Giữ scope/điều kiện từng bài; cờ `commercial_use_clearance: NOT_CONFIRMED`; chuẩn hóa chính xác Recommendation IDs (C01–C03, O01–O07, P01–P05, H01–H07, DC01–DC03, D01–D07) |
| KR04 | ISSN có bất nhất EAA, đơn vị carbohydrate, lập luận soy và an toàn protein cao | Ghi nhận trong issue register; không chuyển con số vào Rule Engine/Nutrition Target |
| KR05 | NIDDK 05/06 trộn claim nguồn với “Giới hạn sử dụng của dự án” | **ĐÃ GIẢI QUYẾT:** Đã tách chính sách sang `project_usage_policy` trong derivative v1.1.0; giữ `HELD_AS_DRAFT` vì lý do an toàn thuốc/rối loạn ăn uống |
| KR06 | ODS-Q01: 2–6 mg/kg × 70 kg không khớp ví dụ 210–420 mg (tính đúng là 140 mg) | Giữ snapshot và issue; cách ly ví dụ tính sai khỏi prompt tính liều |
| KR07 | ODS-Q02/Q03: nitric acid/oxide; Q04 protein thực vật; Q05 bicarbonate | Cảnh báo cơ chế trong tài liệu; không tạo rule từ con số |
| KR08 | ODS-Q06/Q09 và articles 25–28 là quy định lịch sử/Mỹ | Gắn cờ quy định lịch sử; loại khỏi tập tri thức tư vấn luật hiện hành |
| KR09 | ODS thiếu bibliography 1–219, exact input edition chưa xác nhận | Citation về snapshot fact sheet/mục; không giả 219 nghiên cứu đã nhập |
| KR10 | Nhiều representation trùng; project guidance/reference/evaluation | Chọn representation curated có review; không glob toàn bundle để index |
| KR11 | Một số đoạn bản Việt ODS có từ/số dính nhau | Đã ghi nhận trong changelog biên tập |
| KR12 | 114 candidate cases đánh giá F01 | **ĐÃ GIẢI QUYẾT:** Đã lập bảng ánh xạ machine-readable tại `data/knowledge/evaluation-mapping.json` với phân loại case rõ ràng, ràng buộc chặt chẽ với document version và derivative lineage; không ingest vào vector database |

## 4. Bàn giao và kết quả kiểm chứng

Kiểm chứng tự động trong phiên 08/10/2026:
- `npm run test:knowledge`: **24/24 tests PASS** (bao gồm kiểm thử clean checkout CI không có PDF local staging, kiểm thử cờ `checkLocalAssets`, 15 negative tests kiểm tra base SHA-256, base document/version mismatch, source locator unresolvable, metadata/index conflict, evaluation non-existent ID, evaluation derivative version/lineage mismatch, review record hash/version/document mismatch, duplicate/missing review records).
- `npm run validate:knowledge`: **PASS** (mặc định kiểm tra tính toàn vẹn bundle, metadata, review record binding, evaluation mapping mà không phụ thuộc file binary PDF trên CI).
- `node tools/validate-knowledge.mjs --check-local-assets`: **PASS** (kiểm tra đầy đủ 4 nguồn, 73 bài snapshot, 114 candidate cases, 361 file text, 2 external PDF staging với SHA-256 và byte size; 11 derivative articles).
- `npm run validate:contracts`, `npm run validate:ux-docs` và `git diff --check`: **PASS**.

Tài liệu chi tiết bàn giao:
- [f01-knowledge-corpus-detailed-review](f01-knowledge-corpus-detailed-review.md): Báo cáo chi tiết từng bài, ranh giới chuyên môn và bảng ánh xạ 114 evaluation cases.
- `data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json` (và `.md`): Biên bản phê duyệt chuyên môn trực tiếp của Chủ dự án ràng buộc chặt chẽ theo exact document ID, version, path và `content_sha256`.
- `data/knowledge/evaluation-mapping.json`: Bảng ánh xạ machine-readable toàn bộ 114 candidate cases với ràng buộc derivative lineage và version.
- `data/knowledge/derivative-index.json`: Manifest và SHA-256 của 11 bài derivative v1.1.0 đã chuẩn hóa locator relative và trạng thái sẵn sàng kỹ thuật.
