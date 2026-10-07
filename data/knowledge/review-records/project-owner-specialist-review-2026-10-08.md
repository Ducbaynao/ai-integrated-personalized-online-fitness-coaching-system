# Biên bản ghi nhận phê duyệt chuyên môn của chủ dự án (2026-10-08)

- **Mã biên bản:** `REV-PO-20261008-01`
- **Ngày tiếp nhận xác nhận:** `2026-10-08`
- **Người thẩm định:** Chủ dự án — xác nhận trực tiếp của người dùng
- **Vai trò thẩm định:** `PROJECT_OWNER`
- **Hình thức:** Xác nhận trực tiếp trong phiên làm việc Pair Programming

---

## 1. Nguyên văn xác nhận của chủ dự án

> *"Những file kiến thức này ở dạng DRAFT chỉ là tạm thời, những nội dung trong đó đã được tôi review chuyên môn nên có thể đảm bảo, nếu được có thể sửa lại trạng thái DRAFT."*

---

## 2. Phạm vi phê duyệt chuyên môn

Phê duyệt chuyên môn này bao phủ toàn bộ nội dung kho tri thức chuẩn hóa F01 hiện có:
1. **73 bài snapshot nguồn nguyên bản (Version 1.0.0):**
   - **WHO-PA-SB-2020:** 16 bài (`WHO-PA-SB-2020-VI-01` đến `16`).
   - **NIDDK-WEIGHT-FACTORS-2023:** 8 bài (`NIDDK-WEIGHT-FACTORS-2023-VI-01` đến `08`).
   - **ISSN-PROTEIN-EXERCISE-2017:** 18 bài (`ISSN-PROTEIN-EXERCISE-2017-VI-01` đến `18`).
   - **NIH-ODS-EXERCISE-SUPPLEMENTS:** 31 bài (`NIH-ODS-EXERCISE-SUPPLEMENTS-VI-01` đến `31`).
2. **11 bài derivative đã qua chuẩn hóa và biên tập kỹ thuật (Version 1.1.0):**
   - **WHO 2020 Derivatives:** 5 bài (`WHO-PA-SB-2020-VI-01`, `02`, `03`, `05`, `06`).
   - **NIDDK Weight Factors Derivatives:** 6 bài (`NIDDK-WEIGHT-FACTORS-2023-VI-01` đến `06`), trong đó bài 05 và 06 đã tách biệt ranh giới chính sách sử dụng an toàn.
3. **114 candidate evaluation cases:** Đã đối chiếu và kiểm tra ranh giới truy vấn.

---

## 3. Phân định ranh giới trạng thái (Status Boundaries)

Hệ thống phân định nghiêm ngặt 3 tầng trạng thái độc lập:

| Tầng trạng thái | Trạng thái hiện tại | Giải thích & Ranh giới |
|---|---|---|
| **1. Review chuyên môn** (Specialist Review) | **`APPROVED`** | Đã được chủ dự án phê duyệt trực tiếp theo biên bản này. Nội dung tri thức đảm bảo tính chính xác theo tài liệu nguồn. |
| **2. Sẵn sàng kỹ thuật** (Technical Readiness) | **`PASSED`** | Đầy đủ SHA-256 byte-level, source locators chuẩn hóa, lineage, mapping 114 evaluation cases và pass validator test suite. |
| **3. Vòng đời xuất bản** (Publication Lifecycle) | **`DRAFT`** (`retrieval_enabled=false`) | **Bắt buộc giữ DRAFT.** Việc xuất bản `ACTIVE` và kích hoạt retrieval thuộc thẩm quyền luồng Content publishing workflow trong Spring Boot backend (Module Monolith). Không được tự gán `ACTIVE` ngoài quy trình xuất bản. |

---

## 4. Các giới hạn và cảnh báo pháp lý / chuyên môn không bị thay đổi bởi phê duyệt này

1. **Sai sót toán học/số liệu trong tài liệu gốc:** Phê duyệt không tự ý sửa sai số liệu gốc (ví dụ bài ODS-11 caffeine tính 140 mg thay vì 210 mg đối với người 70 kg liều 3 mg/kg). Các điểm này tiếp tục được ghi nhận là OPEN issue kỹ thuật và gắn cờ cảnh báo.
2. **Bản quyền thương mại (Commercial Rights):** Giấy phép phi thương mại (CC BY-NC-SA 3.0 IGO của WHO) vẫn giữ cờ `commercial_use_clearance: NOT_CONFIRMED` cho đến khi có thẩm định pháp lý riêng.
3. **Bản quyền benchmark:** 114 candidate evaluation cases chỉ dùng cho kiểm tra kỹ thuật cục bộ, không tự xưng là holdout dataset hay benchmark đạt chuẩn công bố.
4. **Bất biến quyền hạn (Domain Authority):** F01 chỉ cung cấp thông tin tham khảo (`INFORMATION`). Tri thức trong corpus không bao giờ được tự động chuyển thành Nutrition Target, giáo án tập luyện cá nhân, liều supplement hay đơn thuốc lâm sàng.

---

## 5. Danh sách Document ID và Checksum tương ứng

Chi tiết machine-readable với đầy đủ SHA-256 hash của từng bài được lưu trữ tại file:
`data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json`.
