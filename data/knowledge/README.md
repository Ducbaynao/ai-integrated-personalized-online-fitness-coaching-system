# Corpus chuẩn bị cho F01

Tích hợp ngày 08/10/2026 từ bốn bộ tài liệu người dùng cung cấp trong `D:\Projects\docrag`.
Có **73 bài snapshot Việt, 11 bài derivative v1.1.0, 114 case đánh giá ứng viên và 361 file văn bản**.

## Cấu trúc thư mục và quản lý trạng thái

- `packages/`: Giữ nguyên cấu trúc, bytes văn bản, metadata, attribution và báo cáo gốc của 4 bộ tài liệu. Tuyệt đối không thay đổi snapshot gốc để bảo toàn SHA-256 checksum.
- `package-index.json`: Manifest tổng các snapshot nguồn nguyên bản, danh sách file/bytes/SHA-256 và định danh bài/version 1.0.0.
- `derivatives/`: Derivative versions (version 1.1.0) đã qua chuẩn hóa source locator relative (`../../packages/`), tách phần hướng dẫn dự án (NIDDK 05/06) sang metadata `project_usage_policy`, và ghi nhận phê duyệt chuyên môn của Chủ dự án.
- `derivative-index.json`: Manifest và checksum SHA-256 của các bài derivative; giữ nguyên publication `status: "DRAFT"` và `retrieval_enabled: false`.
- `review-records/`: Lưu trữ hồ sơ phê duyệt chuyên môn trực tiếp của Chủ dự án (`project-owner-specialist-review-2026-10-08.json` và `.md`), ghi nhận nguyên văn xác nhận và phạm vi thẩm định cho toàn bộ 73 bài snapshot và 11 bài derivative.
- `evaluation-mapping.json`: Bảng ánh xạ machine-readable toàn bộ 114 candidate evaluation cases tới tài liệu, version cụ thể, phân loại case (`DIRECT_KNOWLEDGE`, `OUT_OF_SCOPE_REFUSAL`, `SPECIALIZED_POPULATION`, `POLICY_GUARDRAIL`, `EVIDENCE_LIMITATION`, `DISPUTED_OR_DATA_ERROR`) và yêu cầu không bịa citation giả đối với trường hợp từ chối/giới hạn.
- PDF được lưu trữ tại `infrastructure/data/knowledge/<sha256>.pdf` trong local object staging (đã được `.gitignore` loại khỏi Git). `source_pdf` trong metadata là đường dẫn logic trong bundle, được resolve qua `package-index.json` tới `local_staging_path`.

## Phân định ba tầng trạng thái

1. **Review chuyên môn (Specialist Review):** **`APPROVED`** — Toàn bộ nội dung tri thức đã được Chủ dự án trực tiếp thẩm định và xác nhận ngày 08/10/2026.
2. **Tính sẵn sàng kỹ thuật (Technical Readiness):** **`TECHNICAL_VERIFICATION_PASSED`** (9 bài sẵn sàng xuất bản: WHO 01/02/03/05/06, NIDDK 01/02/03/04) và **`HELD_AS_DRAFT`** (2 bài NIDDK 05/06 do guardrail an toàn thuốc/rối loạn ăn uống). Đầy đủ SHA-256, relative locators, lineage, mapping 114 evaluation cases và pass 100% kiểm thử validator.
3. **Vòng đời xuất bản (Publication Lifecycle):** Bắt buộc giữ **`DRAFT`** (`retrieval_enabled: false`). Lý do: Trạng thái `ACTIVE` phải được kích hoạt qua luồng Content publishing workflow trong Spring Boot backend (PostgreSQL System of Record với audit log), không tự gán `ACTIVE` ngoài quy trình xuất bản.

## Lệnh kiểm tra và xác thực

```powershell
npm run validate:knowledge
npm run test:knowledge
node tools/validate-knowledge.mjs --check-local-assets
```

- `npm run validate:knowledge`: Kiểm tra cấu trúc index, file text, hash, locators, evaluation mapping, review records; chế độ mặc định tương thích CI clean checkout không yêu cầu file PDF.
- `npm run test:knowledge`: Chạy bộ 24 kiểm thử toàn diện: CI clean checkout không có PDF, cờ `checkLocalAssets`, và 15 negative tests chuyên biệt (sai base SHA-256, sai base document/version, sai source locator, xung đột metadata/index, evaluation dùng ID không tồn tại, evaluation derivative version/lineage mismatch, review record hash/version/document mismatch, duplicate/missing review records).
- `node tools/validate-knowledge.mjs --check-local-assets`: Kiểm tra thêm bytes/hash của file PDF trong local object staging khi có asset cục bộ.

## Tài liệu liên quan

- [Báo cáo review chi tiết từng bài và bảng ánh xạ evaluation](../../docs/08-ai/f01-knowledge-corpus-detailed-review.md)
- [Báo cáo tổng hợp review ngày 08/10/2026](../../docs/08-ai/knowledge-corpus-review-2026-10-08.md)
- [Hợp đồng dữ liệu import tri thức](../../docs/08-ai/knowledge-import-contract.md)
